package com.nextgen.agent;

import com.nextgen.proto.ControlPlaneProto;
import com.sun.management.OperatingSystemMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;

/**
 * Reads real CPU and memory figures from the JVM's operating-system MXBean.
 *
 * <p>Every call takes a fresh sample; nothing is cached. The bean itself is obtained once because it
 * is a live view rather than a snapshot — re-fetching it per tick would cost work without changing
 * the values.
 *
 * <p>When the platform cannot supply a reading, this class returns
 * {@link MetricReading#unavailable()}. It never substitutes a default, a last-known value, or a
 * derived guess: an unmeasurable machine must be reported as unmeasurable all the way to the UI.
 */
public class SystemMetricsReader {

    private final OperatingSystemMXBean osBean;

    public SystemMetricsReader() {
        this((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean());
    }

    /** Injectable for tests. */
    public SystemMetricsReader(OperatingSystemMXBean osBean) {
        this.osBean = osBean;
    }

    /**
     * Current system-wide CPU load as a percentage.
     *
     * <p>{@code getCpuLoad()} returns a negative number when a reading is not available — notably on
     * the very first sample, before the bean has two points to difference between. NaN is also
     * possible on some platforms. Both are reported as unavailable.
     */
    public MetricReading readCpuPercent() {
        double load;
        try {
            load = osBean.getCpuLoad();
        } catch (RuntimeException e) {
            return MetricReading.unavailable();
        }
        if (load < 0 || Double.isNaN(load)) {
            return MetricReading.unavailable();
        }
        return MetricReading.of((float) (load * 100.0));
    }

    /** Current physical memory utilisation as a percentage. */
    public MetricReading readMemoryPercent() {
        long total;
        long free;
        try {
            total = osBean.getTotalMemorySize();
            free = osBean.getFreeMemorySize();
        } catch (RuntimeException e) {
            return MetricReading.unavailable();
        }
        if (total <= 0 || free < 0 || free > total) {
            return MetricReading.unavailable();
        }
        return MetricReading.of((float) ((total - free) * 100.0 / total));
    }

    /**
     * Static hardware and platform facts, reported once at registration.
     *
     * <p>Fields the platform declines to report are left at their proto default rather than being
     * filled with a plausible-looking figure.
     */
    public ControlPlaneProto.NodeCapabilities detectCapabilities() {
        ControlPlaneProto.NodeCapabilities.Builder builder =
                ControlPlaneProto.NodeCapabilities.newBuilder();

        try {
            builder.setCpuCores(Runtime.getRuntime().availableProcessors());
        } catch (RuntimeException ignored) {
            // leave unset
        }
        try {
            long total = osBean.getTotalMemorySize();
            if (total > 0) {
                builder.setTotalMemoryBytes(total);
            }
        } catch (RuntimeException ignored) {
            // leave unset
        }
        try {
            long disk = new File(".").getAbsoluteFile().getTotalSpace();
            if (disk > 0) {
                builder.setTotalDiskBytes(disk);
            }
        } catch (RuntimeException ignored) {
            // leave unset (SecurityException is a RuntimeException and is covered here)
        }

        builder.setOsName(String.valueOf(System.getProperty("os.name", "")))
                .setOsArch(String.valueOf(System.getProperty("os.arch", "")))
                .setJvmVersion(String.valueOf(System.getProperty("java.version", "")));

        return builder.build();
    }
}
