package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ControlPlaneServiceImpl using in-process gRPC server.
 * Tests all four RPCs: RegisterNode, SendHeartbeat, SubmitTask, GetNodes.
 */
class ControlPlaneServiceImplTest {

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub;
    private ConcurrentHashMap<String, NodeRecord> registry;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        registry = new ConcurrentHashMap<>();
        
        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(new ControlPlaneServiceImpl(registry))
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
        ControlPlaneProto.NodeInfo nodeInfo = ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1")
                .setIp("192.168.1.1")
                .setPort(50051)
                .setHostname("host1")
                .build();

        ControlPlaneProto.RegisterResponse response = stub.registerNode(nodeInfo);

        assertEquals("REGISTERED", response.getStatus());
        assertEquals("node1", response.getAssignedId());
        assertTrue(registry.containsKey("node1"));
        assertEquals("host1", registry.get("node1").getHostname());
    }

    @Test
    void testSendHeartbeatKnownNode() {
        // First register the node
        ControlPlaneProto.NodeInfo nodeInfo = ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1")
                .setIp("192.168.1.1")
                .setPort(50051)
                .setHostname("host1")
                .build();
        stub.registerNode(nodeInfo);

        // Send heartbeat
        ControlPlaneProto.HeartbeatRequest heartbeat = ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId("node1")
                .setCpu(45.5f)
                .setMemory(78.3f)
                .build();

        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(heartbeat);

        assertEquals("OK", response.getStatus());
        assertEquals(45.5f, registry.get("node1").getCpuUsage(), 0.001);
        assertEquals(78.3f, registry.get("node1").getMemoryUsage(), 0.001);
    }

    @Test
    void testSendHeartbeatUnknownNode() {
        ControlPlaneProto.HeartbeatRequest heartbeat = ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId("unknown")
                .setCpu(50.0f)
                .setMemory(60.0f)
                .build();

        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(heartbeat);

        assertEquals("UNKNOWN_NODE", response.getStatus());
    }

    @Test
    void testGetNodes() {
        // Register multiple nodes
        stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1")
                .setIp("192.168.1.1")
                .setPort(50051)
                .setHostname("host1")
                .build());
        
        stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node2")
                .setIp("192.168.1.2")
                .setPort(50051)
                .setHostname("host2")
                .build());

        ControlPlaneProto.NodeList nodeList = stub.getNodes(ControlPlaneProto.Empty.newBuilder().build());

        assertEquals(2, nodeList.getNodesCount());
        assertTrue(nodeList.getNodesList().stream().anyMatch(n -> n.getNodeId().equals("node1")));
        assertTrue(nodeList.getNodesList().stream().anyMatch(n -> n.getNodeId().equals("node2")));
    }

    @Test
    void testSubmitTaskWithNoAliveNodes() {
        ControlPlaneProto.TaskRequest task = ControlPlaneProto.TaskRequest.newBuilder()
                .setTaskId("task1")
                .setPayload("test payload")
                .build();

        ControlPlaneProto.TaskResponse response = stub.submitTask(task);

        assertEquals("NONE", response.getAssignedNode());
        assertTrue(response.getResult().contains("FAILED"));
    }

    @Test
    void testSubmitTaskRoundRobin() {
        // Register two nodes and send heartbeats to mark them alive
        for (String nodeId : new String[]{"node1", "node2"}) {
            stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                    .setNodeId(nodeId)
                    .setIp("192.168.1.1")
                    .setPort(50051)
                    .setHostname(nodeId)
                    .build());
            
            stub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                    .setNodeId(nodeId)
                    .setCpu(50.0f)
                    .setMemory(60.0f)
                    .build());
        }

        // Submit multiple tasks and verify round-robin
        String[] assignments = new String[4];
        for (int i = 0; i < 4; i++) {
            ControlPlaneProto.TaskResponse response = stub.submitTask(
                    ControlPlaneProto.TaskRequest.newBuilder()
                            .setTaskId("task" + i)
                            .setPayload("payload" + i)
                            .build());
            assignments[i] = response.getAssignedNode();
        }

        // Should alternate between node1 and node2
        assertNotEquals(assignments[0], assignments[1]);
        assertNotEquals(assignments[1], assignments[2]);
        assertNotEquals(assignments[2], assignments[3]);
        assertEquals(assignments[0], assignments[2]);
        assertEquals(assignments[1], assignments[3]);
    }
}
