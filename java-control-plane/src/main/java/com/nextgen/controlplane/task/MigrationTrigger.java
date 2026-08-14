package com.nextgen.controlplane.task;

/**
 * Reacts to a node crossing into predicted-failure risk by moving its work elsewhere.
 * {@code ProactiveMigrator} is the real implementation; the interface exists so {@code RiskMonitor}
 * (which decides WHEN to migrate) never needs to know HOW.
 */
public interface MigrationTrigger {

    /** Proactively moves every in-flight task off {@code nodeId} onto a healthy replacement. */
    void migrateAwayFrom(String nodeId, String reason);
}
