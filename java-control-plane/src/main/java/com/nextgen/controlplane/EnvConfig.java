package com.nextgen.controlplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Function;

/**
 * Reads configuration from environment variables, extending the existing
 * {@code ROLE} / {@code NODE_ID} / {@code CONTROL_PLANE_HOST} pattern.
 *
 * <p>Every port, host, interval and feature flag in the system resolves through here so that nothing
 * is hardcoded at a call site. A malformed value is logged and falls back to the documented default
 * rather than failing startup — an unparseable port should not take a cluster down, but it must not
 * pass silently either.
 */
public final class EnvConfig {
    private static final Logger LOG = LoggerFactory.getLogger(EnvConfig.class);

    private EnvConfig() {
    }

    /**
     * Raw lookup with a default. Blank values are treated as absent.
     *
     * <p>A JVM system property of the same name takes precedence over the environment variable, so a
     * single setting can be overridden per-process (notably from a test's {@code -D} flag) without a
     * parallel configuration mechanism.
     */
    public static String stringValue(String name, String defaultValue) {
        String raw = lookup(name);
        return raw == null ? defaultValue : raw;
    }

    /** System property first, then environment variable; null when neither is set or both blank. */
    private static String lookup(String name) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(name);
        return (env == null || env.isBlank()) ? null : env.trim();
    }

    public static int intValue(String name, int defaultValue) {
        return parse(name, defaultValue, Integer::parseInt);
    }

    public static long longValue(String name, long defaultValue) {
        return parse(name, defaultValue, Long::parseLong);
    }

    public static double doubleValue(String name, double defaultValue) {
        return parse(name, defaultValue, Double::parseDouble);
    }

    /** Accepts {@code true/false}, {@code yes/no}, {@code on/off}, {@code 1/0} (case-insensitive). */
    public static boolean booleanValue(String name, boolean defaultValue) {
        String raw = lookup(name);
        if (raw == null) {
            return defaultValue;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> {
                LOG.warn("Environment variable {} has unrecognised boolean value '{}'; using default {}",
                        name, raw, defaultValue);
                yield defaultValue;
            }
        };
    }

    private static <T> T parse(String name, T defaultValue, Function<String, T> parser) {
        String raw = lookup(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return parser.apply(raw);
        } catch (NumberFormatException e) {
            LOG.warn("Environment variable {} has malformed value '{}'; using default {}",
                    name, raw, defaultValue);
            return defaultValue;
        }
    }
}
