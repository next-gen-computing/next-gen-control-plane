package com.nextgen.controlplane.task;

import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.RoundRobinScheduler;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link QueuedTaskReconciler} — the post-become-leader sweep that redispatches (or fails) a
 * task orphaned in QUEUED by a leader dying between accept and dispatch.
 */
class QueuedTaskReconcilerTest {

    private AtomicLong clockMillis;
    private TaskRegistry taskRegistry;
    private NodeRegistry nodeRegistry;
    private NodeTaskChannelRegistry channelRegistry;
    private QueuedTaskReconciler reconciler;

    @BeforeEach
    void setUp() {
        clockMillis = new AtomicLong(1_000L);
        taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), clockMillis::get);
        nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), clockMillis::get);
        channelRegistry = new NodeTaskChannelRegistry();
        TaskDispatcher dispatcher = new TaskDispatcher(taskRegistry, channelRegistry, clockMillis::get);
        reconciler = new QueuedTaskReconciler(
                taskRegistry, nodeRegistry, dispatcher, new RoundRobinScheduler(), null);
    }

    private void registerAliveNodeWithOpenChannel(String nodeId) {
        nodeRegistry.register(nodeId, "10.0.0.1", 50051, nodeId,
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test");
        nodeRegistry.recordHeartbeat(nodeId, 10f, true, 10f, true, 1);
        channelRegistry.register(nodeId, new StreamObserver<>() {
            @Override public void onNext(ControlPlaneProto.ServerTaskCommand value) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
    }

    @Test
    void redispatchesAQueuedTaskOntoAnAliveConnectedNode() {
        registerAliveNodeWithOpenChannel("node1");
        taskRegistry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        reconciler.reconcile();

        TaskRecord record = taskRegistry.get("t1").orElseThrow();
        assertEquals(TaskStateDomain.DISPATCHED, record.getState());
        assertEquals("node1", record.getAssignedNodeId());
    }

    @Test
    void failsAQueuedTaskHonestlyWhenNoAliveNodeExists() {
        taskRegistry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        reconciler.reconcile();

        TaskRecord record = taskRegistry.get("t1").orElseThrow();
        assertEquals(TaskStateDomain.FAILED, record.getState());
        assertTrue(record.getError().contains("no alive node"), record.getError());
    }

    @Test
    void leavesAlreadyDispatchedTasksUntouched() {
        registerAliveNodeWithOpenChannel("node1");
        registerAliveNodeWithOpenChannel("node2");
        taskRegistry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        taskRegistry.markDispatched("t1", "node1");

        reconciler.reconcile();

        TaskRecord record = taskRegistry.get("t1").orElseThrow();
        assertEquals(TaskStateDomain.DISPATCHED, record.getState());
        assertEquals("node1", record.getAssignedNodeId(), "an already-dispatched task must not be reassigned");
    }

    @Test
    void leavesCompletedAndFailedTasksUntouched() {
        taskRegistry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        taskRegistry.markDispatched("t1", "node1");
        taskRegistry.markCompleted("t1", "node1", "{\"prime_count\":5}");

        reconciler.reconcile();

        TaskRecord record = taskRegistry.get("t1").orElseThrow();
        assertEquals(TaskStateDomain.COMPLETED, record.getState());
        assertEquals("{\"prime_count\":5}", record.getResultJson());
    }

    @Test
    void reconcilesMultipleOrphanedTasksInOnePass() {
        registerAliveNodeWithOpenChannel("node1");
        taskRegistry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        taskRegistry.createAndQueue("t2", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        reconciler.reconcile();

        assertEquals(TaskStateDomain.DISPATCHED, taskRegistry.get("t1").orElseThrow().getState());
        assertEquals(TaskStateDomain.DISPATCHED, taskRegistry.get("t2").orElseThrow().getState());
    }

    /** Proves a Raft-enabled deployment's writer is what actually mutates state, not the registry
     * directly — the same "re-read after write" discipline every other writer-aware collaborator uses. */
    @Test
    void routesTheNoNodeFailureThroughTheWriterWhenOneIsSupplied() {
        AtomicReference<String> failedTaskId = new AtomicReference<>();
        com.nextgen.controlplane.ControlPlaneWriter writer = new RecordingWriter(taskRegistry, failedTaskId);
        TaskDispatcher dispatcher = new TaskDispatcher(taskRegistry, channelRegistry, clockMillis::get, writer);
        QueuedTaskReconciler writerAwareReconciler = new QueuedTaskReconciler(
                taskRegistry, nodeRegistry, dispatcher, new RoundRobinScheduler(), writer);
        taskRegistry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        writerAwareReconciler.reconcile();

        assertEquals("t1", failedTaskId.get());
        assertEquals(TaskStateDomain.FAILED, taskRegistry.get("t1").orElseThrow().getState());
    }

    /** A minimal {@link com.nextgen.controlplane.ControlPlaneWriter} that only implements
     * {@code markTaskFailed} for real (delegating to the registry, exactly like
     * {@code DirectControlPlaneWriter} does) — every other method is unreachable on the
     * no-alive-node path this test exercises, so it throws rather than silently no-op'ing if that
     * assumption is ever wrong. */
    private record RecordingWriter(TaskRegistry taskRegistry, AtomicReference<String> failedTaskId)
            implements com.nextgen.controlplane.ControlPlaneWriter {

        @Override
        public void markTaskFailed(String taskId, String reportingNodeId, String error) {
            failedTaskId.set(taskId);
            taskRegistry.markFailed(taskId, reportingNodeId, error);
        }

        @Override
        public void registerNode(String nodeId, String ip, int port, String hostname,
                                 ControlPlaneProto.NodeCapabilities capabilities, String agentVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deregisterNode(String nodeId, boolean drain) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submitTask(String taskId, TaskKindDomain kind, String payloadJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submitJob(String jobId, TaskKindDomain kind,
                              java.util.List<com.nextgen.controlplane.ControlPlaneWriter.JobSubTaskPlan> plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dispatchTask(String taskId, String nodeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markTaskRunning(String taskId, String reportingNodeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markTaskCompleted(String taskId, String reportingNodeId, String resultJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markTaskMigrating(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markTaskRetried(String jobId, String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeJob(String jobId, com.nextgen.controlplane.job.JobStateDomain finalState,
                                String combinedResultJson) {
            throw new UnsupportedOperationException();
        }
    }
}
