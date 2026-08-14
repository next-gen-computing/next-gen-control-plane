package com.nextgen.controlplane;

import com.nextgen.controlplane.training.RiskOutcomeLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeartbeatMonitor — ensures nodes are marked dead after the timeout and restored when
 * heartbeats resume.
 *
 * <p>Records are immutable, so each assertion re-reads the current record from the registry rather
 * than holding a reference across the sweep.
 */
class HeartbeatMonitorTest {

    private static NodeRegistry registryWithNode(String nodeId, long lastHeartbeatMillis,
                                                 NodeStatus status, long nowMillis) {
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        NodeRecord record = NodeRecord
                .fresh(nodeId, "192.168.1.1", 50051, "host1", null, "test", lastHeartbeatMillis)
                .withStatus(status);
        map.put(nodeId, record);
        return new NodeRegistry(map, () -> nowMillis);
    }

    @Test
    void testNodeMarkedDeadAfterTimeout() {
        long now = 1_000_000L;
        // Last heartbeat 10s ago, well past the 6s timeout.
        NodeRegistry registry = registryWithNode("node1", now - 10_000L, NodeStatus.ALIVE, now);
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);

        monitor.checkHeartbeats();

        assertEquals(NodeStatus.SUSPECTED_DEAD, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    void testNodeRemainsAliveWithRecentHeartbeat() {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 500L, NodeStatus.ALIVE, now);
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);

        monitor.checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    void testDeadNodeRecovers() {
        long now = 1_000_000L;
        // Previously marked dead, but a heartbeat has since arrived.
        NodeRegistry registry = registryWithNode("node1", now - 100L, NodeStatus.SUSPECTED_DEAD, now);
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);

        monitor.checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    void testDeadNodeIsExcludedFromSchedulableSet() {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 10_000L, NodeStatus.ALIVE, now);
        new HeartbeatMonitor(registry).checkHeartbeats();

        // The acceptance requirement: once a node is SUSPECTED_DEAD, future work must not route to it.
        assertTrue(registry.aliveSnapshot().isEmpty(),
                "a SUSPECTED_DEAD node must not appear in the schedulable set");
    }

    @Test
    void testDrainingNodeIsNotOverriddenByLivenessSweep() {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 100L, NodeStatus.DRAINING, now);

        new HeartbeatMonitor(registry).checkHeartbeats();

        // DRAINING is an administrative decision; a fresh heartbeat must not silently undo it.
        assertEquals(NodeStatus.DRAINING, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    void testSweepDoesNotClobberAHeartbeatThatArrivedFirst() {
        // Regression test for the lost-update race: the monitor used to read a stale timestamp,
        // decide the node was dead, and write SUSPECTED_DEAD over an ALIVE status that a heartbeat
        // had already set — excluding a healthy node from scheduling for a whole check interval.
        AtomicLong clock = new AtomicLong(1_000_000L);
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        map.put("node1", NodeRecord.fresh("node1", "ip", 1, "h", null, "v",
                clock.get() - 10_000L));
        NodeRegistry registry = new NodeRegistry(map, clock::get);

        // The heartbeat lands before the sweep runs.
        registry.recordHeartbeat("node1", 10f, true, 20f, true, 1);
        new HeartbeatMonitor(registry).checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus(),
                "a heartbeat that already arrived must not be overwritten by the sweep");
    }

    @Test
    void testCustomTimeoutIsHonoured() {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 2_000L, NodeStatus.ALIVE, now);

        // 1s timeout: a 2s-old heartbeat is expired even though it is within the 6s default.
        new HeartbeatMonitor(registry, 1_000L, 500L).checkHeartbeats();

        assertEquals(NodeStatus.SUSPECTED_DEAD, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    void testLegacyMapConstructorStillObservesTheSameMap() {
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        NodeRecord stale = NodeRecord.fresh("node1", "ip", 1, "h", null, "v",
                System.currentTimeMillis() - 10_000L);
        map.put("node1", stale);

        new HeartbeatMonitor(map).checkHeartbeats();

        assertEquals(NodeStatus.SUSPECTED_DEAD, map.get("node1").getStatus());
    }

    // ── Stage E: outcome-logging wiring ─────────────────────────────────────

    @Test
    void outcomeLoggerWritesExactlyOneRecordOnAliveToSuspectedDeadTransition(@TempDir Path tempDir)
            throws IOException {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 10_000L, NodeStatus.ALIVE, now);
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger outcomeLogger = new RiskOutcomeLogger(outputFile);
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry, HeartbeatMonitor.DEFAULT_TIMEOUT_MS,
                HeartbeatMonitor.DEFAULT_CHECK_INTERVAL_MS, new NodeHistory(), outcomeLogger);

        monitor.checkHeartbeats();

        assertEquals(1, Files.readAllLines(outputFile).size());
    }

    @Test
    void outcomeLoggerDoesNotFireOnRecovery(@TempDir Path tempDir) throws IOException {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 100L, NodeStatus.SUSPECTED_DEAD, now);
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger outcomeLogger = new RiskOutcomeLogger(outputFile);
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry, HeartbeatMonitor.DEFAULT_TIMEOUT_MS,
                HeartbeatMonitor.DEFAULT_CHECK_INTERVAL_MS, null, outcomeLogger);

        monitor.checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
        assertFalse(Files.exists(outputFile), "recovery must not produce an outcome record");
    }

    @Test
    void outcomeLoggerDoesNotFireWhenNodeStaysAlive(@TempDir Path tempDir) throws IOException {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 500L, NodeStatus.ALIVE, now);
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger outcomeLogger = new RiskOutcomeLogger(outputFile);
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry, HeartbeatMonitor.DEFAULT_TIMEOUT_MS,
                HeartbeatMonitor.DEFAULT_CHECK_INTERVAL_MS, null, outcomeLogger);

        monitor.checkHeartbeats();

        assertFalse(Files.exists(outputFile), "a node that stays alive must not produce an outcome record");
    }

    @Test
    void legacyConstructorsStillWorkWithoutAnOutcomeLogger() {
        long now = 1_000_000L;
        NodeRegistry registry = registryWithNode("node1", now - 10_000L, NodeStatus.ALIVE, now);

        assertDoesNotThrow(() -> new HeartbeatMonitor(registry).checkHeartbeats());
        assertEquals(NodeStatus.SUSPECTED_DEAD, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    @Timeout(2)
    void testMonitorShutsDownOnInterrupt() throws InterruptedException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);

        Thread monitorThread = new Thread(monitor);
        monitorThread.start();
        monitorThread.interrupt();
        monitorThread.join();

        // Should terminate quickly on interrupt
        assertFalse(monitorThread.isAlive());
    }
}
