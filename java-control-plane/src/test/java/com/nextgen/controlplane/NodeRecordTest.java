package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeRecord — the core data structure for the node registry.
 *
 * <p>NodeRecord is immutable, so these exercise the copy-on-write mutators. The scenarios are the
 * same ones the mutable version was tested against; what changed is that each mutation returns a new
 * record instead of writing through a shared reference.
 */
class NodeRecordTest {

    @Test
    void testConstructorInitialization() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");

        assertEquals("node1", record.getNodeId());
        assertEquals("192.168.1.1", record.getIp());
        assertEquals(50051, record.getPort());
        assertEquals("host1", record.getHostname());
        assertEquals(0.0f, record.getCpuUsage(), 0.001);
        assertEquals(0.0f, record.getMemoryUsage(), 0.001);
        assertEquals(NodeStatus.ALIVE, record.getStatus());
        assertEquals("ALIVE", record.getStatusName());
        assertTrue(record.getLastHeartbeatMillis() > 0);
    }

    @Test
    void testFreshNodeHasNoReadingsYetAndSaysSo() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");

        // A node that has never sent a heartbeat has no measurement. Reporting 0% as if it were a
        // real "idle" reading is exactly the fake-data failure this flag exists to prevent.
        assertTrue(record.isCpuStale());
        assertTrue(record.isMemoryStale());
    }

    @Test
    void testCpuUsageUpdate() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");

        NodeRecord updated = record.withHeartbeat(45.5f, true, 0f, true, 1_000L, 1);
        assertEquals(45.5f, updated.getCpuUsage(), 0.001);
        assertFalse(updated.isCpuStale());

        NodeRecord again = updated.withHeartbeat(99.9f, true, 0f, true, 2_000L, 2);
        assertEquals(99.9f, again.getCpuUsage(), 0.001);

        // The original snapshot is untouched — that immutability is what removes the lost-update race.
        assertEquals(0.0f, record.getCpuUsage(), 0.001);
    }

    @Test
    void testMemoryUsageUpdate() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(0f, true, 78.3f, true, 1_000L, 1);

        assertEquals(78.3f, record.getMemoryUsage(), 0.001);
        assertFalse(record.isMemoryStale());
    }

    @Test
    void testUnavailableReadingPreservesLastKnownValueAndFlagsIt() {
        NodeRecord measured = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(62.0f, true, 71.0f, true, 1_000L, 1);

        // The agent could not read CPU this beat. The previous value is retained so history is not
        // lost, but it is marked stale so no consumer presents it as current.
        NodeRecord degraded = measured.withHeartbeat(0f, false, 71.0f, true, 2_000L, 2);

        assertEquals(62.0f, degraded.getCpuUsage(), 0.001, "last known value must be preserved");
        assertTrue(degraded.isCpuStale());
        assertFalse(degraded.isMemoryStale());
    }

    @Test
    void testStatusTransitions() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        assertEquals(NodeStatus.ALIVE, record.getStatus());

        NodeRecord dead = record.withStatus(NodeStatus.SUSPECTED_DEAD);
        assertEquals(NodeStatus.SUSPECTED_DEAD, dead.getStatus());
        assertEquals("SUSPECTED_DEAD", dead.getStatusName());

        NodeRecord revived = dead.withStatus(NodeStatus.ALIVE);
        assertEquals(NodeStatus.ALIVE, revived.getStatus());
    }

    @Test
    void testWithStatusReturnsSameInstanceWhenUnchanged() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        assertSame(record, record.withStatus(NodeStatus.ALIVE));
    }

    @Test
    void testHeartbeatRestoresAliveFromSuspectedDead() {
        NodeRecord dead = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withStatus(NodeStatus.SUSPECTED_DEAD);

        NodeRecord revived = dead.withHeartbeat(10f, true, 20f, true, 5_000L, 1);

        assertEquals(NodeStatus.ALIVE, revived.getStatus());
    }

    @Test
    void testHeartbeatDoesNotResurrectDeregisteredNode() {
        NodeRecord gone = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withStatus(NodeStatus.DEREGISTERED);

        NodeRecord afterBeat = gone.withHeartbeat(10f, true, 20f, true, 5_000L, 1);

        // A deliberate removal must not be undone by a stray heartbeat; only an explicit
        // re-registration may bring the node back.
        assertEquals(NodeStatus.DEREGISTERED, afterBeat.getStatus());
    }

    @Test
    void testHeartbeatTimestampUpdate() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        long initialTime = record.getLastHeartbeatMillis();

        long newTime = initialTime + 5_000L;
        NodeRecord updated = record.withHeartbeat(0f, true, 0f, true, newTime, 1);

        assertEquals(newTime, updated.getLastHeartbeatMillis());
        assertTrue(updated.getLastHeartbeatMillis() >= initialTime);
    }

    @Test
    void testRegistrationCarriesForwardTelemetryButUpdatesAddress() {
        NodeRecord measured = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(55.0f, true, 66.0f, true, 1_000L, 7);

        NodeRecord reregistered = measured.withRegistration(
                "10.0.0.9", 50052, "host1-moved",
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "2.0.0", 9_000L);

        // Address may legitimately change (DHCP). Live readings must NOT be reset to a phantom 0%,
        // which is what the previous unconditional replacement did on every reconnect.
        assertEquals("10.0.0.9", reregistered.getIp());
        assertEquals(50052, reregistered.getPort());
        assertEquals("host1-moved", reregistered.getHostname());
        assertEquals(55.0f, reregistered.getCpuUsage(), 0.001);
        assertEquals(66.0f, reregistered.getMemoryUsage(), 0.001);
        assertEquals("2.0.0", reregistered.getAgentVersion());
        assertEquals(NodeStatus.ALIVE, reregistered.getStatus());
    }

    @Test
    void testToStringFormat() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(50.0f, true, 60.0f, true, 1_000L, 1);

        String str = record.toString();
        assertTrue(str.contains("node1"));
        assertTrue(str.contains("192.168.1.1"));
        assertTrue(str.contains("cpu=50.0"));
        assertTrue(str.contains("mem=60.0"));
        assertTrue(str.contains("status=ALIVE"));
    }

    @Test
    void testToStringRendersUnavailableReadingAsNotAvailable() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");

        String str = record.toString();
        assertTrue(str.contains("cpu=n/a"), "unmeasured CPU must not render as a number: " + str);
        assertTrue(str.contains("mem=n/a"), "unmeasured memory must not render as a number: " + str);
    }

    @Test
    void testProtoProjectionCarriesStatusAndStaleness() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(33.0f, true, 0f, false, 4_000L, 3)
                .withStatus(NodeStatus.SUSPECTED_DEAD);

        ControlPlaneProto.NodeInfo info = record.toProto();

        assertEquals("node1", info.getNodeId());
        assertEquals(ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD, info.getStatus());
        assertEquals(4_000L, info.getLastHeartbeatEpochMillis());
        assertFalse(info.getCpuStale());
        assertTrue(info.getMemoryStale());
    }

    // ── Risk (Stage D) ──────────────────────────────────────────────────────

    @Test
    void freshNodeStartsNotAtRiskWithZeroScoreAndNoAssessment() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");

        assertFalse(record.isAtRisk());
        assertEquals(0.0, record.getRiskScore());
        assertTrue(record.getRiskReasons().isEmpty());
        assertEquals(0L, record.getRiskAssessedAtMillis());
    }

    @Test
    void withRiskAppliesTheAssessment() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withRisk(0.75, true, java.util.List.of("low battery", "rising RTT"), 5_000L);

        assertTrue(record.isAtRisk());
        assertEquals(0.75, record.getRiskScore(), 0.001);
        assertEquals(java.util.List.of("low battery", "rising RTT"), record.getRiskReasons());
        assertEquals(5_000L, record.getRiskAssessedAtMillis());
    }

    @Test
    void riskFieldsSurviveAHeartbeatStatusAndRegistrationUnchanged() {
        NodeRecord atRisk = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withRisk(0.9, true, java.util.List.of("reason"), 5_000L);

        NodeRecord afterHeartbeat = atRisk.withHeartbeat(10f, true, 20f, true, 6_000L, 1);
        assertTrue(afterHeartbeat.isAtRisk(), "an unrelated heartbeat must not clear risk state");
        assertEquals(0.9, afterHeartbeat.getRiskScore(), 0.001);

        NodeRecord afterStatus = atRisk.withStatus(NodeStatus.DRAINING);
        assertTrue(afterStatus.isAtRisk());

        NodeRecord afterRegistration = atRisk.withRegistration(
                "10.0.0.2", 50052, "newhost", null, "2.0.0", 7_000L);
        assertTrue(afterRegistration.isAtRisk(), "risk must survive a re-registration too");
    }

    @Test
    void toProtoCarriesRiskFields() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withRisk(0.6, true, java.util.List.of("reason"), 5_000L);

        ControlPlaneProto.NodeInfo info = record.toProto();

        assertEquals(0.6f, info.getRiskScore(), 0.001);
        assertTrue(info.getAtRisk());
        assertEquals(5_000L, info.getRiskAssessedAtEpochMillis());
    }
}
