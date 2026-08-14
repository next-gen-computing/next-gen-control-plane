package com.nextgen.desktop.ui.profile;

/**
 * One remembered task/job submission — kept across app restarts so "what did I run, and against
 * which cluster" survives closing the app, not just the lifetime of one session's in-memory list.
 *
 * @param kind "task" or "job"
 * @param clusterLabel the control-plane address this ran against (e.g. "192.168.1.42:50051") —
 *                      recorded at submission time, so history stays accurate even if the app later
 *                      connects to a different cluster.
 * @param completedAtEpochMillis 0 while still in flight.
 */
public record HistoryEntry(
        String id,
        String kind,
        String typeLabel,
        String status,
        String clusterLabel,
        long submittedAtEpochMillis,
        long completedAtEpochMillis,
        String summary
) {
    String key() {
        return kind + ":" + id;
    }
}
