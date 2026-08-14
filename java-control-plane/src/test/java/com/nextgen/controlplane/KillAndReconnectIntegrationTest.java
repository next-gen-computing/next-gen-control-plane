package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of the Phase-1 acceptance criteria over a real gRPC transport
 * (in-process), driving the same code path a deployed cluster uses.
 *
 * <p>Covers the full failure lifecycle: three nodes register and are scheduled fairly; one node is
 * killed and is marked SUSPECTED_DEAD within the timeout window; work stops routing to it; and it
 * rejoins cleanly without duplicate or zombie entries.
 *
 * <p>Time is injected rather than slept, so the timeout behaviour is asserted deterministically
 * instead of being raced against a wall clock.
 */
class KillAndReconnectIntegrationTest {

    private static final long HEARTBEAT_TIMEOUT_MS = 6_000L;

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub;
    private ConcurrentHashMap<String, NodeRecord> backingMap;
    private NodeRegistry registry;
    private HeartbeatMonitor monitor;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws IOException {
        clock = new AtomicLong(1_000_000L);
        backingMap = new ConcurrentHashMap<>();
        registry = new NodeRegistry(backingMap, clock::get);
        monitor = new HeartbeatMonitor(registry, HEARTBEAT_TIMEOUT_MS, 3_000L);

        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new ControlPlaneServiceImpl(registry, new RoundRobinScheduler()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = ControlPlaneServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void register(String nodeId) {
        stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setIp("10.0.0." + nodeId.charAt(nodeId.length() - 1))
                .setPort(50051)
                .setHostname(nodeId)
                .setAgentVersion("1.0.0")
                .setCapabilities(ControlPlaneProto.NodeCapabilities.newBuilder()
                        .setCpuCores(4).setTotalMemoryBytes(8L << 30).build())
                .build());
    }

    private void heartbeat(String nodeId, float cpu, float memory) {
        stub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setCpu(cpu).setCpuAvailable(true)
                .setMemory(memory).setMemoryAvailable(true)
                .setClientSendEpochMillis(clock.get())
                .build());
    }

    private String submit(String taskId) {
        return stub.submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                .setTaskId(taskId).setPayload("payload").build()).getAssignedNode();
    }

    @Test
    @DisplayName("3 nodes register, heartbeat real values, and are scheduled evenly")
    void threeNodeClusterSchedulesEvenly() {
        register("node1");
        register("node2");
        register("node3");
        heartbeat("node1", 11.0f, 21.0f);
        heartbeat("node2", 12.0f, 22.0f);
        heartbeat("node3", 13.0f, 23.0f);

        ControlPlaneProto.NodeList nodes = stub.getNodes(ControlPlaneProto.Empty.getDefaultInstance());
        assertEquals(3, nodes.getNodesCount());
        for (ControlPlaneProto.NodeInfo info : nodes.getNodesList()) {
            assertEquals(ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, info.getStatus());
            assertFalse(info.getCpuStale(), "a node that reported a real reading must not be stale");
            assertTrue(info.getCpu() > 0, "the reported value must be the one the node sent");
            assertEquals(4, info.getCapabilities().getCpuCores());
        }

        Map<String, Integer> assignments = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            assignments.merge(submit("task" + i), 1, Integer::sum);
        }
        assertEquals(Set.of("node1", "node2", "node3"), assignments.keySet());
        assignments.values().forEach(count -> assertEquals(3, count));
    }

    @Test
    @DisplayName("A killed node is marked SUSPECTED_DEAD within the timeout window")
    void killedNodeIsMarkedDeadWithinTheTimeoutWindow() {
        register("node1");
        register("node2");
        heartbeat("node1", 10f, 10f);
        heartbeat("node2", 10f, 10f);

        // node2 dies. node1 keeps beating. Advance just past the 6s timeout.
        clock.addAndGet(HEARTBEAT_TIMEOUT_MS + 1);
        heartbeat("node1", 10f, 10f);
        monitor.checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
        assertEquals(NodeStatus.SUSPECTED_DEAD, registry.get("node2").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A node still within the timeout window is not prematurely declared dead")
    void nodeIsNotDeclaredDeadBeforeTheTimeoutElapses() {
        register("node1");
        heartbeat("node1", 10f, 10f);

        clock.addAndGet(HEARTBEAT_TIMEOUT_MS - 1);
        monitor.checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Future tasks stop routing to a node once it is marked dead")
    void tasksStopRoutingToADeadNode() {
        register("node1");
        register("node2");
        register("node3");
        heartbeat("node1", 10f, 10f);
        heartbeat("node2", 10f, 10f);
        heartbeat("node3", 10f, 10f);

        clock.addAndGet(HEARTBEAT_TIMEOUT_MS + 1);
        heartbeat("node1", 10f, 10f);
        heartbeat("node3", 10f, 10f);
        monitor.checkHeartbeats();

        Set<String> assigned = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            assigned.add(submit("post-death-" + i));
        }

        assertFalse(assigned.contains("node2"), "a node marked dead must receive no further work");
        assertEquals(Set.of("node1", "node3"), assigned);
    }

    @Test
    @DisplayName("A reconnecting node re-integrates cleanly with no duplicate or zombie entry")
    void reconnectingNodeReIntegratesCleanly() {
        register("node1");
        register("node2");
        heartbeat("node1", 10f, 10f);
        heartbeat("node2", 77.0f, 66.0f);

        // node2 dies and is swept.
        clock.addAndGet(HEARTBEAT_TIMEOUT_MS + 1);
        heartbeat("node1", 10f, 10f);
        monitor.checkHeartbeats();
        assertEquals(NodeStatus.SUSPECTED_DEAD, registry.get("node2").orElseThrow().getStatus());

        // node2 comes back — new address, same identity — and re-registers.
        clock.addAndGet(1_000L);
        stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node2").setIp("10.0.0.222").setPort(50051).setHostname("node2").build());
        heartbeat("node2", 15.0f, 25.0f);

        assertEquals(2, registry.size(), "reconnection must not create a duplicate entry");
        assertEquals(2, backingMap.size(), "no zombie entry may remain in the backing map");

        NodeRecord revived = registry.get("node2").orElseThrow();
        assertEquals(NodeStatus.ALIVE, revived.getStatus());
        assertEquals("10.0.0.222", revived.getIp(), "the new address must be picked up");
        assertEquals(15.0f, revived.getCpuUsage(), 0.001);

        // And it is back in the rotation.
        Set<String> assigned = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            assigned.add(submit("post-recovery-" + i));
        }
        assertEquals(Set.of("node1", "node2"), assigned);
    }

    @Test
    @DisplayName("A node whose heartbeat resumes before a sweep is never marked dead at all")
    void heartbeatResumingBeforeSweepPreventsTheDeathMark() {
        register("node1");
        heartbeat("node1", 10f, 10f);

        clock.addAndGet(HEARTBEAT_TIMEOUT_MS + 1);
        heartbeat("node1", 12f, 12f);   // the node was slow, but it did report
        monitor.checkHeartbeats();

        assertEquals(NodeStatus.ALIVE, registry.get("node1").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("After a control-plane restart, nodes are told to re-register instead of hanging")
    void controlPlaneRestartTellsNodesToReRegister() {
        register("node1");
        heartbeat("node1", 10f, 10f);

        // Simulate the control plane restarting: the registry is empty but the agent keeps beating.
        backingMap.clear();

        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(
                ControlPlaneProto.HeartbeatRequest.newBuilder()
                        .setNodeId("node1").setCpu(10f).setCpuAvailable(true)
                        .setMemory(10f).setMemoryAvailable(true).build());

        assertEquals("UNKNOWN_NODE", response.getStatus());
        assertTrue(response.getReregistrationRequired(),
                "without this signal the agent heartbeats into an empty registry forever");

        // The agent obeys and re-registers; the cluster heals with no operator intervention.
        register("node1");
        heartbeat("node1", 10f, 10f);
        assertEquals(1, registry.size());
        assertEquals("node1", submit("after-restart"));
    }

    @Test
    @DisplayName("A dead node's telemetry stays visible and honest rather than being reset")
    void deadNodeRetainsItsLastKnownTelemetry() {
        register("node1");
        heartbeat("node1", 91.5f, 82.5f);

        clock.addAndGet(HEARTBEAT_TIMEOUT_MS + 1);
        monitor.checkHeartbeats();

        ControlPlaneProto.NodeInfo info = stub
                .getNodes(ControlPlaneProto.Empty.getDefaultInstance()).getNodes(0);

        // The operator needs to see what the node looked like when it stopped responding.
        assertEquals(ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD, info.getStatus());
        assertEquals(91.5f, info.getCpu(), 0.001);
        assertTrue(info.getLastHeartbeatEpochMillis() > 0);
    }

    @Test
    @DisplayName("A 20-node cluster registers and schedules without degrading")
    void largerClusterRegistersAndSchedulesEvenly() {
        int nodeCount = 20;
        for (int i = 0; i < nodeCount; i++) {
            String id = String.format("node%02d", i);
            register(id);
            heartbeat(id, 10f, 10f);
        }

        assertEquals(nodeCount, registry.size());
        assertEquals(nodeCount, registry.aliveSnapshot().size());

        Map<String, Integer> assignments = new HashMap<>();
        for (int i = 0; i < nodeCount * 5; i++) {
            assignments.merge(submit("task" + i), 1, Integer::sum);
        }

        assertEquals(nodeCount, assignments.size(), "every node must take a share of the work");
        assignments.values().forEach(count -> assertEquals(5, count));
    }
}
