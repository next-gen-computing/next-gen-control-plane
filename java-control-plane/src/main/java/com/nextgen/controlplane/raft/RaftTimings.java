package com.nextgen.controlplane.raft;

/**
 * Election/heartbeat tunables, read from environment variables by {@code ControlPlaneServer.start()}.
 * Defaults keep a full election comfortably inside {@code ControlPlaneClient}'s existing 4s
 * {@code CALL_DEADLINE_MS} and the existing 6s {@code HeartbeatMonitor.DEFAULT_TIMEOUT_MS} node-side
 * liveness window — a leader change costs roughly one node reconnect cycle, not a new failure mode.
 */
public record RaftTimings(
        long heartbeatIntervalMs,
        long electionTimeoutMinMs,
        long electionTimeoutMaxMs,
        long proposeTimeoutMs) {

    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 150;
    public static final long DEFAULT_ELECTION_TIMEOUT_MIN_MS = 750;
    public static final long DEFAULT_ELECTION_TIMEOUT_MAX_MS = 1500;
    public static final long DEFAULT_PROPOSE_TIMEOUT_MS = 3000;

    public RaftTimings {
        if (electionTimeoutMinMs >= electionTimeoutMaxMs) {
            throw new IllegalArgumentException("electionTimeoutMinMs must be < electionTimeoutMaxMs");
        }
        if (heartbeatIntervalMs <= 0 || electionTimeoutMinMs <= 0 || proposeTimeoutMs <= 0) {
            throw new IllegalArgumentException("all Raft timings must be positive");
        }
    }

    public static RaftTimings defaults() {
        return new RaftTimings(DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_ELECTION_TIMEOUT_MIN_MS,
                DEFAULT_ELECTION_TIMEOUT_MAX_MS, DEFAULT_PROPOSE_TIMEOUT_MS);
    }
}
