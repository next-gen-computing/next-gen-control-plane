package com.nextgen.agent;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;

import java.util.List;

/**
 * Reads real battery/charging/AC-power figures via OSHI.
 *
 * <p>Sibling to {@link SystemMetricsReader}, same discipline: every call takes a fresh sample, and
 * when the platform cannot supply a reading — including the common case of a desktop machine with no
 * battery at all — this returns {@link PowerReading#noBatteryPresent()} rather than a fabricated
 * value. A permanently-absent battery and a momentarily-failed read are reported identically
 * ({@code battery_available = false}); this class has no reliable way to distinguish "no battery
 * exists" from "OSHI could not read it this tick", and either way there is nothing real to report.
 *
 * <p>All three signals (battery percent, charging, on-AC-power) are read from ONE
 * {@link HardwareAbstractionLayer#getPowerSources()} call and returned together, not as three
 * separate reads — they describe the same underlying hardware snapshot, and reading them separately
 * could observe the machine transitioning (e.g. unplugged mid-read) between two supposedly
 * simultaneous facts.
 */
public class PowerMetricsReader {

    private final HardwareAbstractionLayer hardware;

    public PowerMetricsReader() {
        this(new SystemInfo().getHardware());
    }

    /** Injectable for tests. */
    public PowerMetricsReader(HardwareAbstractionLayer hardware) {
        this.hardware = hardware;
    }

    public PowerReading readPowerStatus() {
        List<PowerSource> sources;
        try {
            sources = hardware.getPowerSources();
        } catch (RuntimeException e) {
            return PowerReading.noBatteryPresent();
        }
        if (sources == null || sources.isEmpty()) {
            return PowerReading.noBatteryPresent();
        }

        PowerSource battery = sources.get(0);

        MetricReading batteryPercent;
        try {
            double pct = battery.getRemainingCapacityPercent() * 100.0;
            batteryPercent = (Double.isNaN(pct) || pct < 0)
                    ? MetricReading.unavailable() : MetricReading.of((float) pct);
        } catch (RuntimeException e) {
            batteryPercent = MetricReading.unavailable();
        }

        BooleanReading charging;
        try {
            charging = BooleanReading.of(battery.isCharging());
        } catch (RuntimeException e) {
            charging = BooleanReading.unknown();
        }

        BooleanReading onAcPower;
        try {
            onAcPower = BooleanReading.of(battery.isPowerOnLine());
        } catch (RuntimeException e) {
            onAcPower = BooleanReading.unknown();
        }

        return new PowerReading(batteryPercent, charging, onAcPower);
    }

    /** One hardware power snapshot — battery percentage plus charging/AC-power state. */
    public record PowerReading(MetricReading batteryPercent, BooleanReading charging, BooleanReading onAcPower) {

        private static final PowerReading NO_BATTERY = new PowerReading(
                MetricReading.unavailable(), BooleanReading.unknown(), BooleanReading.unknown());

        public static PowerReading noBatteryPresent() {
            return NO_BATTERY;
        }
    }
}
