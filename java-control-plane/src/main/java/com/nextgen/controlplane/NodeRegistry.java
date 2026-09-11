package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The authoritative store of registered nodes.
 *
 * <p>This class exists to give the node map a single place where mutation happens. Previously a bare
 * {@code ConcurrentHashMap} was passed by reference to the gRPC service, the heartbeat monitor and
 * the dashboard handler, each of which did its own read-modify-write on mutable records. Individual
 * field writes were atomic; the compound operations never were.
 *
 * <h2>Concurrency contract</h2>
 *
 * Every mutation goes through {@link ConcurrentHashMap#compute} or
 * {@link ConcurrentHashMap#computeIfPresent}, which hold the per-key bin lock for the duration of the
 * remapping function. That makes read-decide-write atomic <i>per node</i>, so a registration and a
 * heartbeat for the same node serialise instead of racing.
 *
 * <p><b>Hard rule for maintainers:</b> a remapping function passed to {@code compute} must not log,
 * must not touch metrics, must not perform I/O, and above all must not perform any other operation on
 * this same map. {@code ConcurrentHashMap} explicitly forbids re-entrant access from a remapping
 * function — doing so deadlocks or corrupts the bin. Every counter increment and log line in this
 * class therefore happens <i>after</i> the {@code compute} call returns.
 *
 * <p>The backing map is injectable so that callers still holding a reference to the original map (and
 * the tests that assert against it) observe the same state.
 */
public final class NodeRegistry {

    private final ConcurrentHashMap<String, NodeRecord> nodes;
    private final LongSupplier clock;

    public NodeRegistry() {
        this(new ConcurrentHashMap<>(), System::currentTimeMillis);
    }

    /**
     * @param backing the map to wrap. Shared by reference, not copied, so existing holders of the
     *                map see every change made through this registry.
     * @param clock   millisecond time source; injectable so tests need no sleeps.
     */
    public NodeRegistry(ConcurrentHashMap<String, NodeRecord> backing, LongSupplier clock) {
        this.nodes = backing;
        this.clock = clock;
    }

    /** The wrapped map. Exposed for collaborators that still take a raw map. */
    public ConcurrentHashMap<String, NodeRecord> backingMap() {
        return nodes;
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Registers a node, or merges the registration into an existing record.
     *
     * <p>A node that reconnects after being marked {@code SUSPECTED_DEAD} is re-integrated into the
     * <i>same</i> entry — never duplicated, and never left behind as a zombie. Its accumulated CPU and
     * memory readings are carried forward rather than reset, which is what the previous
     * unconditional {@code put} got wrong.
     */
    public RegistrationOutcome register(String nodeId, String ip, int port, String hostname,
                                        ControlPlaneProto.NodeCapabilities capabilities,
                                        String agentVersion) {
        long now = clock.getAsLong();
        // Single-element arrays carry the branch taken inside the remapping function back out, so no
        // logging or metrics need to happen while the bin lock is held.
        boolean[] created = new boolean[1];
        NodeRecord result = nodes.compute(nodeId, (key, existing) -> {
            if (existing == null) {
                created[0] = true;
                return NodeRecord.fresh(key, ip, port, hostname, capabilities, agentVersion, now);
            }
            return existing.withRegistration(ip, port, hostname, capabilities, agentVersion, now);
        });
        return new RegistrationOutcome(result, created[0]);
    }

    /**
     * Records a heartbeat against an existing node.
     *
     * <p>Deliberately does <b>not</b> auto-register an unknown node. A heartbeat carries no address,
     * hostname or capabilities, so the resulting record would be a fabrication; and once mutual TLS is
     * in play, auto-registration would let any node with a valid certificate resurrect an entry that
     * was deliberately deregistered. The caller signals {@code reregistration_required} instead.
     */
    public HeartbeatOutcome recordHeartbeat(String nodeId, float cpu, boolean cpuValid,
                                            float memory, boolean memoryValid, long sequence) {
        long now = clock.getAsLong();
        NodeRecord[] updated = new NodeRecord[1];
        nodes.computeIfPresent(nodeId, (key, existing) ->
                updated[0] = existing.withHeartbeat(cpu, cpuValid, memory, memoryValid, now, sequence));
        return updated[0] == null
                ? HeartbeatOutcome.unknownNode()
                : HeartbeatOutcome.accepted(updated[0]);
    }

    /**
     * Records the certificate a node was issued, so its status can be surfaced in the UI.
     *
     * @return true when a record existed and was updated.
     */
    public boolean attachCertificate(String nodeId, ControlPlaneProto.CertificateInfo certificate) {
        NodeRecord[] updated = new NodeRecord[1];
        nodes.computeIfPresent(nodeId, (key, existing) ->
                updated[0] = existing.withCertificate(certificate));
        return updated[0] != null;
    }

    /**
     * Marks a node deregistered, or drains it.
     *
     * @param drain when true the record is kept and marked {@link NodeStatus#DRAINING} — it stops
     *              receiving work but its telemetry stays visible. When false the entry is removed.
     * @return true if a record existed and was changed.
     */
    public boolean deregister(String nodeId, boolean drain) {
        if (drain) {
            NodeRecord[] updated = new NodeRecord[1];
            nodes.computeIfPresent(nodeId, (key, existing) ->
                    updated[0] = existing.withStatus(NodeStatus.DRAINING));
            return updated[0] != null;
        }
        return nodes.remove(nodeId) != null;
    }

    /**
     * Expires nodes whose last heartbeat is older than {@code timeoutMillis}, and restores nodes whose
     * heartbeat has since arrived.
     *
     * <p>Iterates {@code keySet()} and mutates through {@code computeIfPresent} per key. Iterating
     * {@code values()} and mutating the returned records — what the previous implementation did — is
     * the same lost-update pattern that let the sweep overwrite a heartbeat that had already arrived:
     * the monitor would read a stale timestamp, the heartbeat thread would set ALIVE, and the monitor
     * would then clobber it with SUSPECTED_DEAD, excluding a healthy node from scheduling for a full
     * check interval.
     *
     * @return the transitions that occurred, so the caller can log and count them outside the lock.
     */
    public List<StatusTransition> sweepExpired(long timeoutMillis) {
        long now = clock.getAsLong();
        List<StatusTransition> transitions = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            nodes.computeIfPresent(nodeId, (key, record) -> {
                // A deregistered or draining node is in an administrative state that liveness must
                // not override.
                if (record.getStatus() == NodeStatus.DEREGISTERED
                        || record.getStatus() == NodeStatus.DRAINING) {
                    return record;
                }
                boolean expired = now - record.getLastHeartbeatMillis() > timeoutMillis;
                NodeStatus target = expired ? NodeStatus.SUSPECTED_DEAD : NodeStatus.ALIVE;
                if (record.getStatus() == target) {
                    return record;
                }
                transitions.add(new StatusTransition(key, record.getStatus(), target));
                return record.withStatus(target);
            });
        }
        return transitions;
    }

    /**
     * Records a fresh risk assessment for a node.
     *
     * <p>Returns the transition (previous vs. new {@code atRisk}) rather than acting on it here, the
     * same "return the transition, act outside the lock" discipline {@link #sweepExpired} uses — a
     * caller (e.g. {@code RiskMonitor}) can safely trigger a migration on the false→true edge without
     * any of that work happening inside this map's remapping function.
     *
     * @return empty if the node is unknown (e.g. deregistered between being snapshotted and scored).
     */
    public Optional<RiskTransition> updateRisk(String nodeId, double riskScore, boolean atRisk,
                                               List<String> reasons) {
        long now = clock.getAsLong();
        RiskTransition[] transition = new RiskTransition[1];
        nodes.computeIfPresent(nodeId, (key, existing) -> {
            boolean wasAtRisk = existing.isAtRisk();
            transition[0] = new RiskTransition(key, wasAtRisk, atRisk, riskScore, reasons);
            return existing.withRisk(riskScore, atRisk, reasons, now);
        });
        return Optional.ofNullable(transition[0]);
    }

    // ── Read paths ───────────────────────────────────────────────────────────

    public Optional<NodeRecord> get(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public boolean contains(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    public int size() {
        return nodes.size();
    }

    /**
     * All nodes, sorted by node id.
     *
     * <p>The sort matters: {@code ConcurrentHashMap.values()} has unspecified iteration order that
     * changes on resize, so anything positional over it (such as the old round-robin index) silently
     * addressed different nodes over time.
     */
    public List<NodeRecord> snapshot() {
        List<NodeRecord> all = new ArrayList<>(nodes.values());
        all.sort(Comparator.comparing(NodeRecord::getNodeId));
        return Collections.unmodifiableList(all);
    }

    /** Nodes eligible to receive work, sorted by node id. */
    public List<NodeRecord> aliveSnapshot() {
        List<NodeRecord> alive = new ArrayList<>();
        for (NodeRecord record : nodes.values()) {
            if (record.getStatus().isSchedulable()) {
                alive.add(record);
            }
        }
        alive.sort(Comparator.comparing(NodeRecord::getNodeId));
        return Collections.unmodifiableList(alive);
    }

    /** Live counts per status, for the metrics collector. */
    public Map<NodeStatus, Integer> statusCounts() {
        Map<NodeStatus, Integer> counts = new EnumMap<>(NodeStatus.class);
        for (NodeStatus status : NodeStatus.values()) {
            counts.put(status, 0);
        }
        for (NodeRecord record : nodes.values()) {
            counts.merge(record.getStatus(), 1, Integer::sum);
        }
        return counts;
    }

    // ── Result types ─────────────────────────────────────────────────────────

    /** @param created true when a new entry was made, false when an existing node was re-integrated. */
    public record RegistrationOutcome(NodeRecord record, boolean created) {
        public boolean resumedExisting() {
            return !created;
        }
    }

    /** @param record the updated record, or null when the node is unknown to the registry. */
    public record HeartbeatOutcome(NodeRecord record, boolean known) {
        static HeartbeatOutcome accepted(NodeRecord record) {
            return new HeartbeatOutcome(record, true);
        }

        static HeartbeatOutcome unknownNode() {
            return new HeartbeatOutcome(null, false);
        }
    }

    public record StatusTransition(String nodeId, NodeStatus from, NodeStatus to) {
    }

    /** @param reasons human-readable explanations from the scorer, for logging on the rising edge. */
    public record RiskTransition(String nodeId, boolean wasAtRisk, boolean isAtRisk,
                                 double riskScore, List<String> reasons) {
        /** True only on the false→true transition — what {@code RiskMonitor} migrates work on. */
        public boolean risingEdge() {
            return !wasAtRisk && isAtRisk;
        }
    }
}
