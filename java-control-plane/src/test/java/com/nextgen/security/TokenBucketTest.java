package com.nextgen.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TokenBucket. Entirely virtual-clock driven — there are no sleeps anywhere in this file.
 */
class TokenBucketTest {

    private static final long MINUTE = Duration.ofMinutes(1).toNanos();

    private static TokenBucket bucket(AtomicLong clock, double capacity, double refillPerMinute) {
        return new TokenBucket(capacity, refillPerMinute, MINUTE, clock::get);
    }

    @Test
    void allowsABurstUpToCapacity() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 5, 1);

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "burst token " + i + " should be granted");
        }
    }

    @Test
    void deniesWhenEmpty() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 3, 1);
        for (int i = 0; i < 3; i++) {
            bucket.tryAcquire();
        }

        assertFalse(bucket.tryAcquire());
    }

    @Test
    void refillsAtTheConfiguredRate() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 5, 1);
        for (int i = 0; i < 5; i++) {
            bucket.tryAcquire();
        }
        assertFalse(bucket.tryAcquire());

        clock.addAndGet(MINUTE);

        assertTrue(bucket.tryAcquire(), "exactly one token should be back after one period");
        assertFalse(bucket.tryAcquire(), "and no more than one");
    }

    @Test
    void doesNotExceedCapacityAfterALongIdlePeriod() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 5, 1);
        for (int i = 0; i < 5; i++) {
            bucket.tryAcquire();
        }

        clock.addAndGet(Duration.ofDays(1).toNanos());

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire());
        }
        assertFalse(bucket.tryAcquire(), "a long idle must not bank unlimited tokens");
    }

    @Test
    void partialRefillIsProportional() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 10, 10);   // 10 per minute
        for (int i = 0; i < 10; i++) {
            bucket.tryAcquire();
        }

        clock.addAndGet(MINUTE / 2);

        assertEquals(5.0, bucket.availableTokens(), 0.01);
    }

    @Test
    void isFullReportsAnIdleBucket() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 5, 5);

        assertTrue(bucket.isFull());
        bucket.tryAcquire();
        assertFalse(bucket.isFull());

        clock.addAndGet(MINUTE);
        assertTrue(bucket.isFull(), "a fully-refilled bucket carries no state worth keeping");
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void clockGoingBackwardsGrantsNoTokensAndDoesNotThrow() {
        AtomicLong clock = new AtomicLong(Duration.ofHours(1).toNanos());
        TokenBucket bucket = bucket(clock, 2, 1);
        bucket.tryAcquire();
        bucket.tryAcquire();
        assertFalse(bucket.tryAcquire());

        // An NTP step backwards. This is exactly why nanoTime is used rather than wall-clock time,
        // but the guard has to hold even so.
        clock.addAndGet(-Duration.ofMinutes(30).toNanos());

        assertDoesNotThrow(bucket::tryAcquire);
        assertFalse(bucket.tryAcquire(), "a backwards clock must not mint tokens");
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(0, 1, MINUTE, () -> 0L));
    }

    @Test
    void rejectsNonPositiveRefill() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(5, 0, MINUTE, () -> 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(5, 1, 0, () -> 0L));
    }

    @Test
    @Timeout(30)
    void concurrentAcquireGrantsExactlyCapacity() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = bucket(clock, 10, 1);

        int threads = 16;
        AtomicInteger granted = new AtomicInteger();
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
                for (int attempt = 0; attempt < 10; attempt++) {
                    if (bucket.tryAcquire()) {
                        granted.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));

        // Proves the mutual exclusion: without it, threads would read the same token count and
        // over-grant.
        assertEquals(10, granted.get());
    }
}
