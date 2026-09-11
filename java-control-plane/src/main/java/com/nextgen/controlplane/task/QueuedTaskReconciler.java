package com.nextgen.controlplane.task;

import com.nextgen.controlplane.ControlPlaneWriter;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.RoundRobinScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Redispatches (or honestly fails) every task still {@code QUEUED} — meant to be invoked once, right
 * after a Raft replica becomes leader.
 *
 * <p>This closes the one gap accept-then-dispatch being two separate replicated commands leaves: a
 * leader can commit {@code SubmitTask}/{@code SubmitJob} and then die before the follow-up
 * {@code DispatchTask} command is ever proposed, leaving a task {@code QUEUED} forever — nothing else
 * in the system ever revisits a {@code QUEUED} task on its own. {@code DISPATCHED}/{@code RUNNING}
 * tasks are deliberately left alone: the node executing them is very likely still alive and will
 * report in normally, and — since there is no checkpointing anywhere in this project — touching them
 * here would discard real in-progress work for no reason.
 *
 * <p>In a non-Raft, single-process deployment a task can still become permanently {@code QUEUED} if the
 * process crashes between accept and dispatch, but there both are the SAME single point of failure —
 * restarting is the only recovery either way, so this reconciler is only ever wired up when Raft is
 * enabled.
 */
public final class QueuedTaskReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(QueuedTaskReconciler.class);

    private final TaskRegistry taskRegistry;
    private final NodeRegistry nodeRegistry;
    private final TaskDispatcher taskDispatcher;
    private final RoundRobinScheduler scheduler;
    /** Null means "mutate taskRegistry directly" — see {@link ControlPlaneWriter}'s Javadoc. Always
     * non-null in practice, since this class is only ever wired up under Raft. */
    private final ControlPlaneWriter writer;

    public QueuedTaskReconciler(TaskRegistry taskRegistry, NodeRegistry nodeRegistry,
                                TaskDispatcher taskDispatcher, RoundRobinScheduler scheduler,
                                ControlPlaneWriter writer) {
        this.taskRegistry = taskRegistry;
        this.nodeRegistry = nodeRegistry;
        this.taskDispatcher = taskDispatcher;
        this.scheduler = scheduler;
        this.writer = writer;
    }

    /** Runs one reconciliation pass over every currently QUEUED task. */
    public void reconcile() {
        for (TaskRecord task : taskRegistry.snapshot()) {
            if (task.getState() != TaskStateDomain.QUEUED) {
                continue;
            }
            Optional<NodeRecord> node = scheduler.select(nodeRegistry.aliveSnapshot());
            if (node.isPresent()) {
                LOG.info("🔁 Reconciling orphaned QUEUED task '{}' onto node '{}' after leader election",
                        task.getTaskId(), node.get().getNodeId());
                taskDispatcher.dispatch(task.getTaskId(), node.get().getNodeId());
            } else {
                String reason = "FAILED — no alive node available during post-election reconciliation";
                LOG.warn("❌ Task '{}' stayed QUEUED with no alive node to reconcile onto — failing honestly",
                        task.getTaskId());
                if (writer != null) {
                    writer.markTaskFailed(task.getTaskId(), "", reason);
                } else {
                    taskRegistry.markFailed(task.getTaskId(), "", reason);
                }
            }
        }
    }
}
