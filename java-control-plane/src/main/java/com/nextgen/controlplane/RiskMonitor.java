package com.nextgen.controlplane;

import com.nextgen.controlplane.risk.RiskScorer;
import com.nextgen.controlplane.task.MigrationTrigger;
import com.nextgen.controlplane.training.RiskSnapshotLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Periodically scores every alive node's recent trend for predicted-failure risk and, on the
 * false→true edge of {@code atRisk}, proactively migrates its in-flight work away — the predictive
 * counterpart to {@link HeartbeatMonitor}'s purely reactive dead-node detection.
 *
 * <p>Structurally parallel to {@code HeartbeatMonitor} on purpose: same {@code Runnable} shape, same
 * "sweep, then act on what the sweep returned outside any lock" discipline. The sweep itself lives in
 * {@link NodeRegistry#updateRisk}, which — like {@link NodeRegistry#sweepExpired} — mutates each node
 * atomically through {@code computeIfPresent} and hands back the transition rather than mutating
 * anything else from inside the remapping function.
 *
 * <p><b>Only the rising edge triggers a migration</b> — not every sweep while a node remains at risk.
 * Migrating once already moves everything currently in flight; migrating again on every subsequent
 * sweep would just repeatedly redispatch a task that is already correctly placed on its (fresher)
 * replacement.
 *
 * <p>This can reduce, but can never eliminate, reliance on {@code HeartbeatMonitor}'s reactive
 * fallback: distinguishing "slow" from "dead" in bounded time is the FLP impossibility result, not an
 * engineering gap this class can close. A node that fails silently with no observable trend at all is
 * still only caught reactively, once its heartbeat actually stops.
 */
public class RiskMonitor implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(RiskMonitor.class);

    /** How often the monitor re-scores every alive node. */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 5_000;

    private final NodeRegistry registry;
    private final NodeHistory history;
    private final RiskScorer scorer;
    private final MigrationTrigger migrationTrigger;
    private final long checkIntervalMs;
    private final RiskSnapshotLogger snapshotLogger;
    private final BooleanSupplier shouldSweep;

    public RiskMonitor(NodeRegistry registry, NodeHistory history, RiskScorer scorer,
                       MigrationTrigger migrationTrigger, long checkIntervalMs) {
        this(registry, history, scorer, migrationTrigger, checkIntervalMs, null);
    }

    /**
     * @param snapshotLogger opt-in, continuous (node properties, risk assessment) logging for future
     *                       negative-example training data — see {@link RiskSnapshotLogger}'s Javadoc
     *                       for why this is opt-in rather than always-on. {@code null} disables it
     *                       entirely, which the 5-arg constructor above does.
     */
    public RiskMonitor(NodeRegistry registry, NodeHistory history, RiskScorer scorer,
                       MigrationTrigger migrationTrigger, long checkIntervalMs,
                       RiskSnapshotLogger snapshotLogger) {
        this(registry, history, scorer, migrationTrigger, checkIntervalMs, snapshotLogger, () -> true);
    }

    /**
     * @param shouldSweep gates whether {@link #run}'s loop actually scores/migrates on a given tick —
     *                     under Raft, migration must happen on exactly one replica, and a follower's
     *                     registry receives no heartbeats to score trends from in the first place.
     *                     Every constructor above passes {@code () -> true}, preserving today's
     *                     always-sweep behavior exactly.
     */
    public RiskMonitor(NodeRegistry registry, NodeHistory history, RiskScorer scorer,
                       MigrationTrigger migrationTrigger, long checkIntervalMs,
                       RiskSnapshotLogger snapshotLogger, BooleanSupplier shouldSweep) {
        this.registry = registry;
        this.history = history;
        this.scorer = scorer;
        this.migrationTrigger = migrationTrigger;
        this.checkIntervalMs = checkIntervalMs;
        this.snapshotLogger = snapshotLogger;
        this.shouldSweep = shouldSweep;
    }

    @Override
    public void run() {
        LOG.info("RiskMonitor started (interval={}ms)", checkIntervalMs);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(checkIntervalMs);
                if (shouldSweep.getAsBoolean()) {
                    checkRisk();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("RiskMonitor interrupted, shutting down.");
                break;
            } catch (RuntimeException e) {
                // A transient failure inside one sweep must not kill predictive detection for the
                // rest of the process's life — same reasoning as HeartbeatMonitor's own catch here.
                LOG.error("RiskMonitor sweep failed; continuing", e);
            }
        }
    }

    /**
     * Runs one risk-scoring sweep over every alive node.
     *
     * <p>Package-private rather than private so tests can drive a single deterministic sweep without
     * waiting on the interval timer.
     */
    void checkRisk() {
        for (NodeRecord node : registry.aliveSnapshot()) {
            List<NodeHistory.Sample> recentHistory = history.recent(node.getNodeId());
            RiskScorer.RiskAssessment assessment = scorer.score(node, recentHistory);

            if (snapshotLogger != null) {
                snapshotLogger.logSnapshot(node, assessment, recentHistory);
            }

            registry.updateRisk(node.getNodeId(), assessment.riskScore(), assessment.atRisk(),
                            assessment.reasons())
                    .filter(NodeRegistry.RiskTransition::risingEdge)
                    .ifPresent(transition -> {
                        String reason = String.join("; ", assessment.reasons());
                        LOG.warn("⚠ Node '{}' crossed into AT RISK (score={}): {}",
                                node.getNodeId(), assessment.riskScore(), reason);
                        migrationTrigger.migrateAwayFrom(node.getNodeId(), reason);
                    });
        }
    }
}
