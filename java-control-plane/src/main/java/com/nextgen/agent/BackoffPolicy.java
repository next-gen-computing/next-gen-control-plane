package com.nextgen.agent;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Capped exponential backoff with jitter, used for every agent-side reconnect.
 *
 * <p>Replaces the previous fixed {@code Thread.sleep(2000)} × 10 followed by {@code System.exit(1)},
 * which had two problems: a fixed delay makes a fleet of agents retry in lockstep and hammer a
 * recovering control plane, and giving up entirely meant a transient network blip required a manual
 * restart.
 *
 * <p>Jitter is applied as a multiplier in {@code [1 - jitterFactor, 1]} on the computed delay, so
 * agents that lost their connection at the same instant do not retry at the same instant.
 *
 * <p>The delay is capped ({@code maxDelayMillis}) so a long outage settles into steady polling rather
 * than growing without bound. Exponent growth is clamped before overflow can occur.
 */
public final class BackoffPolicy {

    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double multiplier;
    private final double jitterFactor;
    private final DoubleSupplier randomSource;

    public BackoffPolicy(long initialDelayMillis, long maxDelayMillis,
                         double multiplier, double jitterFactor) {
        this(initialDelayMillis, maxDelayMillis, multiplier, jitterFactor,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param randomSource supplies values in {@code [0,1)}. Injectable so tests are deterministic.
     */
    public BackoffPolicy(long initialDelayMillis, long maxDelayMillis,
                         double multiplier, double jitterFactor, DoubleSupplier randomSource) {
        if (initialDelayMillis <= 0) {
            throw new IllegalArgumentException("initialDelayMillis must be positive");
        }
        if (maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be >= initialDelayMillis");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be within [0,1]");
        }
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        this.jitterFactor = jitterFactor;
        this.randomSource = randomSource;
    }

    /** The default policy: 1s, doubling, capped at 30s, with 20% jitter. */
    public static BackoffPolicy defaultPolicy() {
        return new BackoffPolicy(1_000L, 30_000L, 2.0, 0.2);
    }

    /**
     * Delay before the given attempt.
     *
     * @param attempt 1-based. Attempt 1 returns the initial delay (jittered).
     */
    public long delayForAttempt(int attempt) {
        int normalised = Math.max(1, attempt);

        double delay = initialDelayMillis;
        for (int i = 1; i < normalised; i++) {
            delay *= multiplier;
            // Clamp inside the loop so a very large attempt number cannot overflow to a nonsense value
            // on the way to being capped.
            if (delay >= maxDelayMillis) {
                delay = maxDelayMillis;
                break;
            }
        }
        double capped = Math.min(delay, maxDelayMillis);

        double jitterMultiplier = 1.0 - (jitterFactor * randomSource.getAsDouble());
        long jittered = Math.round(capped * jitterMultiplier);

        // Never return zero: a zero delay would turn a reconnect loop into a busy spin.
        return Math.max(1L, jittered);
    }

    public long maxDelayMillis() {
        return maxDelayMillis;
    }
}
