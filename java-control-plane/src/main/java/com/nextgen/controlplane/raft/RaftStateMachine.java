package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.proto.ControlPlaneProto.JobSubTaskPlan;
import com.nextgen.proto.ControlPlaneProto.StateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies committed {@link StateCommand}s to the real {@link NodeRegistry}/{@link TaskRegistry}/
 * {@link JobRegistry} — the only class in {@code com.nextgen.controlplane.raft} that knows about the
 * application domain; everything else in this package ({@link RaftNode}, {@link RaftLog},
 * {@link RaftTransport}) is domain-agnostic and could apply to any replicated state machine.
 *
 * <p>Invoked only from {@link RaftNode}'s single internal apply thread, strictly in committed order
 * (see {@link RaftApplier}'s contract) — from the three registries' point of view, this is exactly one
 * more caller using their existing {@code compute}/{@code computeIfPresent} methods, indistinguishable
 * from {@code ControlPlaneServiceImpl} calling them directly in the non-replicated case. Each case
 * below is a thin call into an existing registry method — no new mutation logic is introduced here.
 *
 * <p>Uses {@link TaskRegistry#createIfAbsent}/a job-existence check rather than the unconditional
 * {@code createAndQueue}/{@code createJob} for the two creation commands: a client retry after a
 * leader dies mid-response re-proposes the identical command under a <i>new</i> log index, and this is
 * what makes replaying it a no-op instead of resetting real progress.
 */
public final class RaftStateMachine implements RaftApplier {
    private static final Logger LOG = LoggerFactory.getLogger(RaftStateMachine.class);

    private final NodeRegistry nodeRegistry;
    private final TaskRegistry taskRegistry;
    private final JobRegistry jobRegistry;
    private final ApplyClock applyClock;

    public RaftStateMachine(NodeRegistry nodeRegistry, TaskRegistry taskRegistry, JobRegistry jobRegistry,
                            ApplyClock applyClock) {
        this.nodeRegistry = nodeRegistry;
        this.taskRegistry = taskRegistry;
        this.jobRegistry = jobRegistry;
        this.applyClock = applyClock;
    }

    @Override
    public void apply(long index, ByteString command) {
        if (command.isEmpty()) {
            return; // a Raft no-op entry (§5.4.2) — nothing to apply
        }
        StateCommand parsed;
        try {
            parsed = StateCommand.parseFrom(command);
        } catch (InvalidProtocolBufferException e) {
            LOG.error("Could not parse StateCommand at applied index {} — skipping. This indicates a "
                    + "real bug (a proposer built malformed bytes), not routine input.", index, e);
            return;
        }

        // Every registry stamps its own timestamps from an injected clock; this makes that clock read
        // the LEADER's proposal time for the duration of this one apply, so every replica stamps the
        // identical value — see ApplyClock's Javadoc for why this matters.
        applyClock.enter(parsed.getProposedAtEpochMillis());
        try {
            applyOne(parsed);
        } finally {
            applyClock.exit();
        }
    }

    private void applyOne(StateCommand command) {
        switch (command.getCommandCase()) {
            case REGISTER_NODE -> {
                var c = command.getRegisterNode();
                nodeRegistry.register(c.getNodeId(), c.getIp(), c.getPort(), c.getHostname(),
                        c.getCapabilities(), c.getAgentVersion());
            }
            case DEREGISTER_NODE -> {
                var c = command.getDeregisterNode();
                nodeRegistry.deregister(c.getNodeId(), c.getDrain());
            }
            case SUBMIT_TASK -> {
                var c = command.getSubmitTask();
                TaskKindDomain kind = TaskKindDomain.fromProto(c.getKind());
                if (kind != null) {
                    taskRegistry.createIfAbsent(c.getTaskId(), "", kind, c.getPayloadJson());
                } else {
                    LOG.warn("SubmitTaskCommand for task '{}' names an unrecognised kind — skipping apply", c.getTaskId());
                }
            }
            case SUBMIT_JOB -> applySubmitJob(command);
            case DISPATCH_TASK -> {
                var c = command.getDispatchTask();
                taskRegistry.markDispatched(c.getTaskId(), c.getNodeId());
            }
            case MARK_TASK_RUNNING -> {
                var c = command.getMarkTaskRunning();
                taskRegistry.markRunning(c.getTaskId(), c.getReportingNodeId());
            }
            case MARK_TASK_COMPLETED -> {
                var c = command.getMarkTaskCompleted();
                taskRegistry.markCompleted(c.getTaskId(), c.getReportingNodeId(), c.getResultJson());
            }
            case MARK_TASK_FAILED -> {
                var c = command.getMarkTaskFailed();
                taskRegistry.markFailed(c.getTaskId(), c.getReportingNodeId(), c.getError());
            }
            case MARK_TASK_MIGRATING -> taskRegistry.markMigrating(command.getMarkTaskMigrating().getTaskId());
            case MARK_TASK_RETRIED -> {
                var c = command.getMarkTaskRetried();
                jobRegistry.markTaskRetried(c.getJobId(), c.getTaskId());
            }
            case COMPLETE_JOB -> {
                var c = command.getCompleteJob();
                jobRegistry.completeJob(c.getJobId(), JobStateDomain.fromProto(c.getFinalState()),
                        c.getCombinedResultJson());
            }
            case MINT_ENROLLMENT_TOKEN, CONSUME_ENROLLMENT_TOKEN ->
                    LOG.warn("Enrollment-token commands have no state-machine handler yet (wired in a "
                            + "later Stage J step) — ignoring");
            case COMMAND_NOT_SET -> LOG.warn("StateCommand with no command set — ignoring");
            default -> LOG.warn("Unrecognised StateCommand case {} — ignoring (likely proposed by a "
                    + "newer replica running a version this one doesn't understand yet)", command.getCommandCase());
        }
    }

    private void applySubmitJob(StateCommand command) {
        var c = command.getSubmitJob();
        TaskKindDomain kind = TaskKindDomain.fromProto(c.getKind());
        if (kind == null) {
            LOG.warn("SubmitJobCommand for job '{}' names an unrecognised kind — skipping apply", c.getJobId());
            return;
        }
        List<String> taskIds = new ArrayList<>(c.getSubTasksCount());
        for (JobSubTaskPlan subTask : c.getSubTasksList()) {
            taskRegistry.createIfAbsent(subTask.getTaskId(), c.getJobId(), kind, subTask.getPayloadJson());
            taskIds.add(subTask.getTaskId());
        }
        if (jobRegistry.get(c.getJobId()).isEmpty()) {
            jobRegistry.createJob(c.getJobId(), kind, taskIds);
        }
    }
}
