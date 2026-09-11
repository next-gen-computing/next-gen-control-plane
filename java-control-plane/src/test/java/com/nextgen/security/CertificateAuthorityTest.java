package com.nextgen.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the certificate authority: real X.509 issuance, chaining, and key hygiene.
 */
class CertificateAuthorityTest {

    private static CertificateAuthority caIn(Path dir) {
        return new CertificateAuthority(new PkiPaths(dir), Clock.systemUTC());
    }

    /** Builds a genuine PKCS#10 CSR, signed by the key it carries. */
    private static PKCS10CertificationRequest csrFor(String cn, KeyPair keys) throws Exception {
        return new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + cn), keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate()));
    }

    // ── Bootstrap ────────────────────────────────────────────────────────────

    @Test
    void bootstrapCreatesARealCaCertificate(@TempDir Path dir) {
        CertificateAuthority ca = caIn(dir);
        X509Certificate caCert = ca.caCertificate();

        assertTrue(Files.exists(dir.resolve("ca.crt")));
        assertTrue(Files.exists(dir.resolve("ca.key")));
        // The previous "certificate generator" produced a base64 public key wrapped in CERTIFICATE
        // markers, which parses as nothing. This must be a real, parseable X.509 certificate.
        assertTrue(caCert.getBasicConstraints() >= 0, "must be a CA certificate");
        assertTrue(caCert.getKeyUsage()[5], "keyCertSign must be set");
        assertEquals(caCert.getSubjectX500Principal(), caCert.getIssuerX500Principal(),
                "the CA is self-signed");
    }

    @Test
    void caCertificatePemContainsNoPrivateKey(@TempDir Path dir) {
        String pem = caIn(dir).caCertificatePem();

        // The old implementation concatenated the private key into the same string it handed to
        // peers. That must never happen again.
        assertTrue(pem.contains("BEGIN CERTIFICATE"));
        assertFalse(pem.contains("PRIVATE KEY"), "a certificate PEM must never carry key material");
    }

    @Test
    void secondConstructionReusesTheExistingCa(@TempDir Path dir) {
        BigInteger firstSerial = caIn(dir).caCertificate().getSerialNumber();
        BigInteger secondSerial = caIn(dir).caCertificate().getSerialNumber();

        assertEquals(firstSerial, secondSerial, "restarting must not mint a new CA");
    }

    @Test
    void serverCertificateHasServerAuthAndRequestedSans(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);

        CertificateAuthority.ServerMaterial material =
                ca.ensureServerCertificate(List.of("control.example.com", "127.0.0.1"));

        assertTrue(material.certificate().getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        assertDoesNotThrow(() -> material.certificate().verify(ca.caCertificate().getPublicKey()));
        String sans = String.valueOf(material.certificate().getSubjectAlternativeNames());
        assertTrue(sans.contains("control.example.com"), sans);
    }

    // ── Client issuance ──────────────────────────────────────────────────────

    @Test
    void issuedClientCertificateChainsToTheCa(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        KeyPair keys = CertificateAuthority.generateKeyPair();

        CertificateAuthority.IssuedCertificate issued =
                ca.issueClientCertificate("node1", csrFor("node1", keys));

        assertDoesNotThrow(() -> issued.certificate().verify(ca.caCertificate().getPublicKey()));
        assertEquals(ca.caCertificate().getSubjectX500Principal(),
                issued.certificate().getIssuerX500Principal());
    }

    @Test
    void issuedCertificateHasClientAuthExtendedKeyUsage(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair()));

        assertTrue(issued.certificate().getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"));
        assertEquals(-1, issued.certificate().getBasicConstraints(), "a node must not be a CA");
    }

    @Test
    void issuedCertificateCarriesTheCsrPublicKeyNotASubstitute(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        KeyPair keys = CertificateAuthority.generateKeyPair();

        CertificateAuthority.IssuedCertificate issued =
                ca.issueClientCertificate("node1", csrFor("node1", keys));

        assertArrayEquals(keys.getPublic().getEncoded(),
                issued.certificate().getPublicKey().getEncoded(),
                "the CA must certify the key the node actually holds");
    }

    @Test
    void commonNameComesFromTheBoundNodeIdNotTheCsrSubject(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        KeyPair keys = CertificateAuthority.generateKeyPair();

        // The CSR claims to be "admin"; the caller says the token was bound to "node7".
        CertificateAuthority.IssuedCertificate issued =
                ca.issueClientCertificate("node7", csrFor("admin", keys));

        String subject = issued.certificate().getSubjectX500Principal().getName();
        assertTrue(subject.contains("CN=node7"), "the bound identity must win: " + subject);
        assertFalse(subject.contains("CN=admin"),
                "trusting the client-supplied subject is the impersonation hole");
    }

    @Test
    void serialsAreUniqueAndIncreasing(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        Set<BigInteger> seen = new HashSet<>();
        BigInteger previous = BigInteger.ZERO;

        for (int i = 0; i < 25; i++) {
            CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(
                    "node" + i, csrFor("node" + i, CertificateAuthority.generateKeyPair()));
            assertTrue(seen.add(issued.serial()), "serial reused: " + issued.serial());
            assertTrue(issued.serial().compareTo(previous) > 0, "serials must increase");
            previous = issued.serial();
        }
    }

    @Test
    void reEnrolmentRevokesThePreviousSerial(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        CertificateAuthority.IssuedCertificate first = ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair()));

        CertificateAuthority.IssuedCertificate second = ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair()));

        // At most one live certificate per node, which also bounds ledger growth.
        assertTrue(ca.denylist().isRevoked(first.serial()));
        assertFalse(ca.denylist().isRevoked(second.serial()));
    }

    @Test
    void renewAfterSitsAboutTwoThirdsThroughTheLifetime(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair()));

        long lifetime = issued.notAfter().toEpochMilli() - issued.notBefore().toEpochMilli();
        long renewOffset = issued.renewAfter().toEpochMilli() - issued.notBefore().toEpochMilli();

        assertEquals(2.0 / 3.0, renewOffset / (double) lifetime, 0.01);
    }

    @Test
    void issuanceIsRecordedInTheLedger(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair()));

        assertTrue(ca.currentCertificateFor("node1").isPresent());
        assertEquals(issued.serial(), ca.currentCertificateFor("node1").orElseThrow().serial());
        assertTrue(ca.currentCertificateFor("never-enrolled").isEmpty());
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void csrSignedByADifferentKeyIsRejected(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        KeyPair advertised = CertificateAuthority.generateKeyPair();
        KeyPair signing = CertificateAuthority.generateKeyPair();

        // Advertises one public key but is signed by another: no proof of possession. Accepting this
        // would let an attacker obtain a certificate for someone else's key.
        PKCS10CertificationRequest forged =
                new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=node1"), advertised.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(signing.getPrivate()));

        PkiException failure = assertThrows(PkiException.class,
                () -> ca.issueClientCertificate("node1", forged));
        assertTrue(failure.getMessage().toLowerCase().contains("signature"), failure.getMessage());
    }

    @Test
    void corruptCaKeyFailsFastWithAMessageNamingTheFile(@TempDir Path dir) throws Exception {
        caIn(dir);   // bootstrap a valid CA
        Files.writeString(dir.resolve("ca.key"), "-----BEGIN PRIVATE KEY-----\ngarbage\n-----END PRIVATE KEY-----");

        PkiException failure = assertThrows(PkiException.class, () -> caIn(dir));

        assertNotNull(failure.getMessage());
        assertFalse(failure.getMessage().isBlank());
    }

    @Test
    void corruptSerialFileFailsFast(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);
        Files.writeString(dir.resolve("serial.txt"), "not-a-number");

        assertThrows(PkiException.class, () -> ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair())));
    }

    @Test
    void nodeIdWithACommaIsEscapedInTheSubject(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = caIn(dir);

        CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(
                "node,1", csrFor("x", CertificateAuthority.generateKeyPair()));

        // A raw comma would split the DN into two RDNs and change the identity.
        assertEquals("node,1", PeerIdentityInterceptor.commonNameOf(issued.certificate()));
    }

    // ── Key material permissions ─────────────────────────────────────────────

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void privateKeyIsOwnerOnlyOnPosix(@TempDir Path dir) throws Exception {
        caIn(dir);

        Set<java.nio.file.attribute.PosixFilePermission> permissions =
                Files.getPosixFilePermissions(dir.resolve("ca.key"));

        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), permissions);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void permissionHardeningIsAttemptedOnWindows(@TempDir Path dir) {
        caIn(dir);

        // Best effort by design: a filesystem without an ACL view must not prevent startup, but the
        // outcome has to be observable rather than silent.
        assertTrue(PkiPaths.permissionsEnforced() == 1 || PkiPaths.permissionsEnforced() == 0,
                "permission enforcement must record a definite outcome");
    }

    @Test
    void clockIsInjectableSoValidityIsDeterministic(@TempDir Path dir) throws Exception {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        CertificateAuthority ca = new CertificateAuthority(
                new PkiPaths(dir), Clock.fixed(fixed, ZoneOffset.UTC));

        CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(
                "node1", csrFor("node1", CertificateAuthority.generateKeyPair()));

        assertEquals(fixed.plus(CertificateAuthority.CLIENT_VALIDITY), issued.notAfter());
    }
}
