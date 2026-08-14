package com.nextgen.controlplane.task;

import java.util.Locale;

/**
 * Immutable snapshot of one real task. Same rationale as {@code NodeRecord}: one {@code map.get()}
 * yields a fully consistent snapshot with no locking on the read path, and every mutation is
 * funnelled through {@link TaskRegistry}'s {@code compute}/{@code computeIfPresent} calls, which hold
 * the per-key bin lock and make read-decide-write atomic per task.
 *
 * <p><b>{@code assignedNodeId} is the fencing anchor.</b> {@link TaskRegistry} only applies a
 * progress/result report to this record when the reporting node's id matches this field exactly. A
 * late report from a node this task has since been reassigned away from (see
 * {@link #withReassigned}) does not match and is dropped — otherwise a stale report from a dying node
 * could clobber a fresher result from its replacement.
 */
public final class TaskRecord {

    private final String taskId;
    private final String jobId;
    private final TaskKindDomain kind;
    private final String payloadJson;

    private final String assignedNodeId;
    private final TaskStateDomain state;
    private final String resultJson;
    private final String error;

    private final long createdAtMillis;
    private final long dispatchedAtMillis;
    private final long completedAtMillis;

    /** Incremented every time this task is (re)dispatched — first dispatch is attempt 1. */
    private final int attempt;

    private TaskRecord(String taskId, String jobId, TaskKindDomain kind, String payloadJson,
                       String assignedNodeId, TaskStateDomain state, String resultJson, String error,
                       long createdAtMillis, long dispatchedAtMillis, long completedAtMillis, int attempt) {
        this.taskId = taskId;
        this.jobId = jobId == null ? "" : jobId;
        this.kind = kind;
        this.payloadJson = payloadJson == null ? "" : payloadJson;
        this.assignedNodeId = assignedNodeId == null ? "" : assignedNodeId;
        this.state = state;
        this.resultJson = resultJson == null ? "" : resultJson;
        this.error = error == null ? "" : error;
        this.createdAtMillis = createdAtMillis;
        this.dispatchedAtMillis = dispatchedAtMillis;
        this.completedAtMillis = completedAtMillis;
        this.attempt = attempt;
    }

    /** Creates a freshly-queued task with no node assigned yet. */
    public static TaskRecord queued(String taskId, String jobId, TaskKindDomain kind,
                                    String payloadJson, long nowMillis) {
        return new TaskRecord(taskId, jobId, kind, payloadJson,
                "", TaskStateDomain.QUEUED, "", "",
                nowMillis, 0L, 0L, 0);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getTaskId()             { return taskId; }
    public String getJobId()              { return jobId; }
    public TaskKindDomain getKind()       { return kind; }
    public String getPayloadJson()        { return payloadJson; }
    public String getAssignedNodeId()     { return assignedNodeId; }
    public TaskStateDomain getState()     { return state; }
    public String getResultJson()         { return resultJson; }
    public String getError()              { return error; }
    public long getCreatedAtMillis()      { return createdAtMillis; }
    public long getDispatchedAtMillis()   { return dispatchedAtMillis; }
    public long getCompletedAtMillis()    { return completedAtMillis; }
    public int getAttempt()               { return attempt; }

    public boolean hasJob() { return !jobId.isEmpty(); }

    // ── Copy-on-write mutators ───────────────────────────────────────────────

    /** Assigns this task to a node and marks it dispatched. Bumps {@code attempt}. */
    public TaskRecord withDispatched(String nodeId, long nowMillis) {
        return new TaskRecord(taskId, jobId, kind, payloadJson,
                nodeId, TaskStateDomain.DISPATCHED, "", "",
                createdAtMillis, nowMillis, completedAtMillis, attempt + 1);
    }

    /** The node confirmed it started running the task. */
    public TaskRecord withRunning() {
        return new TaskRecord(taskId, jobId, kind, payloadJson,
                assignedNodeId, TaskStateDomain.RUNNING, resultJson, error,
                createdAtMillis, dispatchedAtMillis, completedAtMillis, attempt);
    }

    public TaskRecord withCompleted(String resultJson, long nowMillis) {
        return new TaskRecord(taskId, jobId, kind, payloadJson,
                assignedNodeId, TaskStateDomain.COMPLETED, resultJson, "",
                createdAtMillis, dispatchedAtMillis, nowMillis, attempt);
    }

    public TaskRecord withFailed(String error, long nowMillis) {
        return new TaskRecord(taskId, jobId, kind, payloadJson,
                assignedNodeId, TaskStateDomain.FAILED, "", error,
                createdAtMillis, dispatchedAtMillis, nowMillis, attempt);
    }

    /**
     * Marks this task as being proactively moved off its current node, before that node has
     * necessarily failed. The actual reassignment to a new node happens via a subsequent
     * {@link #withDispatched}, which also bumps {@code attempt} and clears any prior result.
     */
    public TaskRecord withMigrating() {
        return new TaskRecord(taskId, jobId, kind, payloadJson,
                assignedNodeId, TaskStateDomain.MIGRATING, resultJson, error,
                createdAtMillis, dispatchedAtMillis, completedAtMillis, attempt);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "TaskRecord{id=%s, job=%s, kind=%s, node=%s, state=%s, attempt=%d}",
                taskId, jobId.isEmpty() ? "-" : jobId, kind, assignedNodeId.isEmpty() ? "-" : assignedNodeId,
                state, attempt);
    }
}
