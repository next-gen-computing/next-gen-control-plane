package com.nextgen.desktop.ui.account;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Real password hashing — PBKDF2WithHmacSHA256 via the JDK's own {@link SecretKeyFactory}, no new
 * dependency needed. A per-account random salt and a deliberately slow iteration count mean a stolen
 * {@code accounts.json} still can't be turned back into a password by anyone without doing the same
 * expensive work per guess this class does per check.
 */
final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private PasswordHasher() {
    }

    static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    static String hash(char[] password, String base64Salt) {
        byte[] salt = Base64.getDecoder().decode(base64Salt);
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // PBKDF2WithHmacSHA256 is a standard algorithm present in every JDK 8+ — this can only
            // fail if the JVM itself is misconfigured, not a runtime condition callers can recover
            // from.
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    static boolean matches(char[] password, String base64Salt, String expectedBase64Hash) {
        String actual = hash(password, base64Salt);
        return constantTimeEquals(actual, expectedBase64Hash);
    }

    /** Avoids leaking hash-comparison timing as a side channel — a plain {@code String.equals} returns
     * as soon as it finds the first differing character, which a patient attacker can measure. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
