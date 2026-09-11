package com.nextgen.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The cluster's certificate authority: bootstraps itself on first use, issues client certificates
 * from CSRs, and maintains the issuance ledger.
 *
 * <h2>Design notes</h2>
 *
 * <p><b>EC P-256, not RSA.</b> {@code SHA256withECDSA} over {@code secp256r1}. RSA-3072 key
 * generation takes seconds, which would make every enrollment and every PKI test slow enough to be
 * annoying; P-256 is supported natively by both the JDK and BoringSSL.
 *
 * <p><b>BouncyCastle is used as a library only.</b> Certificates are built with
 * {@link JcaX509v3CertificateBuilder} and signed through the default JCA provider — no
 * {@code Security.addProvider(new BouncyCastleProvider())} anywhere. See the note in the root POM:
 * registering it would work in tests and fail inside the shaded jar.
 *
 * <p><b>The node's private key never leaves the node.</b> Issuance takes a PKCS#10 CSR, verifies
 * proof of possession, and then <i>discards the CSR's subject entirely</i>, substituting the node id
 * that the consumed enrollment token was bound to. Trusting a client-supplied subject would let any
 * enrolling node name itself anything it liked.
 */
public final class CertificateAuthority {
    private static final Logger LOG = LoggerFactory.getLogger(CertificateAuthority.class);

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private static final Duration CA_VALIDITY = Duration.ofDays(3650);
    private static final Duration SERVER_VALIDITY = Duration.ofDays(365);
    /** Client certificates are short-lived; agents renew automatically at ~2/3 of the lifetime. */
    public static final Duration CLIENT_VALIDITY = Duration.ofDays(30);

    private final PkiPaths paths;
    private final Clock clock;
    private final CertificateDenylist denylist;

    private final X509Certificate caCertificate;
    private final PrivateKey caPrivateKey;
    private X509Certificate serverCertificate;
    private PrivateKey serverPrivateKey;

    public CertificateAuthority(PkiPaths paths, Clock clock) {
        this(paths, clock, null);
    }

    public CertificateAuthority(PkiPaths paths, Clock clock, CertificateDenylist denylist) {
        this.paths = paths;
        this.clock = clock;
        this.denylist = denylist == null
                ? new CertificateDenylist(paths.revokedFile(), Duration.ofSeconds(5), System::nanoTime)
                : denylist;
        try {
            paths.ensureDirectory();
            if (Files.exists(paths.caCertificate()) && Files.exists(paths.caKey())) {
                this.caCertificate = readCertificate(readString(paths.caCertificate()));
                this.caPrivateKey = readPrivateKey(readString(paths.caKey()));
                LOG.info("Loaded existing CA: {}", caCertificate.getSubjectX500Principal().getName());
            } else {
                KeyPair caKeys = generateKeyPair();
                this.caPrivateKey = caKeys.getPrivate();
                this.caCertificate = bootstrapCa(caKeys);
                LOG.info("Bootstrapped a new CA at {}", paths.directory());
            }
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException("Failed to initialise the PKI at " + paths.directory(), e);
        }
    }

    public X509Certificate caCertificate() {
        return caCertificate;
    }

    public CertificateDenylist denylist() {
        return denylist;
    }

    public String caCertificatePem() {
        return toPem("CERTIFICATE", encoded(caCertificate));
    }

    // ── Bootstrap ────────────────────────────────────────────────────────────

    private X509Certificate bootstrapCa(KeyPair caKeys) throws Exception {
        Instant now = clock.instant();
        X500Name subject = new X500Name("CN=nextgen-control-plane-ca,O=nextgen");

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, nextSerial(), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(CA_VALIDITY)), subject, caKeys.getPublic());

        // pathLen=0: this CA may sign leaf certificates but no intermediate CAs.
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(caKeys.getPublic()));

        X509Certificate certificate = sign(builder, caKeys.getPrivate());

        PkiPaths.writeSecret(paths.caKey(), pemPrivateKey(caKeys.getPrivate()).getBytes(StandardCharsets.UTF_8));
        Files.writeString(paths.caCertificate(), toPem("CERTIFICATE", encoded(certificate)));
        return certificate;
    }

    /**
     * Returns the server's leaf certificate, generating it on first use.
     *
     * @param sanHosts hostnames and IP literals the server may be reached as. A client verifies the
     *                 name it dialled against these, so a control plane published under a public DNS
     *                 name must include that name here.
     */
    public synchronized ServerMaterial ensureServerCertificate(List<String> sanHosts) {
        if (serverCertificate != null && serverPrivateKey != null) {
            return new ServerMaterial(serverCertificate, serverPrivateKey);
        }
        try {
            if (Files.exists(paths.serverCertificate()) && Files.exists(paths.serverKey())) {
                serverCertificate = readCertificate(readString(paths.serverCertificate()));
                serverPrivateKey = readPrivateKey(readString(paths.serverKey()));
                return new ServerMaterial(serverCertificate, serverPrivateKey);
            }

            KeyPair keys = generateKeyPair();
            Instant now = clock.instant();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    X500Name.getInstance(caCertificate.getSubjectX500Principal().getEncoded()),
                    nextSerial(),
                    Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(SERVER_VALIDITY)),
                    new X500Name("CN=nextgen-control-plane,O=nextgen"),
                    keys.getPublic());

            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames(sanHosts));

            serverCertificate = sign(builder, caPrivateKey);
            serverPrivateKey = keys.getPrivate();

            PkiPaths.writeSecret(paths.serverKey(),
                    pemPrivateKey(serverPrivateKey).getBytes(StandardCharsets.UTF_8));
            Files.writeString(paths.serverCertificate(), toPem("CERTIFICATE", encoded(serverCertificate)));
            LOG.info("Issued a server certificate valid for {}", sanHosts);
            return new ServerMaterial(serverCertificate, serverPrivateKey);
        } catch (Exception e) {
            throw new PkiException("Failed to create the server certificate", e);
        }
    }

    private static GeneralNames subjectAltNames(List<String> hosts) {
        List<GeneralName> names = new ArrayList<>();
        for (String host : hosts) {
            names.add(host.matches("^[0-9.]+$") || host.contains(":")
                    ? new GeneralName(GeneralName.iPAddress, host)
                    : new GeneralName(GeneralName.dNSName, host));
        }
        if (names.isEmpty()) {
            names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        }
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    // ── Issuance ─────────────────────────────────────────────────────────────

    /**
     * Issues a client certificate for {@code nodeId} from the supplied CSR.
     *
     * @param nodeId the identity the consumed enrollment token was bound to. This — not anything in
     *               the CSR — becomes the certificate's common name.
     */
    public synchronized IssuedCertificate issueClientCertificate(String nodeId,
                                                                 PKCS10CertificationRequest csr) {
        try {
            // Proof of possession: the CSR must be self-signed by the key it carries, or an attacker
            // could enrol someone else's public key and receive a certificate for it.
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder()
                    .build(csr.getSubjectPublicKeyInfo()))) {
                throw new PkiException("CSR signature is not valid for the enclosed public key");
            }

            Instant now = clock.instant();
            BigInteger serial = nextSerial();

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    X500Name.getInstance(caCertificate.getSubjectX500Principal().getEncoded()),
                    serial,
                    Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(CLIENT_VALIDITY)),
                    // The CSR's own subject is discarded on purpose.
                    new X500Name("CN=" + escapeCn(nodeId) + ",O=nextgen"),
                    publicKeyOf(csr));

            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier,
                            "spiffe://nextgen/node/" + nodeId)));

            X509Certificate certificate = sign(builder, caPrivateKey);

            // At most one live certificate per node: re-enrolling revokes the previous serial, which
            // both preserves the invariant and bounds accumulation in the ledger.
            currentCertificateFor(nodeId).ifPresent(previous ->
                    denylist.revoke(previous.serial(), previous.notAfter(), "superseded by re-enrolment"));

            appendToIndex(serial, nodeId, now, now.plus(CLIENT_VALIDITY));

            return new IssuedCertificate(certificate, serial, now, now.plus(CLIENT_VALIDITY),
                    toPem("CERTIFICATE", encoded(certificate)));
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException("Failed to issue a client certificate for node " + nodeId, e);
        }
    }

    /** The most recently issued, unexpired certificate for a node, if any. */
    public Optional<IssuedRecord> currentCertificateFor(String nodeId) {
        try {
            if (!Files.exists(paths.indexFile())) {
                return Optional.empty();
            }
            IssuedRecord latest = null;
            for (String line : Files.readAllLines(paths.indexFile())) {
                String[] parts = line.split(",");
                if (parts.length < 4 || !parts[2].equals(nodeId)) {
                    continue;
                }
                try {
                    latest = new IssuedRecord(new BigInteger(parts[0], 16),
                            Instant.ofEpochMilli(Long.parseLong(parts[1])), parts[2],
                            Instant.ofEpochMilli(Long.parseLong(parts[3])));
                } catch (NumberFormatException ignored) {
                    // A malformed ledger line is skipped, not fatal.
                }
            }
            return Optional.ofNullable(latest);
        } catch (IOException e) {
            LOG.warn("Could not read the issuance ledger: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void appendToIndex(BigInteger serial, String nodeId, Instant issuedAt, Instant notAfter)
            throws IOException {
        String line = String.format(Locale.ROOT, "%s,%d,%s,%d%n",
                serial.toString(16), notAfter.toEpochMilli(), nodeId, issuedAt.toEpochMilli());
        Files.writeString(paths.indexFile(), line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Monotonic serial counter, flushed before use so a crash cannot reissue a serial. */
    private synchronized BigInteger nextSerial() throws IOException {
        BigInteger current = BigInteger.ONE;
        if (Files.exists(paths.serialFile())) {
            try {
                current = new BigInteger(Files.readString(paths.serialFile()).trim(), 16).add(BigInteger.ONE);
            } catch (NumberFormatException e) {
                throw new PkiException("Serial file is corrupt: " + paths.serialFile(), e);
            }
        }
        Files.writeString(paths.serialFile(), current.toString(16),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        return current;
    }

    // ── Crypto helpers ───────────────────────────────────────────────────────

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new PkiException("Could not generate an EC key pair", e);
        }
    }

    private X509Certificate sign(JcaX509v3CertificateBuilder builder, PrivateKey signingKey)
            throws Exception {
        // No .setProvider("BC"): the default JCA provider (SunEC) signs this fine, and staying off
        // the BouncyCastle provider keeps the shaded jar working.
        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signingKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (Exception e) {
            throw new PkiException("Could not encode a certificate", e);
        }
    }

    /** Escapes a node id for safe inclusion in an RDN value. */
    private static String escapeCn(String nodeId) {
        return nodeId.replaceAll("([,+\"\\\\<>;=])", "\\\\$1");
    }

    public static String toPem(String type, byte[] der) {
        try (StringWriter out = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(new PemObject(type, der));
            writer.flush();
            return out.toString();
        } catch (IOException e) {
            throw new PkiException("Could not PEM-encode a " + type, e);
        }
    }

    public static String pemPrivateKey(PrivateKey key) {
        return toPem("PRIVATE KEY", key.getEncoded());
    }

    public static X509Certificate readCertificate(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(
                            pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new PkiException("Could not parse a certificate", e);
        }
    }

    public static PrivateKey readPrivateKey(String pem) {
        try (PemReader reader = new PemReader(new StringReader(pem))) {
            PemObject object = reader.readPemObject();
            if (object == null) {
                throw new PkiException("No PEM object found where a private key was expected");
            }
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(object.getContent()));
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException("Could not parse a private key", e);
        }
    }

    /**
     * Extracts the public key from a CSR.
     *
     * <p>Uses {@link JcaPEMKeyConverter}, which resolves the key through the default JCA provider.
     * {@code BouncyCastleProvider.getPublicKey(...)} looks the provider up by name and silently
     * returns null when BouncyCastle is not registered — and this codebase deliberately never
     * registers it (see the note in the root POM).
     */
    public static PublicKey publicKeyOf(PKCS10CertificationRequest csr) {
        try {
            return new JcaPEMKeyConverter().getPublicKey(csr.getSubjectPublicKeyInfo());
        } catch (Exception e) {
            throw new PkiException("Could not read the public key from a CSR", e);
        }
    }

    private static String readString(java.nio.file.Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new PkiException("Could not read " + path, e);
        }
    }

    // ── Value types ──────────────────────────────────────────────────────────

    public record IssuedCertificate(X509Certificate certificate, BigInteger serial,
                                    Instant notBefore, Instant notAfter, String pem) {
        /** Two thirds of the lifetime — when the agent should start trying to renew. */
        public Instant renewAfter() {
            long lifetime = notAfter.toEpochMilli() - notBefore.toEpochMilli();
            return notBefore.plusMillis((long) (lifetime * 2.0 / 3.0));
        }
    }

    public record IssuedRecord(BigInteger serial, Instant notAfter, String nodeId, Instant issuedAt) {
    }

    public record ServerMaterial(X509Certificate certificate, PrivateKey privateKey) {
    }
}
