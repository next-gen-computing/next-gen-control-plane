package com.nextgen.controlplane;

import com.nextgen.controlplane.risk.RuleBasedRiskScorer;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.ProactiveMigrator;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneProto.HeartbeatRequest;
import com.nextgen.proto.ControlPlaneProto.NodeInfo;
import com.nextgen.proto.ControlPlaneProto.NodeTaskEvent;
import com.nextgen.proto.ControlPlaneProto.ServerTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskChannelHello;
import com.nextgen.proto.ControlPlaneProto.TaskDispatch;
import com.nextgen.proto.ControlPlaneProto.TaskKind;
import com.nextgen.proto.ControlPlaneProto.TaskRequest;
import com.nextgen.proto.ControlPlaneProto.TaskResultEvent;
import com.nextgen.proto.ControlPlaneProto.TaskState;
import com.nextgen.proto.ControlPlaneProto.TaskStatusRequest;
import com.nextgen.proto.ControlPlaneProto.TaskStatusResponse;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the whole predictive-migration story end to end over real in-process gRPC: a node reports a
 * real degrading signal (low battery, on battery power) via real heartbeats, a real
 * {@code RiskMonitor} sweep scores it past threshold, a real {@code ProactiveMigrator} redispatches
 * its in-flight task to a healthy node — and a late result from the original node afterward is
 * dropped, not silently accepted, proving Stage A's fencing is what actually makes this safe.
 */
class ProactiveMigrationIntegrationTest {

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub;
    private ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub;
    private ControlPlaneServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        NodeRegistry nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);
        service = new ControlPlaneServiceImpl(
                nodeRegistry, new RoundRobinScheduler(), new TaskRegistry(), new NodeTaskChannelRegistry());

        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        blockingStub = ControlPlaneServiceGrpc.newBlockingStub(channel);
        asyncStub = ControlPlaneServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private LinkedBlockingQueue<ServerTaskCommand> openTaskChannelReturningOutbound(
            String nodeId, StreamObserver<NodeTaskEvent>[] outboundHolder) {
        LinkedBlockingQueue<ServerTaskCommand> incoming = new LinkedBlockingQueue<>();
        StreamObserver<NodeTaskEvent> outbound = asyncStub.taskChannel(new StreamObserver<>() {
            @Override public void onNext(ServerTaskCommand value) { incoming.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        outbound.onNext(NodeTaskEvent.newBuilder()
                .setHello(TaskChannelHello.newBuilder().setNodeId(nodeId).build())
                .build());
        outboundHolder[0] = outbound;
        return incoming;
    }

    private TaskStatusResponse awaitTaskAssignedTo(String taskId, String expectedNodeId) {
        long deadline = System.currentTimeMillis() + 5_000;
        TaskStatusResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = blockingStub.getTaskStatus(TaskStatusRequest.newBuilder().setTaskId(taskId).build());
            if (last.getAssignedNode().equals(expectedNodeId)) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("Task " + taskId + " was never (re)assigned to " + expectedNodeId
                + "; last seen assignedNode=" + (last == null ? "null" : last.getAssignedNode()));
        return null;
    }

    @Test
    void aTaskOnAnAtRiskNodeIsProactivelyMigratedToAHealthyNode() throws InterruptedException {
        // node1: registers, then reports a real degrading signal — unplugged, 5% battery.
        blockingStub.registerNode(NodeInfo.newBuilder()
                .setNodeId("node1").setIp("10.0.0.1").setPort(50051).setHostname("node1").build());
        blockingStub.sendHeartbeat(HeartbeatRequest.newBuilder()
                .setNodeId("node1")
                .setCpu(10f).setCpuAvailable(true).setMemory(10f).setMemoryAvailable(true)
                .setBatteryPercent(5f).setBatteryAvailable(true)
                .setCharging(false).setChargingKnown(true)
                .setOnAcPower(false).setOnAcPowerKnown(true)
                .build());

        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamObserver<NodeTaskEvent>[] node1Outbound = new StreamObserver[1];
        LinkedBlockingQueue<ServerTaskCommand> node1Commands = openTaskChannelReturningOutbound("node1", node1Outbound);

        // Only node1 is alive right now, so the task must land on it.
        ControlPlaneProto.TaskResponse submitResponse = blockingStub.submitTask(TaskRequest.newBuilder()
                .setTaskId("t1")
                .setPayload("{\"range_start\":0,\"range_end\":100}")
                .setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .build());
        assertEquals("node1", submitResponse.getAssignedNode());
        ServerTaskCommand firstDispatch = node1Commands.poll(5, TimeUnit.SECONDS);
        assertNotNull(firstDispatch);
        assertEquals("t1", firstDispatch.getDispatch().getTaskId());

        // node2: healthy, registers after the task was already placed on node1.
        blockingStub.registerNode(NodeInfo.newBuilder()
                .setNodeId("node2").setIp("10.0.0.2").setPort(50051).setHostname("node2").build());
        blockingStub.sendHeartbeat(HeartbeatRequest.newBuilder()
                .setNodeId("node2")
                .setCpu(10f).setCpuAvailable(true).setMemory(10f).setMemoryAvailable(true)
                .setBatteryAvailable(false) // desktop-style node2, e.g. no battery signal at all
                .build());
        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamObserver<NodeTaskEvent>[] node2Outbound = new StreamObserver[1];
        LinkedBlockingQueue<ServerTaskCommand> node2Commands = openTaskChannelReturningOutbound("node2", node2Outbound);

        // A real sweep: real scorer, real history (populated by the real heartbeat above), real migrator.
        RuleBasedRiskScorer scorer = new RuleBasedRiskScorer();
        ProactiveMigrator migrator = new ProactiveMigrator(
                service.taskRegistry(), service.taskDispatcher(), service.channelRegistry(),
                service.registry(), new RoundRobinScheduler(), service.jobCoordinator());
        RiskMonitor riskMonitor = new RiskMonitor(
                service.registry(), service.nodeHistory(), scorer, migrator, 999_999L);

        riskMonitor.checkRisk();

        // The SAME task must now have been redispatched to node2.
        ServerTaskCommand migratedDispatch = node2Commands.poll(5, TimeUnit.SECONDS);
        assertNotNull(migratedDispatch, "node2 must receive the migrated task's TaskDispatch");
        assertTrue(migratedDispatch.hasDispatch());
        assertEquals("t1", migratedDispatch.getDispatch().getTaskId());

        TaskStatusResponse afterMigration = awaitTaskAssignedTo("t1", "node2");
        assertEquals(TaskState.TASK_STATE_DISPATCHED, afterMigration.getState());

        // node1 was told to cancel (best-effort) — it should have received a cancel command too.
        ServerTaskCommand cancelOrNothing = node1Commands.poll(2, TimeUnit.SECONDS);
        if (cancelOrNothing != null) {
            assertTrue(cancelOrNothing.hasCancel());
            assertEquals("t1", cancelOrNothing.getCancel().getTaskId());
        }

        // node1, unaware it was migrated away from, reports its real (now stale) result late.
        node1Outbound[0].onNext(NodeTaskEvent.newBuilder()
                .setResult(TaskResultEvent.newBuilder()
                        .setTaskId("t1").setSuccess(true).setResultJson("{\"prime_count\":999}"))
                .build());

        Thread.sleep(200); // give the stale message a moment to be processed (and dropped)

        TaskStatusResponse finalStatus = blockingStub.getTaskStatus(
                TaskStatusRequest.newBuilder().setTaskId("t1").build());
        assertEquals("node2", finalStatus.getAssignedNode(), "the task must still belong to node2");
        assertEquals(TaskState.TASK_STATE_DISPATCHED, finalStatus.getState(),
                "the stale COMPLETED report from node1 must never have been applied");
        assertEquals("", finalStatus.getResultJson(), "node1's fake, stale result must never surface as real data");
    }

    @Test
    void aHealthyNodeIsNeverProactivelyMigrated() {
        blockingStub.registerNode(NodeInfo.newBuilder()
                .setNodeId("node1").setIp("10.0.0.1").setPort(50051).setHostname("node1").build());
        blockingStub.sendHeartbeat(HeartbeatRequest.newBuilder()
                .setNodeId("node1")
                .setCpu(10f).setCpuAvailable(true).setMemory(10f).setMemoryAvailable(true)
                .setBatteryPercent(95f).setBatteryAvailable(true)
                .setCharging(true).setChargingKnown(true)
                .setOnAcPower(true).setOnAcPowerKnown(true)
                .build());

        RuleBasedRiskScorer scorer = new RuleBasedRiskScorer();
        ProactiveMigrator migrator = new ProactiveMigrator(
                service.taskRegistry(), service.taskDispatcher(), service.channelRegistry(),
                service.registry(), new RoundRobinScheduler(), service.jobCoordinator());
        RiskMonitor riskMonitor = new RiskMonitor(
                service.registry(), service.nodeHistory(), scorer, migrator, 999_999L);

        riskMonitor.checkRisk();

        assertFalse(service.registry().get("node1").orElseThrow().isAtRisk());
    }
}
