package com.nextgen.agent;

import com.nextgen.proto.ControlPlaneProto;
import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SystemMetricsReader.
 *
 * <p>The central guarantee under test: when the platform cannot supply a reading, the reader reports
 * <i>unavailable</i> rather than substituting a plausible number. The previous agent clamped a
 * negative CPU load to {@code 0.0f} and sent it as fact.
 */
class SystemMetricsReaderTest {

    @Test
    void readsRealCpuLoadAsAPercentage() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getCpuLoad()).thenReturn(0.42);

        MetricReading reading = new SystemMetricsReader(bean).readCpuPercent();

        assertTrue(reading.available());
        assertEquals(42.0f, reading.value(), 0.01);
    }

    @Test
    void negativeCpuLoadIsReportedUnavailableNotZero() {
        // getCpuLoad() returns -1 when no reading is available — notably on the very first sample.
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getCpuLoad()).thenReturn(-1.0);

        MetricReading reading = new SystemMetricsReader(bean).readCpuPercent();

        assertFalse(reading.available(),
                "an unmeasurable CPU must not be published as a real 0.0% reading");
        assertEquals("unavailable", reading.describe());
    }

    @Test
    void nanCpuLoadIsReportedUnavailable() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getCpuLoad()).thenReturn(Double.NaN);

        assertFalse(new SystemMetricsReader(bean).readCpuPercent().available());
    }

    @Test
    void exceptionFromTheBeanIsReportedUnavailable() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getCpuLoad()).thenThrow(new UnsupportedOperationException("no such counter"));

        assertFalse(new SystemMetricsReader(bean).readCpuPercent().available());
    }

    @Test
    void readsRealMemoryUtilisation() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenReturn(1_000L);
        when(bean.getFreeMemorySize()).thenReturn(250L);

        MetricReading reading = new SystemMetricsReader(bean).readMemoryPercent();

        assertTrue(reading.available());
        assertEquals(75.0f, reading.value(), 0.01);
    }

    @Test
    void zeroTotalMemoryIsReportedUnavailableNotZeroPercent() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenReturn(0L);
        when(bean.getFreeMemorySize()).thenReturn(0L);

        assertFalse(new SystemMetricsReader(bean).readMemoryPercent().available());
    }

    @Test
    void incoherentMemoryFiguresAreReportedUnavailable() {
        // free > total cannot be true; publishing the resulting negative utilisation would be worse
        // than admitting the reading is unusable.
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenReturn(100L);
        when(bean.getFreeMemorySize()).thenReturn(500L);

        assertFalse(new SystemMetricsReader(bean).readMemoryPercent().available());
    }

    @Test
    void eachReadTakesAFreshSample() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getCpuLoad()).thenReturn(0.10, 0.20, 0.30);

        SystemMetricsReader reader = new SystemMetricsReader(bean);

        assertEquals(10.0f, reader.readCpuPercent().value(), 0.01);
        assertEquals(20.0f, reader.readCpuPercent().value(), 0.01);
        assertEquals(30.0f, reader.readCpuPercent().value(), 0.01);
        verify(bean, times(3)).getCpuLoad();
    }

    @Test
    void detectsRealCapabilities() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenReturn(8L * 1024 * 1024 * 1024);

        ControlPlaneProto.NodeCapabilities caps = new SystemMetricsReader(bean).detectCapabilities();

        assertEquals(Runtime.getRuntime().availableProcessors(), caps.getCpuCores());
        assertEquals(8L * 1024 * 1024 * 1024, caps.getTotalMemoryBytes());
        assertEquals(System.getProperty("os.name"), caps.getOsName());
        assertEquals(System.getProperty("java.version"), caps.getJvmVersion());
    }

    @Test
    void capabilitiesLeaveUnreadableFieldsUnsetRatherThanGuessing() {
        OperatingSystemMXBean bean = mock(OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenThrow(new UnsupportedOperationException());

        ControlPlaneProto.NodeCapabilities caps = new SystemMetricsReader(bean).detectCapabilities();

        assertEquals(0L, caps.getTotalMemoryBytes(), "an unknown size stays at the proto default");
        assertFalse(caps.getOsName().isEmpty(), "fields that ARE readable are still populated");
    }

    @Test
    void metricReadingDescribesAvailableValuesAsPercentages() {
        assertEquals("42.5%", MetricReading.of(42.5f).describe());
        assertEquals("unavailable", MetricReading.unavailable().describe());
    }
}
