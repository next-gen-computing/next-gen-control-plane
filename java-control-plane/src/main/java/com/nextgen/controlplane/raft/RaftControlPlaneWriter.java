package com.nextgen.controlplane.raft;

import com.nextgen.controlplane.ControlPlaneWriter;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.proto.ControlPlaneProto.CompleteJobCommand;
import com.nextgen.proto.ControlPlaneProto.DeregisterNodeCommand;
import com.nextgen.proto.ControlPlaneProto.DispatchTaskCommand;
import com.nextgen.proto.ControlPlaneProto.MarkTaskCompletedCommand;
import com.nextgen.proto.ControlPlaneProto.MarkTaskFailedCommand;
import com.nextgen.proto.ControlPlaneProto.MarkTaskMigratingCommand;
import com.nextgen.proto.ControlPlaneProto.MarkTaskRetriedCommand;
import com.nextgen.proto.ControlPlaneProto.MarkTaskRunningCommand;
import com.nextgen.proto.ControlPlaneProto.NodeCapabilities;
import com.nextgen.proto.ControlPlaneProto.RegisterNodeCommand;
import com.nextgen.proto.ControlPlaneProto.StateCommand;
import com.nextgen.proto.ControlPlaneProto.SubmitJobCommand;
import com.nextgen.proto.ControlPlaneProto.SubmitTaskCommand;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Raft-backed {@link ControlPlaneWriter}: builds a {@link StateCommand}, proposes it via
 * {@link RaftNode}, and <b>blocks</b> the calling thread until it has committed and been applied — see
 * {@link ControlPlaneWriter}'s Javadoc for why this simple approach is safe and sufficient here (every
 * caller is already guaranteed to be running on the current leader, courtesy of the leader-redirect
 * interceptor rejecting non-leader calls before they ever reach this writer).
 *
 * <p>{@link NotLeaderException} (thrown by {@code RaftNode.propose} itself, e.g. a race where
 * leadership was lost between the interceptor's check and this call) propagates unchanged — callers
 * translate it to the same {@code UNAVAILABLE} + leader-hint response the interceptor uses. A propose
 * that times out becomes an {@link IllegalStateException}, an honest failure rather than a hang.
 */
public final class RaftControlPlaneWriter implements ControlPlaneWriter {

    private final RaftNode raftNode;
    private final long proposeTimeoutMs;

    public RaftControlPlaneWriter(RaftNode raftNode, long proposeTimeoutMs) {
        this.raftNode = raftNode;
        this.proposeTimeoutMs = proposeTimeoutMs;
    }

    @Override
    public void registerNode(String nodeId, String ip, int port, String hostname,
                             NodeCapabilities capabilities, String agentVersion) {
        propose(newCommand().setRegisterNode(RegisterNodeCommand.newBuilder()
                .setNodeId(nodeId).setIp(ip).setPort(port).setHostname(hostname)
                .setCapabilities(capabilities).setAgentVersion(agentVersion)));
    }

    @Override
    public void deregisterNode(String nodeId, boolean drain) {
        propose(newCommand().setDeregisterNode(DeregisterNodeCommand.newBuilder()
                .setNodeId(nodeId).setDrain(drain)));
    }

    @Override
    public void submitTask(String taskId, TaskKindDomain kind, String payloadJson) {
        propose(newCommand().setSubmitTask(SubmitTaskCommand.newBuilder()
                .setTaskId(taskId).setKind(kind.toProto()).setPayloadJson(payloadJson)));
    }

    @Override
    public void submitJob(String jobId, TaskKindDomain kind, List<JobSubTaskPlan> plan) {
        SubmitJobCommand.Builder builder = SubmitJobCommand.newBuilder().setJobId(jobId).setKind(kind.toProto());
        for (JobSubTaskPlan subTask : plan) {
            builder.addSubTasks(com.nextgen.proto.ControlPlaneProto.JobSubTaskPlan.newBuilder()
                    .setTaskId(subTask.taskId())
                    .setPayloadJson(subTask.payloadJson()));
        }
        propose(newCommand().setSubmitJob(builder));
    }

    @Override
    public void dispatchTask(String taskId, String nodeId) {
        propose(newCommand().setDispatchTask(DispatchTaskCommand.newBuilder()
                .setTaskId(taskId).setNodeId(nodeId)));
    }

    @Override
    public void markTaskRunning(String taskId, String reportingNodeId) {
        propose(newCommand().setMarkTaskRunning(MarkTaskRunningCommand.newBuilder()
                .setTaskId(taskId).setReportingNodeId(reportingNodeId)));
    }

    @Override
    public void markTaskCompleted(String taskId, String reportingNodeId, String resultJson) {
        propose(newCommand().setMarkTaskCompleted(MarkTaskCompletedCommand.newBuilder()
                .setTaskId(taskId).setReportingNodeId(reportingNodeId).setResultJson(resultJson)));
    }

    @Override
    public void markTaskFailed(String taskId, String reportingNodeId, String error) {
        propose(newCommand().setMarkTaskFailed(MarkTaskFailedCommand.newBuilder()
                .setTaskId(taskId).setReportingNodeId(reportingNodeId).setError(error)));
    }

    @Override
    public void markTaskMigrating(String taskId) {
        propose(newCommand().setMarkTaskMigrating(MarkTaskMigratingCommand.newBuilder().setTaskId(taskId)));
    }

    @Override
    public void markTaskRetried(String jobId, String taskId) {
        propose(newCommand().setMarkTaskRetried(MarkTaskRetriedCommand.newBuilder()
                .setJobId(jobId).setTaskId(taskId)));
    }

    @Override
    public void completeJob(String jobId, JobStateDomain finalState, String combinedResultJson) {
        propose(newCommand().setCompleteJob(CompleteJobCommand.newBuilder()
                .setJobId(jobId).setFinalState(finalState.toProto()).setCombinedResultJson(combinedResultJson)));
    }

    private static StateCommand.Builder newCommand() {
        return StateCommand.newBuilder().setProposedAtEpochMillis(System.currentTimeMillis());
    }

    private void propose(StateCommand.Builder command) {
        try {
            raftNode.propose(command.build().toByteString()).get(proposeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re; // e.g. NotLeaderException — surface it exactly, don't wrap it further
            }
            throw new IllegalStateException("Raft propose failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Raft propose did not commit within " + proposeTimeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Raft propose to commit", e);
        }
    }
}
