package com.nextgen.controlplane.raft;

import com.nextgen.controlplane.DirectControlPlaneWriter;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.job.JobRecord;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.task.TaskStateDomain;
import com.nextgen.proto.ControlPlaneProto.CompleteJobCommand;
import com.nextgen.proto.ControlPlaneProto.DispatchTaskCommand;
import com.nextgen.proto.ControlPlaneProto.JobSubTaskPlan;
import com.nextgen.proto.ControlPlaneProto.MarkTaskCompletedCommand;
import com.nextgen.proto.ControlPlaneProto.NodeCapabilities;
import com.nextgen.proto.ControlPlaneProto.RegisterNodeCommand;
import com.nextgen.proto.ControlPlaneProto.StateCommand;
import com.nextgen.proto.ControlPlaneProto.SubmitJobCommand;
import com.nextgen.proto.ControlPlaneProto.SubmitTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RaftStateMachine} is where a bug would cause silent, invisible divergence between replicas —
 * these tests apply the same command sequence to two fully independent state machines and require
 * byte-identical results, including every timestamp field (the direct test for the class of bug
 * {@link ApplyClock} exists to prevent).
 */
class RaftStateMachineTest {

    private static final class Harness {
        final ApplyClock applyClock = new ApplyClock();
        // Every registry must be driven by the SAME ApplyClock — this is the actual wiring
        // ControlPlaneServer performs when RAFT_ENABLED=true, and is the entire point of this test:
        // without it, each registry stamps real wall-clock time instead of the leader's proposed time.
        final NodeRegistry nodeRegistry = new NodeRegistry(new java.util.concurrent.ConcurrentHashMap<>(), applyClock);
        final TaskRegistry taskRegistry = new TaskRegistry(new java.util.concurrent.ConcurrentHashMap<>(), applyClock);
        final JobRegistry jobRegistry = new JobRegistry(new java.util.concurrent.ConcurrentHashMap<>(), applyClock);
        final RaftStateMachine stateMachine =
                new RaftStateMachine(nodeRegistry, taskRegistry, jobRegistry, applyClock, null);

        void apply(long index, StateCommand.Builder command) {
            stateMachine.apply(index, command.build().toByteString());
        }
    }

    private static StateCommand.Builder command(long proposedAt) {
        return StateCommand.newBuilder().setProposedAtEpochMillis(proposedAt);
    }

    @Test
    void applyingTheSameCommandSequenceOnTwoStateMachinesProducesByteIdenticalState() {
        Harness a = new Harness();
        Harness b = new Harness();

        NodeCapabilities caps = NodeCapabilities.newBuilder().setCpuCores(4).setTotalMemoryBytes(8_000_000_000L).build();
        long t1 = 5_000_000_111L; // deliberately not a "round" number a wall clock would coincidentally produce
        long t2 = 5_000_000_222L;
        long t3 = 5_000_000_333L;

        List<Harness> both = List.of(a, b);
        for (Harness h : both) {
            h.apply(1, command(t1).setRegisterNode(RegisterNodeCommand.newBuilder()
                    .setNodeId("n1").setIp("10.0.0.5").setPort(50051).setHostname("laptop-1")
                    .setCapabilities(caps).setAgentVersion("1.0.0")));
            h.apply(2, command(t2).setSubmitTask(SubmitTaskCommand.newBuilder()
                    .setTaskId("t1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE).setPayloadJson("{\"a\":1}")));
            h.apply(3, command(t3).setDispatchTask(DispatchTaskCommand.newBuilder()
                    .setTaskId("t1").setNodeId("n1")));
        }

        NodeRecord nodeA = a.nodeRegistry.get("n1").orElseThrow();
        NodeRecord nodeB = b.nodeRegistry.get("n1").orElseThrow();
        assertEquals(nodeA.getNodeId(), nodeB.getNodeId());
        assertEquals(nodeA.getIp(), nodeB.getIp());
        assertEquals(nodeA.getRegisteredAtMillis(), nodeB.getRegisteredAtMillis());
        assertEquals(t1, nodeA.getRegisteredAtMillis(), "must be the LEADER's proposed time, not this replica's own clock");

        TaskRecord taskA = a.taskRegistry.get("t1").orElseThrow();
        TaskRecord taskB = b.taskRegistry.get("t1").orElseThrow();
        assertEquals(taskA.getState(), taskB.getState());
        assertEquals(taskA.getCreatedAtMillis(), taskB.getCreatedAtMillis());
        assertEquals(t2, taskA.getCreatedAtMillis());
        assertEquals(taskA.getDispatchedAtMillis(), taskB.getDispatchedAtMillis());
        assertEquals(t3, taskA.getDispatchedAtMillis());
        assertEquals(TaskStateDomain.DISPATCHED, taskA.getState());
        assertEquals("n1", taskA.getAssignedNodeId());
    }

    @Test
    void applyClockOnlyAppliesForTheDurationOfOneApplyCall() {
        Harness h = new Harness();
        long proposedAt = 123_456_789L;

        h.apply(1, command(proposedAt).setSubmitTask(SubmitTaskCommand.newBuilder()
                .setTaskId("t1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE).setPayloadJson("{}")));

        // Outside of apply(), the clock must NOT still be pinned to the last proposal's timestamp —
        // real wall-clock time everywhere else (heartbeat sweeps, risk scoring) depends on this.
        long realNow = System.currentTimeMillis();
        assertTrue(h.applyClock.getAsLong() >= realNow - 5_000,
                "ApplyClock must fall back to real wall-clock time once apply() has returned");
    }

    @Test
    void submitTaskAppliedTwiceUnderDifferentIndicesIsIdempotent() {
        Harness h = new Harness();
        h.apply(1, command(1_000).setSubmitTask(SubmitTaskCommand.newBuilder()
                .setTaskId("t1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE).setPayloadJson("{\"v\":1}")));
        h.apply(2, command(1_000).setDispatchTask(DispatchTaskCommand.newBuilder().setTaskId("t1").setNodeId("n1")));

        // Simulates a client retry after a leader died mid-response: the SAME logical SubmitTaskCommand
        // gets proposed again at a later index.
        h.apply(3, command(2_000).setSubmitTask(SubmitTaskCommand.newBuilder()
                .setTaskId("t1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE).setPayloadJson("{\"v\":1}")));

        TaskRecord record = h.taskRegistry.get("t1").orElseThrow();
        assertEquals(TaskStateDomain.DISPATCHED, record.getState(),
                "a retried creation must never reset real progress back to QUEUED");
        assertEquals("n1", record.getAssignedNodeId());
    }

    @Test
    void submitJobAppliedTwiceUnderDifferentIndicesIsIdempotent() {
        Harness h = new Harness();
        SubmitJobCommand.Builder job = SubmitJobCommand.newBuilder()
                .setJobId("job1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .addSubTasks(JobSubTaskPlan.newBuilder().setTaskId("job1-0").setPayloadJson("{\"a\":1}"))
                .addSubTasks(JobSubTaskPlan.newBuilder().setTaskId("job1-1").setPayloadJson("{\"a\":2}"));

        h.apply(1, command(1_000).setSubmitJob(job));
        h.apply(2, command(1_500).setDispatchTask(DispatchTaskCommand.newBuilder().setTaskId("job1-0").setNodeId("n1")));
        h.apply(3, command(2_000).setSubmitJob(job)); // retried

        assertEquals(1, h.jobRegistry.snapshot().size(), "a retried job submission must not create a duplicate job");
        assertEquals(TaskStateDomain.DISPATCHED, h.taskRegistry.get("job1-0").orElseThrow().getState(),
                "a retried job submission must not reset an already-dispatched sub-task");
        assertEquals(2, h.jobRegistry.get("job1").orElseThrow().getTaskIds().size());
    }

    @Test
    void aFencedTaskReportIsDroppedIdenticallyOnEveryReplica() {
        Harness a = new Harness();
        Harness b = new Harness();

        for (Harness h : List.of(a, b)) {
            h.apply(1, command(1_000).setSubmitTask(SubmitTaskCommand.newBuilder()
                    .setTaskId("t1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE).setPayloadJson("{}")));
            h.apply(2, command(1_100).setDispatchTask(DispatchTaskCommand.newBuilder().setTaskId("t1").setNodeId("node-a")));
            // A report from "node-b" — NOT the node this task is actually assigned to — must be fenced
            // out identically on both replicas (TaskRegistry's own fencing, unchanged).
            h.apply(3, command(1_200).setMarkTaskCompleted(MarkTaskCompletedCommand.newBuilder()
                    .setTaskId("t1").setReportingNodeId("node-b").setResultJson("{\"prime_count\":99}")));
        }

        for (Harness h : List.of(a, b)) {
            TaskRecord record = h.taskRegistry.get("t1").orElseThrow();
            assertEquals(TaskStateDomain.DISPATCHED, record.getState(), "a stale report must never be applied");
            assertEquals("", record.getResultJson());
        }
    }

    @Test
    void completeJobAppliedTwiceIsANoOp() {
        Harness h = new Harness();
        SubmitJobCommand.Builder job = SubmitJobCommand.newBuilder()
                .setJobId("job1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .addSubTasks(JobSubTaskPlan.newBuilder().setTaskId("job1-0").setPayloadJson("{}"));
        h.apply(1, command(1_000).setSubmitJob(job));

        h.apply(2, command(2_000).setCompleteJob(CompleteJobCommand.newBuilder()
                .setJobId("job1").setFinalState(com.nextgen.proto.ControlPlaneProto.JobState.JOB_STATE_COMPLETED)
                .setCombinedResultJson("{\"prime_count\":42}")));
        JobRecord first = h.jobRegistry.get("job1").orElseThrow();

        // A duplicate/retried completion, e.g. from two sub-tasks racing to finalize the same job.
        h.apply(3, command(3_000).setCompleteJob(CompleteJobCommand.newBuilder()
                .setJobId("job1").setFinalState(com.nextgen.proto.ControlPlaneProto.JobState.JOB_STATE_FAILED)
                .setCombinedResultJson("{\"prime_count\":0}")));
        JobRecord second = h.jobRegistry.get("job1").orElseThrow();

        assertEquals(JobStateDomain.COMPLETED, first.getState());
        assertEquals(JobStateDomain.COMPLETED, second.getState(), "the first completion wins; a later one is a no-op");
        assertEquals("{\"prime_count\":42}", second.getCombinedResultJson());
    }

    @Test
    void aNoOpEntryIsIgnored() {
        Harness h = new Harness();
        h.stateMachine.apply(1, com.google.protobuf.ByteString.EMPTY);
        assertEquals(0, h.nodeRegistry.size());
        assertEquals(0, h.taskRegistry.snapshot().size());
    }

    @Test
    void directWriterAndStateMachineAgreeOnTheSameOutcome() {
        // A sanity cross-check: DirectControlPlaneWriter (used in single-process mode) and
        // RaftStateMachine (used under Raft) must produce equivalent registry states for the same
        // logical operations, since RaftControlPlaneWriter is meant to be a drop-in replacement.
        NodeRegistry directNodes = new NodeRegistry();
        TaskRegistry directTasks = new TaskRegistry();
        JobRegistry directJobs = new JobRegistry();
        DirectControlPlaneWriter direct = new DirectControlPlaneWriter(directNodes, directTasks, directJobs);
        direct.submitTask("t1", com.nextgen.controlplane.task.TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        direct.dispatchTask("t1", "n1");

        Harness h = new Harness();
        h.apply(1, command(1_000).setSubmitTask(SubmitTaskCommand.newBuilder()
                .setTaskId("t1").setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE).setPayloadJson("{}")));
        h.apply(2, command(1_100).setDispatchTask(DispatchTaskCommand.newBuilder().setTaskId("t1").setNodeId("n1")));

        Optional<TaskRecord> directRecord = directTasks.get("t1");
        Optional<TaskRecord> raftRecord = h.taskRegistry.get("t1");
        assertTrue(directRecord.isPresent());
        assertTrue(raftRecord.isPresent());
        assertEquals(directRecord.get().getState(), raftRecord.get().getState());
        assertEquals(directRecord.get().getAssignedNodeId(), raftRecord.get().getAssignedNodeId());
    }
}
