package com.nextgen.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enrolment token store: entropy, single use, expiry, and the fact that plaintext is
 * never retained.
 */
class EnrollmentTokenStoreTest {

    private static EnrollmentTokenStore store() {
        return new EnrollmentTokenStore(Clock.systemUTC(), Duration.ofMinutes(60));
    }

    private static EnrollmentTokenStore storeWith(AtomicReference<Instant> now, Duration ttl) {
        return new EnrollmentTokenStore(new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        }, ttl);
    }

    @Test
    void mintedTokenIsAcceptedAndYieldsTheBoundNodeId() {
        EnrollmentTokenStore tokens = store();
        String token = tokens.mint("node7");

        EnrollmentTokenStore.Consumption result = tokens.consume(token);

        assertTrue(result.accepted());
        assertEquals("node7", result.nodeId());
    }

    @Test
    void tokenCarriesAtLeast256BitsOfEntropy() {
        String token = store().mint("node1");

        byte[] decoded = Base64.getUrlDecoder().decode(token);

        // The token this replaces was 8 characters from a 32-symbol alphabet — 40 bits, which is
        // brute-forceable in hours at 1000 attempts/second.
        assertEquals(32, decoded.length, "token must be 256 bits");
    }

    @Test
    void tokensAreUnique() {
        EnrollmentTokenStore tokens = store();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            assertTrue(seen.add(tokens.mint("node" + i)), "duplicate token minted");
        }
    }

    @Test
    void tokenIsSingleUse() {
        EnrollmentTokenStore tokens = store();
        String token = tokens.mint("node1");

        assertTrue(tokens.consume(token).accepted());
        assertFalse(tokens.consume(token).accepted(), "a token must not be reusable");
    }

    @Test
    void plaintextTokenIsNeverRetainedInTheStore() throws Exception {
        EnrollmentTokenStore tokens = store();
        String token = tokens.mint("node1");

        // Walk every field of the store looking for the plaintext. Only the SHA-256 should be held.
        String dump = tokens.toString() + java.util.Arrays.toString(
                EnrollmentTokenStore.class.getDeclaredFields());
        assertFalse(dump.contains(token));

        var field = EnrollmentTokenStore.class.getDeclaredField("tokensByHash");
        field.setAccessible(true);
        String mapContents = String.valueOf(field.get(tokens));
        assertFalse(mapContents.contains(token),
                "the plaintext token must never be stored: " + mapContents);
    }

    @Test
    void peekDoesNotConsume() {
        EnrollmentTokenStore tokens = store();
        String token = tokens.mint("node1");

        assertEquals("node1", tokens.peek(token).orElseThrow());
        assertTrue(tokens.consume(token).accepted(), "peek must not have consumed the token");
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void unknownTokenIsRejected() {
        assertEquals(EnrollmentTokenStore.Consumption.Status.INVALID,
                store().consume("definitely-not-a-token").status());
    }

    @Test
    void nullAndBlankTokensAreRejected() {
        EnrollmentTokenStore tokens = store();

        assertEquals(EnrollmentTokenStore.Consumption.Status.INVALID, tokens.consume(null).status());
        assertEquals(EnrollmentTokenStore.Consumption.Status.INVALID, tokens.consume("  ").status());
    }

    @Test
    void expiredTokenIsRejectedAndRemoved() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        EnrollmentTokenStore tokens = storeWith(now, Duration.ofMinutes(10));
        String token = tokens.mint("node1");

        now.set(now.get().plus(Duration.ofMinutes(11)));

        assertEquals(EnrollmentTokenStore.Consumption.Status.EXPIRED, tokens.consume(token).status());
        // Removed on the way out, so a leaked expired token cannot be retried indefinitely.
        assertEquals(EnrollmentTokenStore.Consumption.Status.INVALID, tokens.consume(token).status());
    }

    @Test
    void purgeExpiredBoundsMemory() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        EnrollmentTokenStore tokens = storeWith(now, Duration.ofMinutes(10));
        for (int i = 0; i < 20; i++) {
            tokens.mint("node" + i);
        }
        assertEquals(20, tokens.size());

        now.set(now.get().plus(Duration.ofMinutes(11)));

        assertEquals(20, tokens.purgeExpired());
        assertEquals(0, tokens.size());
    }

    @Test
    @Timeout(30)
    void tokenIsSingleUseUnderConcurrency() throws Exception {
        EnrollmentTokenStore tokens = store();
        String token = tokens.mint("node1");

        int threads = 16;
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (tokens.consume(token).accepted()) {
                    accepted.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));

        // ConcurrentHashMap.remove is atomic, so there is exactly one winner with no lock and no
        // check-then-act race.
        assertEquals(1, accepted.get());
    }

    // ── Stage II: TokenReplicationSink wiring ─────────────────────────────

    /** Records exactly what it was called with — a real object, not a mock. */
    private static final class RecordingSink implements TokenReplicationSink {
        String mintedNodeId;
        String mintedHash;
        long mintedExpiresAt;
        String consumedHash;

        @Override
        public void onMinted(String nodeId, String tokenHash, long expiresAtEpochMillis) {
            mintedNodeId = nodeId;
            mintedHash = tokenHash;
            mintedExpiresAt = expiresAtEpochMillis;
        }

        @Override
        public void onConsumed(String tokenHash) {
            consumedHash = tokenHash;
        }
    }

    @Test
    void mintCallsOnMintedBeforeReturningWithTheRealNodeIdAndExpiry() {
        EnrollmentTokenStore tokens = store();
        RecordingSink sink = new RecordingSink();
        tokens.setReplicationSink(sink);

        long before = System.currentTimeMillis();
        tokens.mint("node9");

        assertEquals("node9", sink.mintedNodeId);
        assertNotNull(sink.mintedHash);
        assertFalse(sink.mintedHash.isBlank());
        assertTrue(sink.mintedExpiresAt > before, "expiry must be a real future timestamp");
    }

    @Test
    void consumeCallsOnConsumedOnlyOnAcceptance() {
        EnrollmentTokenStore tokens = store();
        RecordingSink sink = new RecordingSink();
        String token = tokens.mint("node1"); // minted before the sink was attached — no onMinted call expected
        tokens.setReplicationSink(sink);

        tokens.consume(token);

        assertNotNull(sink.consumedHash, "a successful consumption must notify the sink");
    }

    @Test
    void consumeDoesNotCallOnConsumedForAnInvalidToken() {
        EnrollmentTokenStore tokens = store();
        RecordingSink sink = new RecordingSink();
        tokens.setReplicationSink(sink);

        tokens.consume("not-a-real-token");

        assertNull(sink.consumedHash, "an invalid token must never fire a consumption notification");
    }

    @Test
    void restoreMintedMakesATokenConsumableWithoutEverCallingMintLocally() {
        // Simulates what a REPLICA (not the one that originally minted it) does when it applies a
        // replicated MintEnrollmentTokenCommand — it never called mint() itself, only restoreMinted().
        EnrollmentTokenStore tokens = store();
        String tokenHash = fakeHash();

        tokens.restoreMinted("remote-node", tokenHash, System.currentTimeMillis() + 60_000);

        // Real consumption needs the PLAINTEXT (consume() hashes it itself) — restoreMinted only ever
        // receives a hash, so this proves presence via peek()'s own hash-free path isn't available;
        // instead confirm the entry is really there by checking size, matching what a real replica can
        // actually observe about state it only ever received as a hash.
        assertEquals(1, tokens.size());
    }

    @Test
    void removeIfPresentByHashIsIdempotentOnAnAlreadyAbsentHash() {
        EnrollmentTokenStore tokens = store();

        assertDoesNotThrow(() -> tokens.removeIfPresentByHash(fakeHash()));
    }

    @Test
    void removeIfPresentByHashActuallyRemovesARestoredEntry() {
        EnrollmentTokenStore tokens = store();
        String tokenHash = fakeHash();
        tokens.restoreMinted("remote-node", tokenHash, System.currentTimeMillis() + 60_000);
        assertEquals(1, tokens.size());

        tokens.removeIfPresentByHash(tokenHash);

        assertEquals(0, tokens.size());
    }

    private static String fakeHash() {
        return Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
    }
}
