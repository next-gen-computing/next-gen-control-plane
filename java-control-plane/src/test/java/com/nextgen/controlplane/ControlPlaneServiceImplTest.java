package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ControlPlaneServiceImpl using an in-process gRPC server.
 */
class ControlPlaneServiceImplTest {

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub;
    private ConcurrentHashMap<String, NodeRecord> registry;
    private ControlPlaneServiceImpl service;

    /** A heartbeat that declares both readings genuine, as a healthy agent sends. */
    private static ControlPlaneProto.HeartbeatRequest heartbeat(String nodeId, float cpu, float memory) {
        return ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setCpu(cpu)
                .setCpuAvailable(true)
                .setMemory(memory)
                .setMemoryAvailable(true)
                .build();
    }

    private static ControlPlaneProto.NodeInfo node(String nodeId, String ip, String hostname) {
        return ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setIp(ip)
                .setPort(50051)
                .setHostname(hostname)
                .build();
    }

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        registry = new ConcurrentHashMap<>();
        service = new ControlPlaneServiceImpl(registry);

        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();

        channel = InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build();

        stub = ControlPlaneServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void testRegisterNode() {
        ControlPlaneProto.RegisterResponse response =
                stub.registerNode(node("node1", "192.168.1.1", "host1"));

        assertEquals("REGISTERED", response.getStatus());
        assertEquals("node1", response.getAssignedId());
        assertFalse(response.getResumedExisting());
        assertTrue(registry.containsKey("node1"));
        assertEquals("host1", registry.get("node1").getHostname());
    }

    @Test
    void testSendHeartbeatKnownNode() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));

        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(heartbeat("node1", 45.5f, 78.3f));

        assertEquals("OK", response.getStatus());
        assertFalse(response.getReregistrationRequired());
        assertEquals(45.5f, registry.get("node1").getCpuUsage(), 0.001);
        assertEquals(78.3f, registry.get("node1").getMemoryUsage(), 0.001);
        assertFalse(registry.get("node1").isCpuStale());
    }

    @Test
    void testHeartbeatWithUnavailableCpuDoesNotRecordAValue() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.sendHeartbeat(heartbeat("node1", 62.0f, 70.0f));

        // The agent could not read CPU. It sends cpu=0 with cpu_available=false; the control plane
        // must NOT store that 0 as a reading.
        stub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId("node1")
                .setCpu(0f)
                .setCpuAvailable(false)
                .setMemory(70.0f)
                .setMemoryAvailable(true)
                .build());

        NodeRecord record = registry.get("node1");
        assertEquals(62.0f, record.getCpuUsage(), 0.001, "last known reading must be preserved");
        assertTrue(record.isCpuStale(), "the reading must be flagged as not current");
    }

    @Test
    void testSendHeartbeatUnknownNode() {
        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(heartbeat("unknown", 50.0f, 60.0f));

        assertEquals("UNKNOWN_NODE", response.getStatus());
        // The agent must be told to re-register rather than beating into a registry that will never
        // accept it — this is what previously deadlocked a node after a control-plane restart.
        assertTrue(response.getReregistrationRequired());
        assertFalse(registry.containsKey("unknown"), "a heartbeat must never auto-create a node");
    }

    @Test
    void testGetNodes() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.registerNode(node("node2", "192.168.1.2", "host2"));

        ControlPlaneProto.NodeList nodeList = stub.getNodes(ControlPlaneProto.Empty.newBuilder().build());

        assertEquals(2, nodeList.getNodesCount());
        assertTrue(nodeList.getNodesList().stream().anyMatch(n -> n.getNodeId().equals("node1")));
        assertTrue(nodeList.getNodesList().stream().anyMatch(n -> n.getNodeId().equals("node2")));
    }

    @Test
    void testGetNodesCarriesLivenessStatus() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        registry.computeIfPresent("node1", (k, v) -> v.withStatus(NodeStatus.SUSPECTED_DEAD));

        ControlPlaneProto.NodeList nodeList = stub.getNodes(ControlPlaneProto.Empty.newBuilder().build());

        // Before this change NodeInfo had no status field at all, so every gRPC consumer had to
        // assume everything it received was healthy.
        assertEquals(ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD,
                nodeList.getNodes(0).getStatus());
    }

    @Test
    void testSubmitTaskWithNoAliveNodes() {
        ControlPlaneProto.TaskResponse response = stub.submitTask(
                ControlPlaneProto.TaskRequest.newBuilder()
                        .setTaskId("task1")
                        .setPayload("test payload")
                        .build());

        assertEquals("NONE", response.getAssignedNode());
        assertTrue(response.getResult().contains("FAILED"));
    }

    @Test
    void testSubmitTaskRoundRobin() {
        for (String nodeId : new String[]{"node1", "node2"}) {
            stub.registerNode(node(nodeId, "192.168.1.1", nodeId));
            stub.sendHeartbeat(heartbeat(nodeId, 50.0f, 60.0f));
        }

        String[] assignments = new String[4];
        for (int i = 0; i < 4; i++) {
            assignments[i] = stub.submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                    .setTaskId("task" + i)
                    .setPayload("payload" + i)
                    .build()).getAssignedNode();
        }

        assertNotEquals(assignments[0], assignments[1]);
        assertNotEquals(assignments[1], assignments[2]);
        assertNotEquals(assignments[2], assignments[3]);
        assertEquals(assignments[0], assignments[2]);
        assertEquals(assignments[1], assignments[3]);
    }

    @Test
    void testSuspectedDeadNodeStopsReceivingTasks() {
        for (String nodeId : new String[]{"node1", "node2"}) {
            stub.registerNode(node(nodeId, "192.168.1.1", nodeId));
            stub.sendHeartbeat(heartbeat(nodeId, 50.0f, 60.0f));
        }

        registry.computeIfPresent("node2", (k, v) -> v.withStatus(NodeStatus.SUSPECTED_DEAD));

        for (int i = 0; i < 6; i++) {
            String assigned = stub.submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                    .setTaskId("task" + i).build()).getAssignedNode();
            assertEquals("node1", assigned, "work must never route to a node marked dead");
        }
    }

    @Test
    void testReRegistrationDoesNotDuplicateOrResetTheNode() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.sendHeartbeat(heartbeat("node1", 42.0f, 55.0f));

        // The node drops out, is marked dead, then comes back and registers again.
        registry.computeIfPresent("node1", (k, v) -> v.withStatus(NodeStatus.SUSPECTED_DEAD));
        ControlPlaneProto.RegisterResponse response =
                stub.registerNode(node("node1", "192.168.1.99", "host1"));

        assertTrue(response.getResumedExisting());
        assertEquals(1, registry.size(), "a reconnect must not create a duplicate entry");

        NodeRecord record = registry.get("node1");
        assertEquals(NodeStatus.ALIVE, record.getStatus(), "the node must be re-integrated as alive");
        assertEquals("192.168.1.99", record.getIp(), "a new address must be picked up");
        assertEquals(42.0f, record.getCpuUsage(), 0.001,
                "re-registration must not wipe live telemetry back to a phantom 0%");
    }

    @Test
    void testDeregisterRemovesNodeAndStopsScheduling() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.sendHeartbeat(heartbeat("node1", 10f, 10f));

        ControlPlaneProto.DeregisterResponse response = stub.deregisterNode(
                ControlPlaneProto.DeregisterRequest.newBuilder()
                        .setNodeId("node1").setReason("shutting down").build());

        assertTrue(response.getAccepted());
        assertFalse(registry.containsKey("node1"));
        assertEquals("NONE", stub.submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                .setTaskId("t").build()).getAssignedNode());
    }

    @Test
    void testDeregisterUnknownNodeIsRejectedNotSilentlyAccepted() {
        ControlPlaneProto.DeregisterResponse response = stub.deregisterNode(
                ControlPlaneProto.DeregisterRequest.newBuilder().setNodeId("ghost").build());

        assertFalse(response.getAccepted());
        assertEquals("UNKNOWN_NODE", response.getMessage());
    }

    @Test
    void testDrainKeepsTheRecordButStopsScheduling() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.sendHeartbeat(heartbeat("node1", 10f, 10f));

        stub.deregisterNode(ControlPlaneProto.DeregisterRequest.newBuilder()
                .setNodeId("node1").setDrain(true).build());

        assertTrue(registry.containsKey("node1"), "draining keeps the record visible");
        assertEquals(NodeStatus.DRAINING, registry.get("node1").getStatus());
        assertEquals("NONE", stub.submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                .setTaskId("t").build()).getAssignedNode());
    }

    // ── NodeHistory (Stage C: trend history + power signals) ──────────────────

    @Test
    void heartbeatWithPowerSignalsLandsInNodeHistory() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));

        stub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId("node1")
                .setCpu(55.0f).setCpuAvailable(true)
                .setMemory(65.0f).setMemoryAvailable(true)
                .setBatteryPercent(42.0f).setBatteryAvailable(true)
                .setCharging(true).setChargingKnown(true)
                .setOnAcPower(true).setOnAcPowerKnown(true)
                .setPreviousRttSeconds(0.015).setPreviousRttAvailable(true)
                .build());

        NodeHistory.Sample sample = service.nodeHistory().latest("node1").orElseThrow();
        assertEquals(55.0f, sample.cpuPercent(), 0.01);
        assertEquals(42.0f, sample.batteryPercent(), 0.01);
        assertTrue(sample.batteryAvailable());
        assertTrue(sample.charging());
        assertTrue(sample.chargingKnown());
        assertTrue(sample.onAcPower());
        assertEquals(0.015, sample.previousRttSeconds(), 0.0001);
        assertTrue(sample.previousRttAvailable());
    }

    @Test
    void heartbeatWithNoBatteryLandsAsUnavailableNotFabricated() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));

        // A desktop machine's real heartbeat: no battery fields set at all (proto defaults).
        stub.sendHeartbeat(heartbeat("node1", 10f, 10f));

        NodeHistory.Sample sample = service.nodeHistory().latest("node1").orElseThrow();
        assertFalse(sample.batteryAvailable());
        assertFalse(sample.chargingKnown());
        assertFalse(sample.onAcPowerKnown());
    }

    @Test
    void getNodesReflectsTheLatestPowerReading() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId("node1")
                .setCpu(10f).setCpuAvailable(true)
                .setMemory(10f).setMemoryAvailable(true)
                .setBatteryPercent(88.0f).setBatteryAvailable(true)
                .setCharging(false).setChargingKnown(true)
                .setOnAcPower(false).setOnAcPowerKnown(true)
                .build());

        ControlPlaneProto.NodeInfo info = stub.getNodes(ControlPlaneProto.Empty.newBuilder().build())
                .getNodes(0);

        assertEquals(88.0f, info.getBatteryPercent(), 0.01);
        assertTrue(info.getBatteryAvailable());
        assertTrue(info.getChargingKnown());
        assertFalse(info.getCharging());
    }

    @Test
    void getNodesForANodeWithNoHeartbeatYetLeavesPowerFieldsAtDefaults() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));

        ControlPlaneProto.NodeInfo info = stub.getNodes(ControlPlaneProto.Empty.newBuilder().build())
                .getNodes(0);

        assertFalse(info.getBatteryAvailable());
        assertFalse(info.getChargingKnown());
    }

    @Test
    void deregisterForgetsNodeHistoryButDrainDoesNot() {
        stub.registerNode(node("node1", "192.168.1.1", "host1"));
        stub.sendHeartbeat(heartbeat("node1", 10f, 10f));
        stub.registerNode(node("node2", "192.168.1.2", "host2"));
        stub.sendHeartbeat(heartbeat("node2", 10f, 10f));

        stub.deregisterNode(ControlPlaneProto.DeregisterRequest.newBuilder()
                .setNodeId("node1").build()); // real removal
        stub.deregisterNode(ControlPlaneProto.DeregisterRequest.newBuilder()
                .setNodeId("node2").setDrain(true).build()); // drain only

        assertTrue(service.nodeHistory().recent("node1").isEmpty(),
                "a truly removed node's history must not resurface if its id is reused");
        assertFalse(service.nodeHistory().recent("node2").isEmpty(),
                "draining keeps history visible, matching how it keeps the rest of the record visible");
    }
}
