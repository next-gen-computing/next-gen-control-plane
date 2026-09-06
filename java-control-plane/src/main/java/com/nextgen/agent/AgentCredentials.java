package com.nextgen.agent;

import com.nextgen.controlplane.EnvConfig;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.PkiException;
import com.nextgen.security.PkiPaths;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;

/**
 * The node's own key material: its private key, its issued client certificate, and the CA
 * certificate it uses to verify the control plane.
 *
 * <p><b>The private key never leaves this machine.</b> The agent generates its own key pair, builds a
 * PKCS#10 CSR, and sends only the CSR. This is the structural difference from the previous
 * {@code TlsCertificateGenerator}, which base64'd a public key, labelled it a CERTIFICATE, and
 * concatenated the private key into the same string before sending the lot to the peer.
 */
public final class AgentCredentials {
    private static final Logger LOG = LoggerFactory.getLogger(AgentCredentials.class);

    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private final Path directory;

    private KeyPair keyPair;
    private X509Certificate certificate;
    private String caCertificatePem;

    public AgentCredentials(Path directory) {
        this.directory = directory;
    }

    /** Resolves the agent's credential directory from {@code NEXTGEN_AGENT_PKI_DIR}. */
    public static AgentCredentials fromEnvironment() {
        return new AgentCredentials(Paths.get(EnvConfig.stringValue("NEXTGEN_AGENT_PKI_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "agent").toString())));
    }

    public Path keyFile()         { return directory.resolve("node.key"); }
    public Path certificateFile() { return directory.resolve("node.crt"); }
    public Path caFile()          { return directory.resolve("ca.crt"); }

    /** Loads existing material from disk, if any. */
    public synchronized void load() {
        try {
            if (Files.exists(keyFile()) && Files.exists(certificateFile()) && Files.exists(caFile())) {
                PrivateKey key = CertificateAuthority.readPrivateKey(Files.readString(keyFile()));
                certificate = CertificateAuthority.readCertificate(Files.readString(certificateFile()));
                caCertificatePem = Files.readString(caFile());
                keyPair = new KeyPair(certificate.getPublicKey(), key);
                LOG.info("Loaded existing node credentials (serial {}, expires {})",
                        certificate.getSerialNumber().toString(16), certificate.getNotAfter());
            }
        } catch (IOException | PkiException e) {
            // Corrupt or partial material is discarded rather than half-used; the agent re-enrols.
            LOG.warn("Existing node credentials could not be loaded ({}); will re-enrol", e.getMessage());
            keyPair = null;
            certificate = null;
            caCertificatePem = null;
        }
    }

    /**
     * Generates a fresh key pair and a CSR for it.
     *
     * <p>The CSR's subject is a placeholder: the control plane discards it and substitutes the node
     * id bound to the consumed enrolment token.
     */
    public synchronized String createCsr(String nodeId) {
        try {
            keyPair = CertificateAuthority.generateKeyPair();
            X500Name subject = new X500Name("CN=" + nodeId.replaceAll("([,+\"\\\\<>;=])", "\\\\$1"));
            JcaPKCS10CertificationRequestBuilder builder =
                    new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
            // Self-signed with the private key: this is the proof of possession the CA verifies.
            ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                    .build(keyPair.getPrivate());
            PKCS10CertificationRequest csr = builder.build(signer);
            return CertificateAuthority.toPem("CERTIFICATE REQUEST", csr.getEncoded());
        } catch (Exception e) {
            throw new PkiException("Could not build a certificate signing request", e);
        }
    }

    /** Persists an issued certificate alongside the key that was generated for it. */
    public synchronized void store(String certificatePem, String caPem) {
        if (keyPair == null) {
            throw new PkiException("No key pair to associate with the issued certificate");
        }
        try {
            Files.createDirectories(directory);
            PkiPaths.restrictToOwner(directory);

            certificate = CertificateAuthority.readCertificate(certificatePem);
            caCertificatePem = caPem;

            PkiPaths.writeSecret(keyFile(),
                    CertificateAuthority.pemPrivateKey(keyPair.getPrivate())
                            .getBytes(StandardCharsets.UTF_8));
            Files.writeString(certificateFile(), certificatePem);
            Files.writeString(caFile(), caPem);
            LOG.info("Stored node certificate serial {} (expires {})",
                    certificate.getSerialNumber().toString(16), certificate.getNotAfter());
        } catch (IOException e) {
            throw new PkiException("Could not persist node credentials to " + directory, e);
        }
    }

    public synchronized boolean hasCertificate() {
        return certificate != null && keyPair != null && caCertificatePem != null;
    }

    public synchronized Optional<X509Certificate> certificate() {
        return Optional.ofNullable(certificate);
    }

    public synchronized PrivateKey privateKey() {
        return keyPair == null ? null : keyPair.getPrivate();
    }

    public synchronized String caCertificatePem() {
        return caCertificatePem;
    }

    /** True when the certificate has expired or is close enough that renewal should be attempted. */
    public synchronized boolean needsRenewal(Instant now, java.time.Duration renewWindow) {
        if (certificate == null) {
            return true;
        }
        return now.plus(renewWindow).isAfter(certificate.getNotAfter().toInstant());
    }

    /**
     * The CA certificate to trust when first contacting the control plane, before any certificate has
     * been issued. Read from {@code NEXTGEN_CA_CERT} (a path) or from a previously stored copy.
     *
     * <p>A CA certificate is public data — distributing it out of band is not a chicken-and-egg
     * problem, and it is strictly safer than trust-on-first-use, which would expose the enrolment
     * token to an active man-in-the-middle.
     */
    public synchronized Optional<String> bootstrapCaCertificate() {
        String configured = EnvConfig.stringValue("NEXTGEN_CA_CERT", "");
        if (!configured.isEmpty()) {
            try {
                return Optional.of(Files.readString(Paths.get(configured)));
            } catch (IOException e) {
                throw new PkiException("NEXTGEN_CA_CERT points at " + configured
                        + " which could not be read", e);
            }
        }
        if (caCertificatePem != null) {
            return Optional.of(caCertificatePem);
        }
        try {
            if (Files.exists(caFile())) {
                return Optional.of(Files.readString(caFile()));
            }
        } catch (IOException e) {
            LOG.warn("Could not read the stored CA certificate: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
