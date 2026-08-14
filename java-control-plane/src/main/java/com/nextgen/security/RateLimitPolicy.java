package com.nextgen.security;

import com.nextgen.controlplane.EnvConfig;

import java.time.Duration;

/**
 * Rate-limit settings for the enrolment endpoint.
 *
 * <p>Defaults allow a 20-node cluster to bootstrap simultaneously (well under the global capacity of
 * 50) while capping the sustained rate hard enough that token guessing is hopeless.
 *
 * @param perIpCapacity  burst allowed from one source address
 * @param perIpRefill    tokens restored per {@code refillPeriod} per source
 * @param globalCapacity burst allowed across the whole control plane
 * @param globalRefill   tokens restored per {@code refillPeriod} globally
 * @param maxTrackedKeys hard cap on distinct source buckets held in memory
 */
public record RateLimitPolicy(double perIpCapacity, double perIpRefill,
                              double globalCapacity, double globalRefill,
                              Duration refillPeriod, int maxTrackedKeys) {

    public static RateLimitPolicy defaults() {
        return new RateLimitPolicy(5, 1, 50, 10, Duration.ofMinutes(1), 10_000);
    }

    public static RateLimitPolicy fromEnvironment() {
        return new RateLimitPolicy(
                EnvConfig.doubleValue("ENROLL_RATE_PER_IP_BURST", 5),
                EnvConfig.doubleValue("ENROLL_RATE_PER_IP_REFILL", 1),
                EnvConfig.doubleValue("ENROLL_RATE_GLOBAL_BURST", 50),
                EnvConfig.doubleValue("ENROLL_RATE_GLOBAL_REFILL", 10),
                Duration.ofSeconds(EnvConfig.longValue("ENROLL_RATE_PERIOD_SECONDS", 60)),
                EnvConfig.intValue("ENROLL_RATE_MAX_KEYS", 10_000));
    }
}
