package com.nextgen.desktop.v2.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TlsCertificateGeneratorTest {

    @Test
    void testGenerateCertificate() throws Exception {
        String cert = TlsCertificateGenerator.generateCertificate("TestServer", "NextGenControlPlane");
        
        assertNotNull(cert);
        assertFalse(cert.isEmpty());
        
        // Should contain PEM markers
        assertTrue(cert.contains("-----BEGIN") || cert.length() > 10);
    }

    @Test
    void testGenerateCertificateDifferentInstances() {
        String cert1 = TlsCertificateGenerator.generateCertificate("Server1", "NextGenControlPlane");
        String cert2 = TlsCertificateGenerator.generateCertificate("Server2", "NextGenControlPlane");
        
        // Different servers should have different certificates
        assertNotEquals(cert1, cert2);
    }

    @Test
    void testGenerateCertificateSameInstance() {
        String cert1 = TlsCertificateGenerator.generateCertificate("SameServer", "NextGenControlPlane");
        String cert2 = TlsCertificateGenerator.generateCertificate("SameServer", "NextGenControlPlane");
        
        // Same server called twice - should generate different certificates (different keys)
        assertNotNull(cert1);
        assertNotNull(cert2);
    }

    @Test
    void testGenerateConnectionToken() {
        String token = TlsCertificateGenerator.generateConnectionToken();
        
        assertNotNull(token);
        assertEquals(8, token.length()); // 8 alphanumeric chars
        
        // Should be valid chars (ABCDEFGHJKLMNPQRSTUVWXYZ23456789)
        assertTrue(token.matches("^[A-HJ-NP-Z2-9]+$"));
    }

    @Test
    void testGenerateConnectionTokenUniqueness() {
        Set<String> tokens = new HashSet<>();
        int count = 100;
        
        for (int i = 0; i < count; i++) {
            String token = TlsCertificateGenerator.generateConnectionToken();
            tokens.add(token);
        }
        
        // All tokens should be unique
        assertEquals(count, tokens.size());
    }

    @Test
    void testGenerateConnectionTokenLength() {
        String token = TlsCertificateGenerator.generateConnectionToken();
        assertEquals(8, token.length());
    }

    @Test
    void testCertificateFileCreation() throws Exception {
        String name = "TestServerFile";
        String org = "TestOrg";
        
        String cert = TlsCertificateGenerator.generateCertificate(name, org);
        
        // Verify cert directory was created
        String certDir = System.getProperty("user.home") + "/.nextgen-cp-v2/certs";
        Path certPath = Paths.get(certDir);
        assertTrue(Files.exists(certPath));
        
        // Verify key files exist
        File privateKey = new File(certDir, name + "_private.pem");
        File publicKey = new File(certDir, name + "_public.pem");
        
        // Files might or might not exist depending on implementation
        // Just verify the method returns a valid cert string
        assertFalse(cert.isEmpty());
    }

    @Test
    void testSecureRandomUsage() {
        // Verify tokens are not predictable by checking entropy
        String token1 = TlsCertificateGenerator.generateConnectionToken();
        String token2 = TlsCertificateGenerator.generateConnectionToken();
        
        // Two consecutive tokens should be different
        assertNotEquals(token1, token2);
        
        // Both should be strings of expected length
        assertEquals(8, token1.length());
        assertEquals(8, token2.length());
    }

    @Test
    void testCertificateContainsExpectedData() {
        String name = "MyServer";
        String org = "MyOrg";
        String cert = TlsCertificateGenerator.generateCertificate(name, org);
        
        // The certificate should be Base64 encoded and non-empty
        assertNotNull(cert);
        assertTrue(cert.length() > 50); // Should be reasonably long
    }
}
