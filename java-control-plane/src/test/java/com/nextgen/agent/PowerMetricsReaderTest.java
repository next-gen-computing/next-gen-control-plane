package com.nextgen.agent;

import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PowerMetricsReader.
 *
 * <p>The central guarantee under test, mirroring {@link com.nextgen.agent.SystemMetricsReaderTest}:
 * a desktop machine with no battery — the common case, not an edge case — must report
 * {@code battery_available = false}, never a fabricated reading. Same for a read that fails outright.
 */
class PowerMetricsReaderTest {

    @Test
    void aDesktopWithNoBatteryReportsEverythingUnavailable() {
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        when(hardware.getPowerSources()).thenReturn(List.of());

        PowerMetricsReader.PowerReading reading = new PowerMetricsReader(hardware).readPowerStatus();

        assertFalse(reading.batteryPercent().available(),
                "a machine with no battery must never report a fabricated percentage");
        assertFalse(reading.charging().known());
        assertFalse(reading.onAcPower().known());
    }

    @Test
    void readsRealBatteryChargingAndAcPowerFromTheFirstSource() {
        PowerSource battery = mock(PowerSource.class);
        when(battery.getRemainingCapacityPercent()).thenReturn(0.73);
        when(battery.isCharging()).thenReturn(true);
        when(battery.isPowerOnLine()).thenReturn(true);
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        when(hardware.getPowerSources()).thenReturn(List.of(battery));

        PowerMetricsReader.PowerReading reading = new PowerMetricsReader(hardware).readPowerStatus();

        assertTrue(reading.batteryPercent().available());
        assertEquals(73.0f, reading.batteryPercent().value(), 0.01);
        assertTrue(reading.charging().known());
        assertTrue(reading.charging().value());
        assertTrue(reading.onAcPower().known());
        assertTrue(reading.onAcPower().value());
    }

    @Test
    void aDischargingUnpluggedLaptopReportsChargingFalseNotUnknown() {
        PowerSource battery = mock(PowerSource.class);
        when(battery.getRemainingCapacityPercent()).thenReturn(0.42);
        when(battery.isCharging()).thenReturn(false);
        when(battery.isPowerOnLine()).thenReturn(false);
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        when(hardware.getPowerSources()).thenReturn(List.of(battery));

        PowerMetricsReader.PowerReading reading = new PowerMetricsReader(hardware).readPowerStatus();

        assertTrue(reading.charging().known(), "false-but-known must not collapse into unknown");
        assertFalse(reading.charging().value());
        assertTrue(reading.onAcPower().known());
        assertFalse(reading.onAcPower().value());
    }

    @Test
    void aNegativeOrNanCapacityIsReportedUnavailableNotAsIs() {
        PowerSource battery = mock(PowerSource.class);
        when(battery.getRemainingCapacityPercent()).thenReturn(Double.NaN);
        when(battery.isCharging()).thenReturn(false);
        when(battery.isPowerOnLine()).thenReturn(true);
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        when(hardware.getPowerSources()).thenReturn(List.of(battery));

        PowerMetricsReader.PowerReading reading = new PowerMetricsReader(hardware).readPowerStatus();

        assertFalse(reading.batteryPercent().available());
        // Charging/AC-power are independent signals from a different accessor and must still resolve.
        assertTrue(reading.onAcPower().value());
    }

    @Test
    void anExceptionFromGetPowerSourcesIsReportedAsNoBatteryPresent() {
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        when(hardware.getPowerSources()).thenThrow(new RuntimeException("platform error"));

        PowerMetricsReader.PowerReading reading = new PowerMetricsReader(hardware).readPowerStatus();

        assertFalse(reading.batteryPercent().available());
        assertFalse(reading.charging().known());
        assertFalse(reading.onAcPower().known());
    }

    @Test
    void anExceptionFromASinglePowerSourceAccessorDoesNotFailTheWholeReading() {
        PowerSource battery = mock(PowerSource.class);
        when(battery.getRemainingCapacityPercent()).thenReturn(0.55);
        when(battery.isCharging()).thenThrow(new RuntimeException("no such counter"));
        when(battery.isPowerOnLine()).thenReturn(true);
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        when(hardware.getPowerSources()).thenReturn(List.of(battery));

        PowerMetricsReader.PowerReading reading = new PowerMetricsReader(hardware).readPowerStatus();

        assertTrue(reading.batteryPercent().available(), "an unrelated accessor failing must not sink this reading");
        assertEquals(55.0f, reading.batteryPercent().value(), 0.01);
        assertFalse(reading.charging().known(), "the one accessor that failed must report unknown");
        assertTrue(reading.onAcPower().known());
    }

    @Test
    void booleanReadingDescribesKnownAndUnknownValues() {
        assertEquals("true", BooleanReading.of(true).describe());
        assertEquals("unknown", BooleanReading.unknown().describe());
    }
}
