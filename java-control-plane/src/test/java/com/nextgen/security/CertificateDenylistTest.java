package com.nextgen.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the revocation denylist, driven by an injected nanosecond clock so the poll interval is
 * exercised without sleeping.
 */
class CertificateDenylistTest {

    private static final Duration RELOAD = Duration.ofSeconds(5);

    private record Fixture(CertificateDenylist denylist, AtomicLong clock, Path file) {
    }

    private static Fixture fixture(Path dir) {
        AtomicLong clock = new AtomicLong(0);
        Path file = dir.resolve("revoked.txt");
        return new Fixture(new CertificateDenylist(file, RELOAD, clock::get), clock, file);
    }

    /** Advances past the poll interval and forces the reload check. */
    private static void advanceAndReload(Fixture f) {
        f.clock().addAndGet(RELOAD.toNanos() + 1);
        f.denylist().maybeReload();
    }

    @Test
    void unknownSerialIsAllowed(@TempDir Path dir) {
        assertFalse(fixture(dir).denylist().isRevoked(BigInteger.valueOf(42)));
    }

    @Test
    void revokedSerialIsDenied(@TempDir Path dir) {
        Fixture f = fixture(dir);

        f.denylist().revoke(BigInteger.valueOf(42), Instant.now().plusSeconds(600), "compromised");

        assertTrue(f.denylist().isRevoked(BigInteger.valueOf(42)));
    }

    @Test
    void revocationTakesEffectImmediatelyWithoutWaitingForThePoll(@TempDir Path dir) {
        Fixture f = fixture(dir);

        f.denylist().revoke(BigInteger.valueOf(7), Instant.now(), "leaked");

        // The clock has not moved, so no reload has happened — the in-memory set must have been
        // updated directly. Otherwise a revocation would take up to the poll interval to bite.
        assertTrue(f.denylist().isRevoked(BigInteger.valueOf(7)));
    }

    @Test
    void revocationIsPersisted(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.denylist().revoke(BigInteger.valueOf(255), Instant.ofEpochMilli(1_000), "test");

        String contents = Files.readString(f.file());

        assertTrue(contents.contains("ff"), "the serial should be written in hex: " + contents);
        assertTrue(contents.contains("test"));
    }

    @Test
    void externalEditIsPickedUpAfterTheReloadInterval(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        assertFalse(f.denylist().isRevoked(BigInteger.valueOf(0xAB)));

        // Another process (an operator, or a second control plane) appends to the file.
        Files.writeString(f.file(), "ab 0 0 revoked-by-operator\n");
        advanceAndReload(f);

        assertTrue(f.denylist().isRevoked(BigInteger.valueOf(0xAB)));
    }

    @Test
    void unchangedFileIsNotRepeatedlyReparsed(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Files.writeString(f.file(), "01 0 0 x\n");
        advanceAndReload(f);
        assertEquals(1, f.denylist().size());

        advanceAndReload(f);

        assertEquals(1, f.denylist().size());
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void missingFileMeansAnEmptyDenylistNotAnError(@TempDir Path dir) {
        Fixture f = fixture(dir);

        assertDoesNotThrow(() -> f.denylist().isRevoked(BigInteger.ONE));
        assertEquals(0, f.denylist().size());
    }

    @Test
    void malformedLineIsSkippedNotFatal(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Files.writeString(f.file(), """
                zzzz-not-hex 0 0 broken
                0a 0 0 good
                """);

        advanceAndReload(f);

        // One bad line must not disable revocation for everything else.
        assertTrue(f.denylist().isRevoked(BigInteger.valueOf(10)));
        assertEquals(1, f.denylist().size());
    }

    @Test
    void blankAndCommentLinesAreIgnored(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Files.writeString(f.file(), """
                # revoked because of the 2026 incident

                0b 0 0 reason
                """);

        advanceAndReload(f);

        assertEquals(1, f.denylist().size());
        assertTrue(f.denylist().isRevoked(BigInteger.valueOf(11)));
    }

    @Test
    void nullSerialIsNeverConsideredRevoked(@TempDir Path dir) {
        assertFalse(fixture(dir).denylist().isRevoked(null));
    }

    @Test
    void revokingNullIsANoOp(@TempDir Path dir) {
        Fixture f = fixture(dir);

        assertDoesNotThrow(() -> f.denylist().revoke(null, Instant.now(), "x"));
        assertEquals(0, f.denylist().size());
    }

    @Test
    void reloadDoesNotHappenBeforeTheIntervalElapses(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.denylist().isRevoked(BigInteger.ONE);   // triggers the initial load

        Files.writeString(f.file(), "0c 0 0 late\n");
        f.clock().addAndGet(RELOAD.toNanos() / 2);

        assertFalse(f.denylist().isRevoked(BigInteger.valueOf(12)),
                "the poll interval should not have elapsed yet");
    }
}
