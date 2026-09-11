package com.nextgen.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BackoffPolicy. Jitter is injected, so every assertion here is deterministic.
 */
class BackoffPolicyTest {

    /** No jitter: delays are exactly the geometric series, capped. */
    private static BackoffPolicy exact(long initial, long max, double multiplier) {
        return new BackoffPolicy(initial, max, multiplier, 0.0, () -> 0.0);
    }

    @Test
    void firstAttemptUsesTheInitialDelay() {
        assertEquals(1_000L, exact(1_000L, 30_000L, 2.0).delayForAttempt(1));
    }

    @Test
    void delayGrowsGeometrically() {
        BackoffPolicy policy = exact(1_000L, 60_000L, 2.0);

        assertEquals(1_000L, policy.delayForAttempt(1));
        assertEquals(2_000L, policy.delayForAttempt(2));
        assertEquals(4_000L, policy.delayForAttempt(3));
        assertEquals(8_000L, policy.delayForAttempt(4));
    }

    @Test
    void delayIsCappedAtTheMaximum() {
        BackoffPolicy policy = exact(1_000L, 30_000L, 2.0);

        assertEquals(30_000L, policy.delayForAttempt(20));
        assertEquals(30_000L, policy.delayForAttempt(1_000));
    }

    @Test
    void veryLargeAttemptNumbersDoNotOverflowIntoANonsenseDelay() {
        BackoffPolicy policy = exact(1_000L, 30_000L, 2.0);

        long delay = policy.delayForAttempt(Integer.MAX_VALUE);

        assertEquals(30_000L, delay);
        assertTrue(delay > 0, "an overflowed delay could become negative and busy-spin");
    }

    @Test
    void jitterReducesTheDelayWithinTheConfiguredBand() {
        // randomSource returns 1.0 -> maximum jitter subtracted: 20% off 10s.
        BackoffPolicy policy = new BackoffPolicy(10_000L, 30_000L, 2.0, 0.2, () -> 1.0);

        assertEquals(8_000L, policy.delayForAttempt(1));
    }

    @Test
    void jitterNeverIncreasesTheDelayBeyondTheCap() {
        BackoffPolicy policy = new BackoffPolicy(1_000L, 30_000L, 2.0, 0.5, () -> 0.0);

        assertTrue(policy.delayForAttempt(100) <= 30_000L);
    }

    @Test
    void delayIsNeverZero() {
        // Full jitter on a tiny base would round to 0 and turn the reconnect loop into a busy spin.
        BackoffPolicy policy = new BackoffPolicy(1L, 10L, 2.0, 1.0, () -> 1.0);

        assertTrue(policy.delayForAttempt(1) >= 1L);
    }

    @Test
    void attemptZeroOrNegativeIsTreatedAsTheFirstAttempt() {
        BackoffPolicy policy = exact(1_000L, 30_000L, 2.0);

        assertEquals(1_000L, policy.delayForAttempt(0));
        assertEquals(1_000L, policy.delayForAttempt(-5));
    }

    @Test
    void defaultPolicyIsCappedAt30Seconds() {
        assertEquals(30_000L, BackoffPolicy.defaultPolicy().maxDelayMillis());
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void rejectsNonPositiveInitialDelay() {
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(0L, 10L, 2.0, 0.0));
    }

    @Test
    void rejectsMaxBelowInitial() {
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(10_000L, 1_000L, 2.0, 0.0));
    }

    @Test
    void rejectsMultiplierBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(1_000L, 10_000L, 0.5, 0.0));
    }

    @Test
    void rejectsJitterOutsideUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(1_000L, 10_000L, 2.0, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(1_000L, 10_000L, 2.0, -0.1));
    }
}
