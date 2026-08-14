package com.nextgen.agent;

/**
 * A single OS metric sample, carrying whether the reading actually succeeded.
 *
 * <p>The availability flag is the whole point of this type. {@code OperatingSystemMXBean.getCpuLoad()}
 * returns a negative value when the platform cannot supply a reading, and the previous implementation
 * clamped that to {@code 0.0f} before sending it — publishing "this machine is completely idle" when
 * the truth was "this machine could not be measured". Callers must branch on
 * {@link #available()} and propagate unavailability rather than substituting a number.
 */
public record MetricReading(float value, boolean available) {

    private static final MetricReading UNAVAILABLE = new MetricReading(0.0f, false);

    public static MetricReading of(float value) {
        return new MetricReading(value, true);
    }

    public static MetricReading unavailable() {
        return UNAVAILABLE;
    }

    /** Formats for logs: a percentage when real, the literal {@code "unavailable"} when not. */
    public String describe() {
        return available
                ? String.format(java.util.Locale.ROOT, "%.1f%%", value)
                : "unavailable";
    }
}
