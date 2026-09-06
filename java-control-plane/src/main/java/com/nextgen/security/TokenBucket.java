package com.nextgen.security;

import java.util.function.LongSupplier;

/**
 * A token bucket rate limiter.
 *
 * <p>Chosen over a fixed or sliding window because enrolment is a once-per-node-lifetime event with a
 * legitimate synchronised burst — a 20-node cluster bootstrapping at once. A token bucket admits the
 * burst while capping the sustained rate; a fixed window either rejects the bootstrap or permits
 * twice the intended rate across a window boundary.
 *
 * <p>The clock is a {@link LongSupplier} of <b>nanoseconds</b> ({@code System::nanoTime}), not a
 * {@link java.time.Clock}. Wall-clock time is not monotonic: an NTP step backwards would freeze the
 * bucket and a step forwards would grant free tokens.
 */
public final class TokenBucket {

    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;

    private double tokens;
    private long lastRefillNanos;

    /**
     * @param capacity   maximum burst size
     * @param refillCount tokens added per {@code refillPeriodNanos}
     * @param refillPeriodNanos the refill period
     */
    public TokenBucket(double capacity, double refillCount, long refillPeriodNanos,
                       LongSupplier nanoClock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillCount <= 0 || refillPeriodNanos <= 0) {
            throw new IllegalArgumentException("refill rate must be positive");
        }
        this.capacity = capacity;
        this.tokensPerNano = refillCount / (double) refillPeriodNanos;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    /** Attempts to consume one token. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Current token count, for tests and for the "is this bucket idle" sweep. */
    public synchronized double availableTokens() {
        refill();
        return tokens;
    }

    /** True when the bucket is full, i.e. it holds no state worth keeping. */
    public synchronized boolean isFull() {
        refill();
        return tokens >= capacity;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        // elapsed > 0 guards a backwards clock: it must neither grant tokens nor push lastRefill
        // forward, both of which would corrupt the rate.
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefillNanos = now;
        }
    }
}
