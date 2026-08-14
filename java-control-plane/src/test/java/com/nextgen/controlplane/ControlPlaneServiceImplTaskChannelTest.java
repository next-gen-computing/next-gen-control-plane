package com.nextgen.controlplane;

import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.TaskDispatcher;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneProto.NodeTaskEvent;
import com.nextgen.proto.ControlPlaneProto.ServerTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskChannelHello;
import com.nextgen.proto.ControlPlaneProto.TaskProgressEvent;
import com.nextgen.proto.ControlPlaneProto.TaskResponse;
import com.nextgen.proto.ControlPlaneProto.TaskResultEvent;
import com.nextgen.proto.ControlPlaneProto.TaskState;
import com.nextgen.proto.ControlPlaneProto.TaskStatusResponse;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
 * Proves the real dispatch → execute → collect pipeline end to end over an actual (in-process) gRPC
 * TaskChannel stream, using a fake node that plays both sides: opens the channel, receives a real
 * TaskDispatch, and reports real progress/result back — the thing SubmitTask never did before Stage A.
 *
 * <p>Also proves the fencing guarantee TaskRegistryTest establishes at the unit level actually holds
 * over the wire: a result reported by a node the task has since been reassigned away from must be
 * dropped by the real {@code taskChannel} handler, not just by {@link TaskRegistry} in isolation.
 */
class ControlPlaneServiceImplTaskChannelTest {

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub;
    private ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub;
    private ControlPlaneServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        NodeRegistry nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);
        TaskRegistry taskRegistry = new TaskRegistry();
        NodeTaskChannelRegistry channelRegistry = new NodeTaskChannelRegistry();
        service = new ControlPlaneServiceImpl(nodeRegistry, new RoundRobinScheduler(), taskRegistry, channelRegistry);

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

    /** Registers and heartbeats a node so it is ALIVE and eligible for scheduling. */
    private void registerAliveNode(String nodeId) {
        blockingStub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId(nodeId).setIp("10.0.0.1").setPort(50051).setHostname(nodeId).build());
        blockingStub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId(nodeId).setCpu(10f).setCpuAvailable(true).setMemory(10f).setMemoryAvailable(true)
                .build());
    }

    /** Opens a fake node's TaskChannel and returns the queue of commands the server pushes to it. */
    private LinkedBlockingQueue<ServerTaskCommand> openTaskChannel(String nodeId) {
        LinkedBlockingQueue<ServerTaskCommand> incoming = new LinkedBlockingQueue<>();
        StreamObserver<NodeTaskEvent> outbound = asyncStub.taskChannel(new StreamObserver<>() {
            @Override public void onNext(ServerTaskCommand value) { incoming.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        outbound.onNext(NodeTaskEvent.newBuilder()
                .setHello(TaskChannelHello.newBuilder().setNodeId(nodeId).build())
                .build());
        this.lastOpenedOutbound = outbound;
        return incoming;
    }

    // Convenience for tests that only need to send events from the most recently opened channel.
    private StreamObserver<NodeTaskEvent> lastOpenedOutbound;

    /** Retries SubmitTask until real dispatch succeeds (the fake node's channel is registered async). */
    private TaskResponse submitUntilDispatched(String taskId, String payload) {
        long deadline = System.currentTimeMillis() + 5_000;
        TaskResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = blockingStub.submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                    .setTaskId(taskId)
                    .setPayload(payload)
                    .setKind(ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                    .build());
            if (last.getResult().startsWith("ACCEPTED")) {
                return last;
            }
            sleep(20);
        }
        fail("Task was never successfully dispatched: " + (last == null ? "null" : last.getResult()));
        return null;
    }

    private TaskStatusResponse awaitTaskState(String taskId, TaskState expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        TaskStatusResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = blockingStub.getTaskStatus(
                    ControlPlaneProto.TaskStatusRequest.newBuilder().setTaskId(taskId).build());
            if (last.getState() == expected) {
                return last;
            }
            sleep(20);
        }
        fail("Task " + taskId + " never reached state " + expected + "; last seen: "
                + (last == null ? "null" : last.getState()));
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void realDispatchExecutionAndResultCollectionRoundTrips() throws InterruptedException {
        registerAliveNode("node1");
        LinkedBlockingQueue<ServerTaskCommand> node1Commands = openTaskChannel("node1");

        TaskResponse submitResponse = submitUntilDispatched("t1", "{\"range_start\":0,\"range_end\":101}");
        assertEquals("node1", submitResponse.getAssignedNode());

        ServerTaskCommand command = node1Commands.poll(5, TimeUnit.SECONDS);
        assertNotNull(command, "the server must actually push a TaskDispatch down the real stream");
        assertTrue(command.hasDispatch());
        assertEquals("t1", command.getDispatch().getTaskId());
        assertEquals(ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE, command.getDispatch().getKind());

        // The fake node "executes" and reports back over the real stream, exactly like TaskChannelClient.
        lastOpenedOutbound.onNext(NodeTaskEvent.newBuilder()
                .setProgress(TaskProgressEvent.newBuilder().setTaskId("t1").setState(TaskState.TASK_STATE_RUNNING))
                .build());
        lastOpenedOutbound.onNext(NodeTaskEvent.newBuilder()
                .setResult(TaskResultEvent.newBuilder()
                        .setTaskId("t1").setSuccess(true).setResultJson("{\"prime_count\":25}"))
                .build());

        TaskStatusResponse status = awaitTaskState("t1", TaskState.TASK_STATE_COMPLETED);
        assertEquals("node1", status.getAssignedNode());
        assertEquals("{\"prime_count\":25}", status.getResultJson());
        assertEquals("", status.getError());

        ControlPlaneProto.TaskList list = blockingStub.listTasks(ControlPlaneProto.Empty.newBuilder().build());
        assertTrue(list.getTasksList().stream().anyMatch(t -> t.getTaskId().equals("t1")));
    }

    @Test
    void aStaleResultFromANodeTheTaskWasMigratedAwayFromIsDropped() throws InterruptedException {
        registerAliveNode("node1");
        LinkedBlockingQueue<ServerTaskCommand> node1Commands = openTaskChannel("node1");

        submitUntilDispatched("t1", "{\"range_start\":0,\"range_end\":101}");
        ServerTaskCommand command = node1Commands.poll(5, TimeUnit.SECONDS);
        assertNotNull(command);
        StreamObserver<NodeTaskEvent> node1Outbound = lastOpenedOutbound;

        // Simulate a proactive migration (Stage D territory) reassigning "t1" to node2 while node1's
        // execution is still in flight — exactly what a real ProactiveMigrator will eventually do.
        service.taskRegistry().markDispatched("t1", "node2");

        // node1 has no idea it was migrated away from and reports its real result anyway.
        node1Outbound.onNext(NodeTaskEvent.newBuilder()
                .setResult(TaskResultEvent.newBuilder()
                        .setTaskId("t1").setSuccess(true).setResultJson("{\"prime_count\":999}"))
                .build());

        // Give the stale message a moment to be processed (it must be dropped, not applied).
        sleep(200);

        TaskStatusResponse status = blockingStub.getTaskStatus(
                ControlPlaneProto.TaskStatusRequest.newBuilder().setTaskId("t1").build());
        assertEquals("node2", status.getAssignedNode(), "task must still belong to its new node");
        assertEquals(TaskState.TASK_STATE_DISPATCHED, status.getState(),
                "the stale COMPLETED report from the old node must not have been applied");
        assertEquals("", status.getResultJson(), "the fake, stale result must never surface as real data");
    }

    @Test
    void getTaskStatusOnAnUnknownTaskIsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                blockingStub.getTaskStatus(
                        ControlPlaneProto.TaskStatusRequest.newBuilder().setTaskId("ghost").build()));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }
}
