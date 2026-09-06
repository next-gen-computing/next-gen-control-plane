package com.nextgen.agent;

/**
 * A single boolean OS/hardware signal, carrying whether it is actually known.
 *
 * <p>The same discipline as {@link MetricReading}, for booleans: a machine with no battery, or a
 * platform that declines to report charging/AC-power state, must report {@code known = false}
 * rather than a guessed {@code true}/{@code false}. Callers must branch on {@link #known()} and
 * propagate unavailability rather than substituting a value.
 */
public record BooleanReading(boolean value, boolean known) {

    private static final BooleanReading UNKNOWN = new BooleanReading(false, false);

    public static BooleanReading of(boolean value) {
        return new BooleanReading(value, true);
    }

    public static BooleanReading unknown() {
        return UNKNOWN;
    }

    public String describe() {
        return known ? String.valueOf(value) : "unknown";
    }
}
