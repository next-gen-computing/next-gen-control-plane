package com.nextgen.controlplane.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The authoritative store of real, dispatched tasks — the tracking that {@code SubmitTask} never had
 * before this: previously, once a {@code TaskResponse} was returned, the control plane kept no record
 * of the task or which node it went to, so there was nothing to report on later and nothing to
 * migrate if that node died.
 *
 * <p>Same concurrency contract as {@code NodeRegistry}: every mutation goes through {@code compute}/
 * {@code computeIfPresent}, holding the per-key bin lock so read-decide-write is atomic per task. No
 * logging, metrics, or I/O inside a remapping function — {@code ConcurrentHashMap} forbids re-entrant
 * access to the same map from one.
 *
 * <p><b>Fencing.</b> {@link #markRunning}/{@link #markCompleted}/{@link #markFailed} all take the id
 * of the node reporting the event, stamped by the caller from the task channel's identity — never
 * trusted from the event payload itself. A report is only applied when it matches the task's
 * <i>current</i> {@code assignedNodeId}; otherwise it's a stale report from a node this task has since
 * been reassigned away from, and is dropped. Without this, a late result arriving after a proactive
 * migration (Stage D) could silently overwrite a fresher result from the replacement node.
 */
public final class TaskRegistry {

    private final ConcurrentHashMap<String, TaskRecord> tasks;
    private final LongSupplier clock;

    public TaskRegistry() {
        this(new ConcurrentHashMap<>(), System::currentTimeMillis);
    }

    public TaskRegistry(ConcurrentHashMap<String, TaskRecord> backing, LongSupplier clock) {
        this.tasks = backing;
        this.clock = clock;
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /** Creates a new task record in the {@code QUEUED} state. {@code jobId} is empty for a standalone
     * task. Stage Y: idempotent on an existing id — a client retrying {@code SubmitTask}/{@code
     * SubmitJob} after an RPC timeout whose original call actually succeeded previously reset an
     * already-{@code COMPLETED}/{@code FAILED} record straight back to {@code QUEUED} via an
     * unconditional {@code put}, permanently discarding its real result. Now simply delegates to
     * {@link #createIfAbsent}'s already-correct discipline (used on the Raft path for the exact same
     * reason) instead of duplicating it. */
    public TaskRecord createAndQueue(String taskId, String jobId, TaskKindDomain kind, String payloadJson) {
        return createIfAbsent(taskId, jobId, kind, payloadJson);
    }

    /**
     * Like {@link #createAndQueue}, but a no-op (returns the existing record unchanged) if {@code taskId}
     * already exists — needed under Raft: a client retry of a {@code SubmitTask}/{@code SubmitJob} RPC
     * after a leader dies mid-response re-proposes the identical command, and {@link #createAndQueue}'s
     * unconditional {@code put} would reset an already-{@code DISPATCHED} (or further along) task back
     * to {@code QUEUED}, silently discarding real progress. This one change is what removes the need for
     * any separate client-session/dedup table — the command itself is idempotent.
     */
    public TaskRecord createIfAbsent(String taskId, String jobId, TaskKindDomain kind, String payloadJson) {
        TaskRecord fresh = TaskRecord.queued(taskId, jobId, kind, payloadJson, clock.getAsLong());
        return tasks.computeIfAbsent(taskId, key -> fresh);
    }

    /** Assigns a queued (or previously-dispatched) task to a node. Stage Y: refuses to move a task OUT
     * of {@code COMPLETED} — without this guard, a task that already reported success could be pushed
     * straight back to {@code DISPATCHED} (e.g. by a stale/duplicate dispatch call racing a real
     * terminal report) and genuinely re-executed on a node, duplicating real work and destroying the
     * original result. Returns empty (a no-op) in that case. Deliberately NOT extended to
     * {@code FAILED}: {@code JobCoordinator}'s own established one-retry-on-failure mechanism
     * legitimately redispatches a just-failed task to a different node — that is the intended,
     * existing behavior this guard must not break, not the bug it exists to fix. */
    public Optional<TaskRecord> markDispatched(String taskId, String nodeId) {
        long now = clock.getAsLong();
        TaskRecord[] updated = new TaskRecord[1];
        tasks.computeIfPresent(taskId, (key, existing) -> {
            if (existing.getState() == TaskStateDomain.COMPLETED) {
                return existing;
            }
            return updated[0] = existing.withDispatched(nodeId, now);
        });
        return Optional.ofNullable(updated[0]);
    }

    /** The node confirmed it started running the task. Dropped (returns empty) if fenced out. */
    public Optional<TaskRecord> markRunning(String taskId, String reportingNodeId) {
        TaskRecord[] updated = new TaskRecord[1];
        tasks.computeIfPresent(taskId, (key, existing) -> {
            if (!existing.getAssignedNodeId().equals(reportingNodeId)) {
                return existing; // stale report — this task is no longer this node's to update
            }
            return updated[0] = existing.withRunning();
        });
        return Optional.ofNullable(updated[0]);
    }

    /** Records a successful result. Dropped (returns empty) if the reporting node no longer owns this task. */
    public Optional<TaskRecord> markCompleted(String taskId, String reportingNodeId, String resultJson) {
        long now = clock.getAsLong();
        TaskRecord[] updated = new TaskRecord[1];
        tasks.computeIfPresent(taskId, (key, existing) -> {
            if (!existing.getAssignedNodeId().equals(reportingNodeId)) {
                return existing;
            }
            return updated[0] = existing.withCompleted(resultJson, now);
        });
        return Optional.ofNullable(updated[0]);
    }

    /** Records a failure. Dropped (returns empty) if the reporting node no longer owns this task. */
    public Optional<TaskRecord> markFailed(String taskId, String reportingNodeId, String error) {
        long now = clock.getAsLong();
        TaskRecord[] updated = new TaskRecord[1];
        tasks.computeIfPresent(taskId, (key, existing) -> {
            if (!existing.getAssignedNodeId().equals(reportingNodeId)) {
                return existing;
            }
            return updated[0] = existing.withFailed(error, now);
        });
        return Optional.ofNullable(updated[0]);
    }

    /**
     * Marks a task as being proactively moved off its current node, ahead of the redispatch that
     * actually reassigns it (see {@code ProactiveMigrator}). No fencing here — unlike
     * {@code markRunning}/{@code markCompleted}/{@code markFailed}, this is never a report FROM a
     * node; it is the server unilaterally deciding to move work, so there is no reporting identity to
     * check against.
     */
    public Optional<TaskRecord> markMigrating(String taskId) {
        TaskRecord[] updated = new TaskRecord[1];
        tasks.computeIfPresent(taskId, (key, existing) -> updated[0] = existing.withMigrating());
        return Optional.ofNullable(updated[0]);
    }

    // ── Read paths ───────────────────────────────────────────────────────────

    public Optional<TaskRecord> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** All tasks, newest first. */
    public List<TaskRecord> snapshot() {
        List<TaskRecord> all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparingLong(TaskRecord::getCreatedAtMillis).reversed());
        return Collections.unmodifiableList(all);
    }

    /**
     * This node's currently in-flight tasks — {@code DISPATCHED} or {@code RUNNING} — the ones
     * {@code ProactiveMigrator} needs to move elsewhere. Excludes {@code QUEUED} (no node assigned
     * yet) and every terminal/already-migrating state.
     */
    public List<TaskRecord> tasksOnNode(String nodeId) {
        List<TaskRecord> onNode = new ArrayList<>();
        for (TaskRecord record : tasks.values()) {
            if (record.getAssignedNodeId().equals(nodeId)
                    && (record.getState() == TaskStateDomain.DISPATCHED
                            || record.getState() == TaskStateDomain.RUNNING)) {
                onNode.add(record);
            }
        }
        return Collections.unmodifiableList(onNode);
    }
}
