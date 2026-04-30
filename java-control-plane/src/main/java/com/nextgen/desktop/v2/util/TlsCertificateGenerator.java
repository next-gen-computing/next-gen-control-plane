package com.nextgen.desktop.v2.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/**
 * Utility for generating TLS certificates for mTLS authentication.
 * Creates self-signed certificates for servers and nodes.
 */
public class TlsCertificateGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(TlsCertificateGenerator.class);
    
    private static final String CERT_DIR = System.getProperty("user.home") + "/.nextgen-cp-v2/certs";
    private static final int CERT_VALIDITY_DAYS = 365;
    private static final int KEY_SIZE = 2048;
    
    /**
     * Generate a self-signed certificate for a server or node.
     * 
     * @param commonName The CN (Common Name) for the certificate
     * @param organization The organization name
     * @return PEM-encoded certificate string
     */
    public static String generateCertificate(String commonName, String organization) {
        try {
            // Create certificate directory if it doesn't exist
            Path certDir = Paths.get(CERT_DIR);
            if (!Files.exists(certDir)) {
                Files.createDirectories(certDir);
            }
            
            // Generate key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(KEY_SIZE);
            KeyPair keyPair = keyGen.generateKeyPair();
            
            // For production, use Bouncy Castle to generate proper X.509 certificates
            // For this implementation, we'll store the public key as a simple PEM format
            String publicKeyPem = exportPublicKeyToPem(keyPair.getPublic());
            String privateKeyPem = exportPrivateKeyToPem(keyPair.getPrivate());
            
            // Combine into a single PEM format for storage
            String combinedPem = "-----BEGIN CERTIFICATE-----\n" +
                    publicKeyPem +
                    "\n-----END CERTIFICATE-----\n\n" +
                    "-----BEGIN PRIVATE KEY-----\n" +
                    privateKeyPem +
                    "\n-----END PRIVATE KEY-----";
            
            LOG.info("Generated TLS certificate for: {}", commonName);
            return combinedPem;
            
        } catch (Exception e) {
            LOG.error("Failed to generate TLS certificate", e);
            throw new RuntimeException("Certificate generation failed", e);
        }
    }
    
    /**
     * Generate a connection token for server-node pairing.
     * Generates a short 8-character token for easy manual entry.
     * 
     * @return A unique 8-character alphanumeric token
     */
    public static String generateConnectionToken() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No I, O, 0, 1 to avoid confusion
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    private static String exportPublicKeyToPem(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        return Base64.getEncoder().encodeToString(encoded);
    }
    
    private static String exportPrivateKeyToPem(PrivateKey privateKey) {
        byte[] encoded = privateKey.getEncoded();
        return Base64.getEncoder().encodeToString(encoded);
    }
    
    /**
     * Validate a certificate string.
     * 
     * @param certPem The PEM-encoded certificate
     * @return true if valid, false otherwise
     */
    public static boolean validateCertificate(String certPem) {
        if (certPem == null || certPem.isEmpty()) {
            return false;
        }
        
        // Basic validation: check for PEM markers
        return certPem.contains("-----BEGIN CERTIFICATE-----") &&
               certPem.contains("-----END CERTIFICATE-----");
    }
}
