package com.nextgen.controlplane;

import com.nextgen.controlplane.risk.RiskScorer;
import com.nextgen.controlplane.task.MigrationTrigger;
import com.nextgen.controlplane.training.RiskSnapshotLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RiskMonitor — mirrors {@link HeartbeatMonitorTest}'s style: injected clock (via
 * {@link NodeRegistry}), fake collaborators, deterministic single sweeps via the package-private
 * {@code checkRisk()}. Uses a fake {@link RiskScorer} throughout so these tests are about RiskMonitor's
 * OWN behaviour (rising-edge detection, dispatching to the migration trigger) — the scorer's actual
 * rules have their own dedicated coverage in {@code RuleBasedRiskScorerTest}.
 */
class RiskMonitorTest {

    private static NodeRegistry registryWithAliveNode(String nodeId) {
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        map.put(nodeId, NodeRecord.fresh(nodeId, "10.0.0.1", 50051, "host", null, "v", 1000L));
        return new NodeRegistry(map, () -> 2000L);
    }

    /** A scorer that always returns the same fixed assessment, regardless of node/history. */
    private static RiskScorer fixedScorer(boolean atRisk, double score, String... reasons) {
        RiskScorer.RiskAssessment assessment = new RiskScorer.RiskAssessment(score, atRisk, List.of(reasons));
        return (node, history) -> assessment;
    }

    private static final class RecordingMigrationTrigger implements MigrationTrigger {
        final List<String> migratedNodeIds = new ArrayList<>();

        @Override
        public void migrateAwayFrom(String nodeId, String reason) {
            migratedNodeIds.add(nodeId);
        }
    }

    /** Records exactly what it was called with — a real object, not a mock. */
    private static final class RecordingAlertNotifier implements com.nextgen.controlplane.alert.AlertNotifier {
        final List<String> atRiskCalls = new ArrayList<>();

        @Override public void notifyNodeDown(String nodeId, String reason) { }
        @Override public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
            atRiskCalls.add(nodeId + ":" + riskScore);
        }
    }

    // ── Stage GG: alert-notifier wiring ──────────────────────────────────────

    @Test
    void alertNotifierFiresExactlyOnceOnTheRisingEdge() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RecordingAlertNotifier notifier = new RecordingAlertNotifier();
        RiskMonitor monitor = new RiskMonitor(registry, new NodeHistory(),
                fixedScorer(true, 0.8, "test reason"), trigger, 1000L, null, () -> true, notifier);

        monitor.checkRisk();
        monitor.checkRisk();
        monitor.checkRisk();

        assertEquals(1, notifier.atRiskCalls.size(),
                "only the false->true edge should fire an alert, not every sweep");
        assertEquals("node1:0.8", notifier.atRiskCalls.get(0));
    }

    @Test
    void alertNotifierDoesNotFireWhenNeverAtRisk() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RecordingAlertNotifier notifier = new RecordingAlertNotifier();
        RiskMonitor monitor = new RiskMonitor(registry, new NodeHistory(),
                fixedScorer(false, 0.1), trigger, 1000L, null, () -> true, notifier);

        monitor.checkRisk();

        assertTrue(notifier.atRiskCalls.isEmpty());
    }

    @Test
    void aNullAlertNotifierIsSafeAndMatchesEveryOtherConstructorsBehavior() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RiskMonitor monitor = new RiskMonitor(registry, new NodeHistory(),
                fixedScorer(true, 0.8, "reason"), trigger, 1000L, null, () -> true, null);

        assertDoesNotThrow(monitor::checkRisk);
    }

    @Test
    void aNodeCrossingIntoAtRiskTriggersExactlyOneMigration() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RiskMonitor monitor = new RiskMonitor(
                registry, new NodeHistory(), fixedScorer(true, 0.8, "test reason"), trigger, 1000L);

        monitor.checkRisk();

        assertEquals(List.of("node1"), trigger.migratedNodeIds);
        assertTrue(registry.get("node1").orElseThrow().isAtRisk());
        assertEquals(0.8, registry.get("node1").orElseThrow().getRiskScore(), 0.001);
    }

    @Test
    void repeatedSweepsWhileStillAtRiskDoNotRepeatMigration() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RiskMonitor monitor = new RiskMonitor(
                registry, new NodeHistory(), fixedScorer(true, 0.8, "still at risk"), trigger, 1000L);

        monitor.checkRisk();
        monitor.checkRisk();
        monitor.checkRisk();

        assertEquals(1, trigger.migratedNodeIds.size(),
                "only the false->true edge should trigger a migration, not every sweep");
    }

    @Test
    void aNodeThatNeverCrossesTheThresholdNeverTriggersMigration() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RiskMonitor monitor = new RiskMonitor(registry, new NodeHistory(), fixedScorer(false, 0.1), trigger, 1000L);

        monitor.checkRisk();

        assertTrue(trigger.migratedNodeIds.isEmpty());
        assertFalse(registry.get("node1").orElseThrow().isAtRisk());
    }

    @Test
    void recoveringThenBecomingAtRiskAgainTriggersASecondMigration() {
        NodeRegistry registry = registryWithAliveNode("node1");
        NodeHistory history = new NodeHistory();
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();

        RiskMonitor riskyMonitor = new RiskMonitor(
                registry, history, fixedScorer(true, 0.8, "risk"), trigger, 1000L);
        RiskMonitor healthyMonitor = new RiskMonitor(
                registry, history, fixedScorer(false, 0.0), trigger, 1000L);

        riskyMonitor.checkRisk();   // rising edge #1
        healthyMonitor.checkRisk(); // recovers
        riskyMonitor.checkRisk();   // rising edge #2

        assertEquals(List.of("node1", "node1"), trigger.migratedNodeIds);
    }

    @Test
    void onlyAliveNodesAreScored() {
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        map.put("dead1", NodeRecord.fresh("dead1", "10.0.0.1", 50051, "host", null, "v", 1000L)
                .withStatus(NodeStatus.SUSPECTED_DEAD));
        NodeRegistry registry = new NodeRegistry(map, () -> 2000L);
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RiskMonitor monitor = new RiskMonitor(
                registry, new NodeHistory(), fixedScorer(true, 0.9, "risk"), trigger, 1000L);

        monitor.checkRisk();

        assertTrue(trigger.migratedNodeIds.isEmpty(), "a dead node must never be proactively migrated");
    }

    @Test
    void assessmentReasonsAreRecordedOnTheNodeRecord() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RecordingMigrationTrigger trigger = new RecordingMigrationTrigger();
        RiskMonitor monitor = new RiskMonitor(registry, new NodeHistory(),
                fixedScorer(true, 0.5, "low battery", "rising RTT"), trigger, 1000L);

        monitor.checkRisk();

        assertEquals(List.of("low battery", "rising RTT"), registry.get("node1").orElseThrow().getRiskReasons());
    }

    // ── Stage G: opt-in snapshot logging ────────────────────────────────────

    @Test
    void snapshotLoggerFiresOnEverySweepWhenProvided(@TempDir Path tempDir) throws IOException {
        NodeRegistry registry = registryWithAliveNode("node1");
        Path outputFile = tempDir.resolve("snapshots.jsonl");
        RiskMonitor monitor = new RiskMonitor(registry, new NodeHistory(), fixedScorer(false, 0.1),
                (nodeId, reason) -> { }, 1000L, new RiskSnapshotLogger(outputFile));

        monitor.checkRisk();
        monitor.checkRisk();

        assertEquals(2, Files.readAllLines(outputFile).size(),
                "the snapshot logger must fire on every sweep, not just risk transitions");
    }

    @Test
    void legacyFiveArgConstructorNeverLogsSnapshots() {
        NodeRegistry registry = registryWithAliveNode("node1");
        RiskMonitor monitor = new RiskMonitor(
                registry, new NodeHistory(), fixedScorer(false, 0.1), (nodeId, reason) -> { }, 1000L);

        assertDoesNotThrow(monitor::checkRisk, "the 5-arg constructor must work exactly as before, with no logger");
    }

    @Test
    @Timeout(2)
    void monitorShutsDownOnInterrupt() throws InterruptedException {
        NodeRegistry registry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);
        RiskMonitor monitor = new RiskMonitor(
                registry, new NodeHistory(), fixedScorer(false, 0.0), (nodeId, reason) -> { }, 500L);

        Thread monitorThread = new Thread(monitor);
        monitorThread.start();
        monitorThread.interrupt();
        monitorThread.join();

        assertFalse(monitorThread.isAlive());
    }
}
