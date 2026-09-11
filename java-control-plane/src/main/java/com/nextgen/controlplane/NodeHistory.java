package com.nextgen.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-owned per-node rolling sample history — the real input the predictive risk scorer (Stage D)
 * will look for a *trend* in, not just current state.
 *
 * <p>This is deliberately separate from the desktop-ui's {@code MetricsHistory}: that class lives
 * client-side, populated by whatever a connected dashboard happens to be polling, and simply does not
 * exist unless a desktop-ui client is attached. The risk scorer runs inside the control plane process
 * itself and must see history regardless of whether anyone is watching a dashboard, so this class is
 * populated directly in {@code ControlPlaneServiceImpl.sendHeartbeat} — the one place every real
 * heartbeat already passes through.
 *
 * <p>Mirrors {@code TaskRegistry}'s concurrency contract: every mutation goes through
 * {@link ConcurrentHashMap#compute}, and the value stored per node is an immutable, copy-on-write
 * list — no lock is held across anything but the single {@code compute} call.
 */
public final class NodeHistory {

    /**
     * Bounds memory and keeps the risk scorer looking at genuinely recent trend, not a node's entire
     * lifetime. At the default ~2s heartbeat interval this is roughly the last minute.
     */
    static final int MAX_SAMPLES_PER_NODE = 30;

    private final ConcurrentHashMap<String, List<Sample>> samples = new ConcurrentHashMap<>();

    /** Appends a sample, trimming the oldest once {@link #MAX_SAMPLES_PER_NODE} is exceeded. */
    public void record(String nodeId, Sample sample) {
        samples.compute(nodeId, (key, existing) -> {
            List<Sample> current = existing == null ? List.of() : existing;
            List<Sample> updated = new ArrayList<>(current);
            updated.add(sample);
            int excess = updated.size() - MAX_SAMPLES_PER_NODE;
            if (excess > 0) {
                updated = new ArrayList<>(updated.subList(excess, updated.size()));
            }
            return List.copyOf(updated);
        });
    }

    /** This node's samples, oldest first. Empty (never null) if the node has none yet. */
    public List<Sample> recent(String nodeId) {
        return samples.getOrDefault(nodeId, List.of());
    }

    /** The most recently recorded sample, if any. */
    public Optional<Sample> latest(String nodeId) {
        List<Sample> all = recent(nodeId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /**
     * Forgets a node's history entirely. Called on real deregistration (never on drain, which keeps
     * the node's other telemetry visible too) — otherwise a removed node id reused by an unrelated
     * physical machine would inherit a stale trend that was never really about it.
     */
    public void forget(String nodeId) {
        samples.remove(nodeId);
    }

    /**
     * One heartbeat's worth of trend-relevant signals. Every {@code *Available}/{@code *Known} flag
     * follows the project-wide rule: false means the paired value is meaningless and must not be
     * treated as a reading — mirrors {@code HeartbeatRequest} field-for-field.
     */
    public record Sample(
            long recordedAtMillis,
            float cpuPercent, boolean cpuAvailable,
            float memoryPercent, boolean memoryAvailable,
            float batteryPercent, boolean batteryAvailable,
            boolean charging, boolean chargingKnown,
            boolean onAcPower, boolean onAcPowerKnown,
            double previousRttSeconds, boolean previousRttAvailable
    ) {
    }
}
