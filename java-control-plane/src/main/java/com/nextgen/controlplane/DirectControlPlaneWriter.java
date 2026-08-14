package com.nextgen.controlplane;

import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.proto.ControlPlaneProto.NodeCapabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * The default {@link ControlPlaneWriter}: calls exactly what {@link ControlPlaneServiceImpl} and its
 * collaborators called before this seam existed. Every existing constructor — and therefore every
 * existing test — uses this, so introducing the seam is a zero-behavior-change refactor.
 */
public final class DirectControlPlaneWriter implements ControlPlaneWriter {

    private final NodeRegistry nodeRegistry;
    private final TaskRegistry taskRegistry;
    private final JobRegistry jobRegistry;

    public DirectControlPlaneWriter(NodeRegistry nodeRegistry, TaskRegistry taskRegistry, JobRegistry jobRegistry) {
        this.nodeRegistry = nodeRegistry;
        this.taskRegistry = taskRegistry;
        this.jobRegistry = jobRegistry;
    }

    @Override
    public void registerNode(String nodeId, String ip, int port, String hostname,
                             NodeCapabilities capabilities, String agentVersion) {
        nodeRegistry.register(nodeId, ip, port, hostname, capabilities, agentVersion);
    }

    @Override
    public void deregisterNode(String nodeId, boolean drain) {
        nodeRegistry.deregister(nodeId, drain);
    }

    @Override
    public void submitTask(String taskId, TaskKindDomain kind, String payloadJson) {
        taskRegistry.createAndQueue(taskId, "", kind, payloadJson);
    }

    @Override
    public void submitJob(String jobId, TaskKindDomain kind, List<JobSubTaskPlan> plan) {
        List<String> taskIds = new ArrayList<>(plan.size());
        for (JobSubTaskPlan subTask : plan) {
            taskRegistry.createAndQueue(subTask.taskId(), jobId, kind, subTask.payloadJson());
            taskIds.add(subTask.taskId());
        }
        jobRegistry.createJob(jobId, kind, taskIds);
    }

    @Override
    public void dispatchTask(String taskId, String nodeId) {
        taskRegistry.markDispatched(taskId, nodeId);
    }

    @Override
    public void markTaskRunning(String taskId, String reportingNodeId) {
        taskRegistry.markRunning(taskId, reportingNodeId);
    }

    @Override
    public void markTaskCompleted(String taskId, String reportingNodeId, String resultJson) {
        taskRegistry.markCompleted(taskId, reportingNodeId, resultJson);
    }

    @Override
    public void markTaskFailed(String taskId, String reportingNodeId, String error) {
        taskRegistry.markFailed(taskId, reportingNodeId, error);
    }

    @Override
    public void markTaskMigrating(String taskId) {
        taskRegistry.markMigrating(taskId);
    }

    @Override
    public void markTaskRetried(String jobId, String taskId) {
        jobRegistry.markTaskRetried(jobId, taskId);
    }

    @Override
    public void completeJob(String jobId, JobStateDomain finalState, String combinedResultJson) {
        jobRegistry.completeJob(jobId, finalState, combinedResultJson);
    }
}
