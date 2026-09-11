package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeRegistry, including the concurrency guarantees that motivated its extraction.
 */
class NodeRegistryTest {

    private static NodeRegistry registryAt(AtomicLong clock) {
        return new NodeRegistry(new ConcurrentHashMap<>(), clock::get);
    }

    private static ControlPlaneProto.NodeCapabilities caps(int cores) {
        return ControlPlaneProto.NodeCapabilities.newBuilder().setCpuCores(cores).build();
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    void registerCreatesNewNode() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));

        NodeRegistry.RegistrationOutcome outcome =
                registry.register("node1", "10.0.0.1", 50051, "host1", caps(4), "1.0.0");

        assertTrue(outcome.created());
        assertFalse(outcome.resumedExisting());
        assertEquals(1, registry.size());
        assertEquals("host1", outcome.record().getHostname());
        assertEquals(4, outcome.record().getCapabilities().getCpuCores());
    }

    @Test
    void reRegisterMergesInsteadOfReplacing() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = registryAt(clock);
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(4), "1.0.0");
        registry.recordHeartbeat("node1", 73.0f, true, 64.0f, true, 1);

        clock.set(50_000L);
        NodeRegistry.RegistrationOutcome outcome =
                registry.register("node1", "10.0.0.2", 50051, "host1", caps(4), "1.0.1");

        assertTrue(outcome.resumedExisting());
        assertEquals(1, registry.size(), "no duplicate entry may be created");
        assertEquals("10.0.0.2", outcome.record().getIp(), "a moved node's address must update");
        assertEquals(73.0f, outcome.record().getCpuUsage(), 0.001,
                "live telemetry must survive a reconnect");
        assertFalse(outcome.record().isCpuStale());
    }

    @Test
    void reRegisterAfterBeingMarkedDeadRestoresTheNodeCleanly() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = registryAt(clock);
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(2), "1.0.0");
        registry.recordHeartbeat("node1", 20f, true, 30f, true, 1);

        clock.set(100_000L);
        registry.sweepExpired(6_000L);
        assertEquals(NodeStatus.SUSPECTED_DEAD, registry.get("node1").orElseThrow().getStatus());

        registry.register("node1", "10.0.0.1", 50051, "host1", caps(2), "1.0.0");

        assertEquals(1, registry.size(), "no zombie entry may be left behind");
        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
        assertEquals(1, registry.aliveSnapshot().size());
    }

    // ── Heartbeats ───────────────────────────────────────────────────────────

    @Test
    void heartbeatForUnknownNodeIsRejectedAndDoesNotCreateAnEntry() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));

        NodeRegistry.HeartbeatOutcome outcome =
                registry.recordHeartbeat("ghost", 10f, true, 10f, true, 1);

        assertFalse(outcome.known());
        assertNull(outcome.record());
        assertEquals(0, registry.size(),
                "auto-registering from a heartbeat would fabricate address and capability data");
    }

    @Test
    void heartbeatWithUnavailableReadingsPreservesLastKnownValues() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(1), "1.0.0");
        registry.recordHeartbeat("node1", 88.0f, true, 44.0f, true, 1);

        NodeRegistry.HeartbeatOutcome outcome =
                registry.recordHeartbeat("node1", 0f, false, 0f, false, 2);

        assertTrue(outcome.known());
        assertEquals(88.0f, outcome.record().getCpuUsage(), 0.001);
        assertEquals(44.0f, outcome.record().getMemoryUsage(), 0.001);
        assertTrue(outcome.record().isCpuStale());
        assertTrue(outcome.record().isMemoryStale());
    }

    // ── Sweep ────────────────────────────────────────────────────────────────

    @Test
    void sweepReportsTransitionsForLoggingOutsideTheLock() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = registryAt(clock);
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        clock.set(100_000L);
        List<NodeRegistry.StatusTransition> transitions = registry.sweepExpired(6_000L);

        assertEquals(1, transitions.size());
        assertEquals("node1", transitions.get(0).nodeId());
        assertEquals(NodeStatus.ALIVE, transitions.get(0).from());
        assertEquals(NodeStatus.SUSPECTED_DEAD, transitions.get(0).to());
    }

    @Test
    void repeatedSweepsDoNotReportTheSameTransitionTwice() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = registryAt(clock);
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        clock.set(100_000L);
        registry.sweepExpired(6_000L);
        List<NodeRegistry.StatusTransition> second = registry.sweepExpired(6_000L);

        assertTrue(second.isEmpty(), "a stable state must not re-emit transitions");
    }

    // ── Deregistration ───────────────────────────────────────────────────────

    @Test
    void deregisterRemovesTheEntry() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        assertTrue(registry.deregister("node1", false));
        assertEquals(0, registry.size());
    }

    @Test
    void deregisterUnknownNodeReturnsFalse() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));

        assertFalse(registry.deregister("ghost", false));
    }

    @Test
    void drainKeepsTheEntryButRemovesItFromScheduling() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        assertTrue(registry.deregister("node1", true));
        assertEquals(1, registry.size());
        assertEquals(NodeStatus.DRAINING, registry.get("node1").orElseThrow().getStatus());
        assertTrue(registry.aliveSnapshot().isEmpty());
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    @Test
    void snapshotIsSortedByNodeIdRegardlessOfInsertionOrder() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        for (String id : new String[]{"zeta", "alpha", "mike"}) {
            registry.register(id, "10.0.0.1", 1, id, caps(1), "v");
        }

        List<NodeRecord> snapshot = registry.snapshot();

        // A stable order is what makes identity-based scheduling meaningful; ConcurrentHashMap's own
        // iteration order is unspecified and changes on resize.
        assertEquals(List.of("alpha", "mike", "zeta"),
                snapshot.stream().map(NodeRecord::getNodeId).toList());
    }

    @Test
    void snapshotIsUnmodifiable() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
    }

    @Test
    void statusCountsReportsEveryStatusIncludingZeros() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        var counts = registry.statusCounts();

        assertEquals(1, counts.get(NodeStatus.ALIVE));
        assertEquals(0, counts.get(NodeStatus.SUSPECTED_DEAD));
        assertEquals(NodeStatus.values().length, counts.size());
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    void concurrentRegisterAndHeartbeatNeverLoseTheNodeOrDuplicateIt() throws Exception {
        // Regression test for the lost-update race. Previously registerNode did an unconditional
        // put() of a brand-new record, so a concurrent heartbeat could write into the object that
        // put() had just orphaned — the heartbeat vanished and the node was later declared dead
        // despite an unbroken heartbeat stream.
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");

        int iterations = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);

        Runnable registerLoop = () -> {
            awaitQuietly(start);
            for (int i = 0; i < iterations; i++) {
                registry.register("node1", "10.0.0.1", 1, "h", caps(1), "v");
            }
        };
        Runnable heartbeatLoop = () -> {
            awaitQuietly(start);
            for (int i = 0; i < iterations; i++) {
                registry.recordHeartbeat("node1", 50f, true, 50f, true, i);
            }
        };
        Runnable sweepLoop = () -> {
            awaitQuietly(start);
            for (int i = 0; i < iterations; i++) {
                registry.sweepExpired(6_000L);
            }
        };
        Runnable readLoop = () -> {
            awaitQuietly(start);
            for (int i = 0; i < iterations; i++) {
                registry.snapshot();
                registry.aliveSnapshot();
                registry.statusCounts();
            }
        };

        pool.submit(registerLoop);
        pool.submit(heartbeatLoop);
        pool.submit(sweepLoop);
        pool.submit(readLoop);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "workers did not finish");

        assertEquals(1, registry.size(), "concurrent register/heartbeat must not duplicate a node");
        NodeRecord record = registry.get("node1").orElseThrow();
        assertEquals(50f, record.getCpuUsage(), 0.001,
                "the last heartbeat's reading must survive concurrent registration");
    }

    @Test
    @Timeout(30)
    void concurrentRegistrationOfManyDistinctNodesRegistersEachExactlyOnce() throws Exception {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        int nodeCount = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < nodeCount; i++) {
            String id = "node-" + i;
            pool.submit(() -> {
                awaitQuietly(start);
                registry.register(id, "10.0.0.1", 1, id, caps(1), "v");
                registry.recordHeartbeat(id, 10f, true, 10f, true, 1);
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));

        assertEquals(nodeCount, registry.size());
        assertEquals(nodeCount, registry.aliveSnapshot().size());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Risk (Stage D) ──────────────────────────────────────────────────────

    @Test
    void updateRiskAppliesTheAssessmentToTheRecord() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = registryAt(clock);
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(4), "1.0.0");
        clock.set(5_000L);

        registry.updateRisk("node1", 0.75, true, List.of("low battery"));

        NodeRecord record = registry.get("node1").orElseThrow();
        assertEquals(0.75, record.getRiskScore(), 0.001);
        assertTrue(record.isAtRisk());
        assertEquals(List.of("low battery"), record.getRiskReasons());
        assertEquals(5_000L, record.getRiskAssessedAtMillis());
    }

    @Test
    void updateRiskReturnsTheRisingEdgeTransition() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(4), "1.0.0");

        NodeRegistry.RiskTransition first = registry.updateRisk("node1", 0.6, true, List.of("reason"))
                .orElseThrow();
        assertTrue(first.risingEdge());
        assertFalse(first.wasAtRisk());
        assertTrue(first.isAtRisk());

        NodeRegistry.RiskTransition second = registry.updateRisk("node1", 0.6, true, List.of("reason"))
                .orElseThrow();
        assertFalse(second.risingEdge(), "already at risk — this is not a NEW rising edge");
    }

    @Test
    void updateRiskOnAnUnknownNodeReturnsEmpty() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));

        assertTrue(registry.updateRisk("ghost", 0.9, true, List.of()).isEmpty());
    }

    @Test
    void aFreshlyRegisteredNodeStartsNotAtRiskWithNoAssessmentYet() {
        NodeRegistry registry = registryAt(new AtomicLong(1_000L));
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(4), "1.0.0");

        NodeRecord record = registry.get("node1").orElseThrow();
        assertFalse(record.isAtRisk());
        assertEquals(0.0, record.getRiskScore());
        assertEquals(0L, record.getRiskAssessedAtMillis());
    }

    @Test
    void riskFieldsSurviveAHeartbeatUnchanged() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = registryAt(clock);
        registry.register("node1", "10.0.0.1", 50051, "host1", caps(4), "1.0.0");
        registry.updateRisk("node1", 0.8, true, List.of("at risk"));

        registry.recordHeartbeat("node1", 50f, true, 60f, true, 1);

        NodeRecord record = registry.get("node1").orElseThrow();
        assertTrue(record.isAtRisk(), "an unrelated heartbeat must not silently clear risk state");
        assertEquals(0.8, record.getRiskScore(), 0.001);
    }
}
