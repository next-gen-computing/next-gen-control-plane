package com.nextgen.controlplane.task;

import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.RoundRobinScheduler;
import com.nextgen.controlplane.job.JobCoordinator;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The real {@link MigrationTrigger}: moves every task in flight on an at-risk node onto a healthy
 * replacement, reusing exactly the same {@link TaskDispatcher} path first placement and {@code
 * JobCoordinator}'s reactive retry already use. {@link TaskRegistry}'s fencing is what makes this
 * safe — a late result the old node reports after this runs no longer matches the task's (now
 * reassigned) {@code assignedNodeId} and is dropped, never accepted as authoritative over the
 * replacement's fresher result.
 *
 * <p>No checkpointing: a migrated task restarts from its original payload on the new node, stated
 * plainly as a real limitation rather than silently assumed away.
 */
public final class ProactiveMigrator implements MigrationTrigger {
    private static final Logger LOG = LoggerFactory.getLogger(ProactiveMigrator.class);

    private final TaskRegistry taskRegistry;
    private final TaskDispatcher taskDispatcher;
    private final NodeTaskChannelRegistry channelRegistry;
    private final NodeRegistry nodeRegistry;
    private final RoundRobinScheduler scheduler;
    private final JobCoordinator jobCoordinator;

    public ProactiveMigrator(TaskRegistry taskRegistry, TaskDispatcher taskDispatcher,
                             NodeTaskChannelRegistry channelRegistry, NodeRegistry nodeRegistry,
                             RoundRobinScheduler scheduler, JobCoordinator jobCoordinator) {
        this.taskRegistry = taskRegistry;
        this.taskDispatcher = taskDispatcher;
        this.channelRegistry = channelRegistry;
        this.nodeRegistry = nodeRegistry;
        this.scheduler = scheduler;
        this.jobCoordinator = jobCoordinator;
    }

    @Override
    public void migrateAwayFrom(String nodeId, String reason) {
        List<TaskRecord> inFlight = taskRegistry.tasksOnNode(nodeId);
        if (inFlight.isEmpty()) {
            LOG.info("⚠ Node '{}' is at risk ({}) but has no in-flight tasks to migrate", nodeId, reason);
            return;
        }
        LOG.warn("⚠ Proactively migrating {} task(s) off at-risk node '{}': {}",
                inFlight.size(), nodeId, reason);
        for (TaskRecord task : inFlight) {
            migrateOne(task, nodeId, reason);
        }
    }

    private void migrateOne(TaskRecord task, String oldNodeId, String reason) {
        // No fallback to the at-risk node itself, unlike JobCoordinator's reactive retry: retrying a
        // transient execution failure on the same node can still make sense, but "migrating" a task
        // to the very node it's being moved away FROM would defeat the entire point.
        Optional<NodeRecord> replacement = pickReplacementNode(oldNodeId);
        if (replacement.isEmpty()) {
            LOG.error("❌ No healthy replacement available to migrate task {} off '{}' — leaving it in place",
                    task.getTaskId(), oldNodeId);
            return;
        }
        String newNodeId = replacement.get().getNodeId();

        taskRegistry.markMigrating(task.getTaskId());
        bestEffortCancel(oldNodeId, task.getTaskId(), reason);

        boolean dispatched = taskDispatcher.dispatch(task.getTaskId(), newNodeId);
        if (dispatched) {
            LOG.info("✅ Task {} migrated from '{}' to '{}'", task.getTaskId(), oldNodeId, newNodeId);
        } else {
            LOG.error("❌ Task {} failed to migrate from '{}' to '{}'", task.getTaskId(), oldNodeId, newNodeId);
            // TaskDispatcher already marked it FAILED synchronously on this same thread. A job
            // sub-task's failure is otherwise only ever observed via the wire result path
            // (ControlPlaneServiceImpl.handleResult) or JobCoordinator's own retry — neither of which
            // runs for a dispatch failure triggered from here, so this notifies it directly, exactly
            // mirroring what JobCoordinator.dispatchInitially does for its own synchronous failures.
            taskRegistry.get(task.getTaskId())
                    .filter(TaskRecord::hasJob)
                    .filter(record -> record.getState() == TaskStateDomain.FAILED)
                    .ifPresent(jobCoordinator::onSubTaskTerminal);
        }
    }

    private Optional<NodeRecord> pickReplacementNode(String excludeNodeId) {
        List<NodeRecord> candidates = nodeRegistry.aliveSnapshot().stream()
                .filter(n -> !n.getNodeId().equals(excludeNodeId))
                .toList();
        return scheduler.select(candidates);
    }

    /**
     * Tells the old node to stop, if it can still be reached. Best-effort only: the whole reason
     * this is happening is that the node is predicted to be failing, so it may already be
     * unreachable — not being able to tell it to stop is expected, not an error.
     */
    private void bestEffortCancel(String nodeId, String taskId, String reason) {
        Optional<StreamObserver<ControlPlaneProto.ServerTaskCommand>> outbound = channelRegistry.get(nodeId);
        if (outbound.isEmpty()) {
            return;
        }
        try {
            outbound.get().onNext(ControlPlaneProto.ServerTaskCommand.newBuilder()
                    .setCancel(ControlPlaneProto.TaskCancel.newBuilder()
                            .setTaskId(taskId)
                            .setReason(reason)
                            .build())
                    .build());
        } catch (RuntimeException e) {
            LOG.debug("Could not send cancel for task {} to node {}: {}", taskId, nodeId, e.getMessage());
        }
    }
}
