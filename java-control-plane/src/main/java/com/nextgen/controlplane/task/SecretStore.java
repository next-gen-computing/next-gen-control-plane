package com.nextgen.controlplane.task;

import com.nextgen.security.PkiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Operator-set secrets, encrypted at rest under {@code <dataDir>/secrets/} with a server-local
 * AES-256-GCM key — not a per-node envelope scheme (nothing like that exists anywhere in this
 * codebase to build on, and inventing one would be substantial new crypto plumbing a single-trusted-
 * operator system doesn't need). This matches how Docker Swarm itself actually secures secrets at
 * rest — a cluster-wide key, not a per-node key — the standard approach, not a corner cut.
 *
 * <p>The key file ({@code secret.key}) is written with the exact same owner-only-permission discipline
 * {@link PkiPaths#writeSecret} already uses for CA/node private key material — this class reuses that
 * utility rather than re-implementing file-permission hardening.
 *
 * <p>Plaintext only ever exists in memory, for as long as a caller holds the returned {@code byte[]} —
 * {@link #get} decrypts fresh on every call rather than caching. Ciphertext on disk is
 * {@code IV (12 bytes) || GCM ciphertext+tag} — a single file per secret, named after a sanitized
 * version of its operator-given name so an adversarial name can't escape {@code storageDir}.
 */
public final class SecretStore {
    private static final Logger LOG = LoggerFactory.getLogger(SecretStore.class);

    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int AES_KEY_BITS = 256;

    private final Path storageDir;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public SecretStore(Path dataDir) {
        this.storageDir = dataDir.resolve("secrets");
        this.keyFile = storageDir.resolve("secret.key");
    }

    /** Encrypts {@code plaintext} and writes it to this secret's file, overwriting any existing value.
     * The plaintext byte array is never retained by this method past the call. */
    public void put(String name, byte[] plaintext) throws GeneralSecurityException, IOException {
        SecretKey secretKey = loadOrCreateKey();
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] onDisk = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, onDisk, 0, iv.length);
        System.arraycopy(ciphertext, 0, onDisk, iv.length, ciphertext.length);

        Files.createDirectories(storageDir);
        PkiPaths.restrictToOwner(storageDir);
        Path file = pathFor(name);
        PkiPaths.writeSecret(file, onDisk);
        LOG.info("🔒 Stored secret '{}' ({} bytes plaintext)", name, plaintext.length);
    }

    /** @return the decrypted plaintext, or empty if no secret with this name was ever set. A corrupted
     * or tampered ciphertext fails the GCM tag check and surfaces as a {@link GeneralSecurityException}
     * — never silently returns garbage plaintext. */
    public Optional<byte[]> get(String name) throws GeneralSecurityException, IOException {
        Path file = pathFor(name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        SecretKey secretKey = loadOrCreateKey();
        byte[] onDisk = Files.readAllBytes(file);
        if (onDisk.length <= GCM_IV_BYTES) {
            throw new GeneralSecurityException("stored secret '" + name + "' is too short to contain a real IV+ciphertext");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(onDisk, 0, iv, 0, GCM_IV_BYTES);
        byte[] ciphertext = new byte[onDisk.length - GCM_IV_BYTES];
        System.arraycopy(onDisk, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return Optional.of(cipher.doFinal(ciphertext));
    }

    private Path pathFor(String name) {
        return storageDir.resolve(sanitize(name) + ".enc");
    }

    /** Mirrors {@code DockerComposeServiceExecutor.sanitizeContainerName}'s discipline: an operator-
     * supplied name could contain anything, so every character outside a safe set is replaced rather
     * than trusted as a path segment. */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    private SecretKey loadOrCreateKey() throws GeneralSecurityException, IOException {
        SecretKey existing = key;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (key != null) {
                return key;
            }
            Files.createDirectories(storageDir);
            PkiPaths.restrictToOwner(storageDir);
            if (Files.exists(keyFile)) {
                byte[] raw = Files.readAllBytes(keyFile);
                key = new SecretKeySpec(raw, "AES");
            } else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(AES_KEY_BITS, random);
                SecretKey generated = generator.generateKey();
                PkiPaths.writeSecret(keyFile, generated.getEncoded());
                key = generated;
                LOG.info("🔑 Generated a new secret-encryption key at {}", keyFile);
            }
            return key;
        }
    }
}
