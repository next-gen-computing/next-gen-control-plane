package com.nextgen.controlplane.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real AES-256-GCM throughout — no mocking of the crypto. */
class SecretStoreTest {

    @Test
    void putThenGetRoundTripsTheExactPlaintext(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir);
        byte[] plaintext = "super-secret-password".getBytes(StandardCharsets.UTF_8);

        store.put("db-password", plaintext);
        Optional<byte[]> read = store.get("db-password");

        assertTrue(read.isPresent());
        assertArrayEquals(plaintext, read.get());
    }

    @Test
    void getForAnUnknownNameIsEmpty(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir);
        assertTrue(store.get("never-set").isEmpty());
    }

    @Test
    void theOnDiskFileIsNotThePlaintext(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir);
        byte[] plaintext = "a-very-recognisable-plaintext-marker".getBytes(StandardCharsets.UTF_8);

        store.put("marker", plaintext);

        Path file = dir.resolve("secrets").resolve("marker.enc");
        assertTrue(Files.exists(file));
        byte[] onDisk = Files.readAllBytes(file);
        String onDiskText = new String(onDisk, StandardCharsets.ISO_8859_1);
        assertFalse(onDiskText.contains("a-very-recognisable-plaintext-marker"),
                "the raw plaintext must never appear in the encrypted file on disk");
    }

    @Test
    void puttingTheSameNameTwiceOverwritesTheOldValue(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir);
        store.put("rotating", "old-value".getBytes(StandardCharsets.UTF_8));
        store.put("rotating", "new-value".getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> read = store.get("rotating");
        assertEquals("new-value", new String(read.orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void aCorruptedCiphertextFailsTheGcmTagCheckRatherThanReturningGarbage(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir);
        store.put("tampered", "original".getBytes(StandardCharsets.UTF_8));

        Path file = dir.resolve("secrets").resolve("tampered.enc");
        byte[] onDisk = Files.readAllBytes(file);
        onDisk[onDisk.length - 1] ^= 0x01; // flip one bit in the GCM tag/ciphertext tail
        Files.write(file, onDisk);

        assertThrows(GeneralSecurityException.class, () -> store.get("tampered"));
    }

    @Test
    void aNameWithPathUnsafeCharactersCannotEscapeTheStorageDirectory(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore(dir);
        store.put("../../etc/passwd", "irrelevant".getBytes(StandardCharsets.UTF_8));

        assertFalse(Files.exists(dir.resolve("etc").resolve("passwd")),
                "a maliciously-named secret must never write outside the storage directory");
        // It's still retrievable under its own sanitized name — just confirms the write actually happened.
        assertTrue(store.get("../../etc/passwd").isPresent());
    }

    @Test
    void reusesTheSameKeyAcrossSeparateStoreInstancesPointedAtTheSameDataDir(@TempDir Path dir) throws Exception {
        new SecretStore(dir).put("persisted", "value".getBytes(StandardCharsets.UTF_8));

        // A fresh instance (simulating a process restart) must still be able to decrypt what an earlier
        // instance encrypted — proves the key is actually persisted and reloaded, not regenerated.
        SecretStore reopened = new SecretStore(dir);
        Optional<byte[]> read = reopened.get("persisted");
        assertTrue(read.isPresent());
        assertEquals("value", new String(read.get(), StandardCharsets.UTF_8));
    }
}
