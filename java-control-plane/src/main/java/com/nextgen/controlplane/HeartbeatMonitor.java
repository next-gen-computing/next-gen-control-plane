package com.nextgen.controlplane;

import com.nextgen.controlplane.alert.AlertNotifier;
import com.nextgen.controlplane.training.RiskOutcomeLogger;
import io.prometheus.client.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Periodically checks registered nodes for heartbeat timeout, marking stragglers
 * {@link NodeStatus#SUSPECTED_DEAD} and restoring nodes whose heartbeat has resumed.
 *
 * <p>The sweep itself lives in {@link NodeRegistry#sweepExpired(long)}, which mutates each node
 * atomically through {@code computeIfPresent}. That matters: the previous implementation iterated
 * {@code registry.values()} and wrote to the mutable records it found, so it could read a stale
 * timestamp, lose a race with a heartbeat that set the node ALIVE, and then overwrite that with
 * SUSPECTED_DEAD — excluding a perfectly healthy node from scheduling for a full check interval.
 *
 * <p>Remains a {@link Runnable} with a {@code run()} loop so it can be driven by a plain thread.
 */
public class HeartbeatMonitor implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(HeartbeatMonitor.class);

    /** A node is considered dead if no heartbeat is received within this duration. */
    public static final long DEFAULT_TIMEOUT_MS = 6_000;

    /** How often the monitor checks for stragglers. */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 3_000;

    private static final Counter TRANSITIONS = Counter.build()
            .name("controlplane_node_status_transitions_total")
            .help("Node liveness transitions observed by the heartbeat monitor")
            .labelNames("from", "to")
            .register();

    private final NodeRegistry registry;
    private final long timeoutMs;
    private final long checkIntervalMs;
    private final NodeHistory history;
    private final RiskOutcomeLogger outcomeLogger;
    private final BooleanSupplier shouldSweep;
    private final AlertNotifier alertNotifier;

    /** Legacy constructor: wraps an externally-held map and uses the default timings. */
    public HeartbeatMonitor(ConcurrentHashMap<String, NodeRecord> registry) {
        this(new NodeRegistry(registry, System::currentTimeMillis),
                DEFAULT_TIMEOUT_MS, DEFAULT_CHECK_INTERVAL_MS);
    }

    public HeartbeatMonitor(NodeRegistry registry) {
        this(registry, DEFAULT_TIMEOUT_MS, DEFAULT_CHECK_INTERVAL_MS);
    }

    public HeartbeatMonitor(NodeRegistry registry, long timeoutMs, long checkIntervalMs) {
        this(registry, timeoutMs, checkIntervalMs, null, null);
    }

    /**
     * Full constructor: also logs a real (signal snapshot &rarr; eventual outcome) training example
     * via {@code outcomeLogger} whenever a node transitions {@code ALIVE -> SUSPECTED_DEAD}, using
     * {@code history} for the recent trend window at that moment. Both are nullable — every
     * constructor above passes {@code null} for both, which disables outcome logging entirely and
     * preserves this class's existing behavior exactly.
     */
    public HeartbeatMonitor(NodeRegistry registry, long timeoutMs, long checkIntervalMs,
                            NodeHistory history, RiskOutcomeLogger outcomeLogger) {
        this(registry, timeoutMs, checkIntervalMs, history, outcomeLogger, () -> true);
    }

    /**
     * @param shouldSweep gates whether {@link #run}'s loop actually sweeps on a given tick — under
     *                     Raft, a follower's registry receives zero heartbeats (heartbeats are
     *                     deliberately leader-local, never replicated), so a follower sweeping would
     *                     mark every node dead for no reason; every constructor above passes
     *                     {@code () -> true}, preserving today's always-sweep behavior exactly.
     *                     {@code ControlPlaneServer.start()} also folds in a leadership grace period
     *                     here — skip sweeping until enough time has passed since THIS replica became
     *                     leader, since its inherited {@code NodeRecord}s have frozen heartbeat
     *                     timestamps a follower never advanced.
     */
    public HeartbeatMonitor(NodeRegistry registry, long timeoutMs, long checkIntervalMs,
                            NodeHistory history, RiskOutcomeLogger outcomeLogger, BooleanSupplier shouldSweep) {
        this(registry, timeoutMs, checkIntervalMs, history, outcomeLogger, shouldSweep, null);
    }

    /**
     * @param alertNotifier Stage GG: opt-in real external alert on every {@code ALIVE ->
     *                       SUSPECTED_DEAD} transition — {@code null} (every constructor above)
     *                       disables alerting entirely, preserving this class's existing behavior.
     */
    public HeartbeatMonitor(NodeRegistry registry, long timeoutMs, long checkIntervalMs,
                            NodeHistory history, RiskOutcomeLogger outcomeLogger, BooleanSupplier shouldSweep,
                            AlertNotifier alertNotifier) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.checkIntervalMs = checkIntervalMs;
        this.history = history;
        this.outcomeLogger = outcomeLogger;
        this.shouldSweep = shouldSweep;
        this.alertNotifier = alertNotifier;
    }

    @Override
    public void run() {
        LOG.info("HeartbeatMonitor started (timeout={}ms, interval={}ms)", timeoutMs, checkIntervalMs);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(checkIntervalMs);
                if (shouldSweep.getAsBoolean()) {
                    checkHeartbeats();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("HeartbeatMonitor interrupted, shutting down.");
                break;
            } catch (RuntimeException e) {
                // A transient failure inside one sweep must not kill liveness detection for the rest
                // of the process's life. Log it and keep sweeping.
                LOG.error("HeartbeatMonitor sweep failed; continuing", e);
            }
        }
    }

    /**
     * Runs one liveness sweep.
     *
     * <p>Package-private rather than private so tests can drive a single deterministic sweep without
     * waiting on the interval timer.
     */
    void checkHeartbeats() {
        List<NodeRegistry.StatusTransition> transitions = registry.sweepExpired(timeoutMs);

        // Logging and metrics happen here, outside the registry's compute() calls — never inside a
        // ConcurrentHashMap remapping function.
        for (NodeRegistry.StatusTransition transition : transitions) {
            TRANSITIONS.labels(transition.from().wireName(), transition.to().wireName()).inc();
            if (transition.to() == NodeStatus.SUSPECTED_DEAD) {
                LOG.warn("⚠ Node '{}' marked SUSPECTED_DEAD (no heartbeat for >{}ms)",
                        transition.nodeId(), timeoutMs);
                logOutcomeIfEnabled(transition);
                if (alertNotifier != null) {
                    alertNotifier.notifyNodeDown(transition.nodeId(),
                            "no heartbeat for over " + timeoutMs + "ms");
                }
            } else if (transition.to() == NodeStatus.ALIVE) {
                LOG.info("✅ Node '{}' recovered — heartbeat received", transition.nodeId());
            }
        }
    }

    /** A no-op whenever this monitor was built without an {@link RiskOutcomeLogger} (the legacy path). */
    private void logOutcomeIfEnabled(NodeRegistry.StatusTransition transition) {
        if (outcomeLogger == null) {
            return;
        }
        registry.get(transition.nodeId()).ifPresent(node -> {
            List<NodeHistory.Sample> recent = history == null ? List.of() : history.recent(transition.nodeId());
            outcomeLogger.logSuspectedDeath(transition, node, recent);
        });
    }
}
