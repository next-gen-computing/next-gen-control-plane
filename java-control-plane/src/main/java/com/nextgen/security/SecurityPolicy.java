package com.nextgen.security;

import com.nextgen.controlplane.EnvConfig;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Environment-driven security configuration.
 *
 * <p>{@code TLS_ENABLED} defaults to false so existing deployments and the in-process test suite
 * keep working unchanged. When it is false the interceptors are still installed but run in
 * <b>permissive mode</b>: they record what they would have rejected in
 * {@code controlplane_mtls_would_deny_total} without actually rejecting it, which makes it possible
 * to see whether a cluster is ready to enforce mTLS before flipping the switch.
 *
 * @param tlsEnabled         require TLS on the wire and enforce certificate policy
 * @param enrollmentEnabled  accept new enrolments. Turning this off after provisioning closes the
 *                           only unauthenticated entry point without needing a second port.
 * @param sanHosts           names the server certificate is valid for
 * @param enrollmentTokenTtl how long a minted enrolment token remains usable
 */
public record SecurityPolicy(boolean tlsEnabled,
                             boolean enrollmentEnabled,
                             List<String> sanHosts,
                             Duration enrollmentTokenTtl,
                             RateLimitPolicy rateLimit) {

    public static SecurityPolicy fromEnvironment() {
        String rawSans = EnvConfig.stringValue("TLS_SAN_HOSTS", "localhost,127.0.0.1");
        List<String> sans = Arrays.stream(rawSans.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new SecurityPolicy(
                EnvConfig.booleanValue("TLS_ENABLED", false),
                EnvConfig.booleanValue("ENROLLMENT_ENABLED", true),
                sans,
                Duration.ofMinutes(EnvConfig.longValue("ENROLLMENT_TOKEN_TTL_MINUTES", 60)),
                RateLimitPolicy.fromEnvironment());
    }

    /** A permissive policy for tests and local development. */
    public static SecurityPolicy permissive() {
        return new SecurityPolicy(false, true, List.of("localhost", "127.0.0.1"),
                Duration.ofMinutes(60), RateLimitPolicy.defaults());
    }

    /** True when policy violations should be rejected rather than merely counted. */
    public boolean enforcing() {
        return tlsEnabled;
    }
}
