package com.nextgen.desktop.ui.client;

import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.PkiPaths;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the mutual-TLS connection path added to {@link GrpcConnectionManager}.
 *
 * <p>Before this, the manager could only ever build a plaintext channel — even a node holding a
 * perfectly valid certificate had no way to actually use it from the desktop app. These tests don't
 * need a live server: building a {@code ManagedChannel} doesn't connect eagerly, so what matters is
 * that a real certificate produces a valid TLS context and that the manager's own state
 * ({@code isControlPlaneSecure}) tracks which kind of connection is active.
 */
class GrpcConnectionManagerTest {

    private static X509Certificate issueTestCertificate(Path dir, CertificateAuthority ca,
                                                         KeyPair keys) throws Exception {
        var csr = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=test"), keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate()));
        return ca.issueClientCertificate("test-node", csr).certificate();
    }

    @Test
    void startsWithoutAnySecureConnection() {
        GrpcConnectionManager manager = new GrpcConnectionManager();

        assertFalse(manager.isControlPlaneSecure());
    }

    @Test
    void connectSecurelyMarksTheConnectionAsSecure(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = new CertificateAuthority(new PkiPaths(dir), Clock.systemUTC());
        KeyPair keys = CertificateAuthority.generateKeyPair();
        X509Certificate certificate = issueTestCertificate(dir, ca, keys);

        GrpcConnectionManager manager = new GrpcConnectionManager();
        try {
            manager.connectSecurely("localhost", 50051, 50052,
                    ca.caCertificatePem(), certificate, keys.getPrivate());

            assertTrue(manager.isControlPlaneSecure());
            assertNotNull(manager.getControlPlaneClient());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void plaintextConnectClearsAnyPriorSecureState(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = new CertificateAuthority(new PkiPaths(dir), Clock.systemUTC());
        KeyPair keys = CertificateAuthority.generateKeyPair();
        X509Certificate certificate = issueTestCertificate(dir, ca, keys);

        GrpcConnectionManager manager = new GrpcConnectionManager();
        try {
            manager.connectSecurely("localhost", 50051, 50052,
                    ca.caCertificatePem(), certificate, keys.getPrivate());
            assertTrue(manager.isControlPlaneSecure());

            // Falling back to plaintext (e.g. the user reconnects without a token) must not leave a
            // stale "secure" flag from the previous connection.
            manager.connectTo("localhost", 50051, 50052);

            assertFalse(manager.isControlPlaneSecure());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void shutdownClearsTheSecureFlag(@TempDir Path dir) throws Exception {
        CertificateAuthority ca = new CertificateAuthority(new PkiPaths(dir), Clock.systemUTC());
        KeyPair keys = CertificateAuthority.generateKeyPair();
        X509Certificate certificate = issueTestCertificate(dir, ca, keys);

        GrpcConnectionManager manager = new GrpcConnectionManager();
        manager.connectSecurely("localhost", 50051, 50052,
                ca.caCertificatePem(), certificate, keys.getPrivate());

        manager.shutdown();

        assertFalse(manager.isControlPlaneSecure());
    }

    @Test
    void malformedCertificateMaterialFailsWithAClearExceptionRatherThanBuildingABrokenChannel() {
        GrpcConnectionManager manager = new GrpcConnectionManager();

        assertThrows(Exception.class, () -> manager.connectSecurely(
                "localhost", 50051, 50052, "not a real certificate", null, null));
    }
}
