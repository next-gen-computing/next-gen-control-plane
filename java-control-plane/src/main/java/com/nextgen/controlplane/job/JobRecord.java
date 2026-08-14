package com.nextgen.controlplane.job;

import com.nextgen.controlplane.task.TaskKindDomain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of one job — N {@code TaskRecord}s (see {@code TaskRegistry}) sharing this
 * job's id, tracked here only for what a job adds on top of its constituent tasks: which sub-task ids
 * belong to it, which of them have already used their one reactive retry (see
 * {@link JobCoordinator}), and — once every sub-task is terminal — the reduced outcome.
 *
 * <p>Sub-task progress itself (state, result, error) is never duplicated here; {@code TaskRegistry}
 * stays the single source of truth for it, looked up by id from {@link #getTaskIds()}.
 */
public final class JobRecord {

    private final String jobId;
    private final TaskKindDomain kind;
    private final List<String> taskIds;
    private final JobStateDomain state;
    private final String combinedResultJson;
    private final Set<String> retriedTaskIds;
    private final long createdAtMillis;
    private final long updatedAtMillis;

    private JobRecord(String jobId, TaskKindDomain kind, List<String> taskIds, JobStateDomain state,
                      String combinedResultJson, Set<String> retriedTaskIds,
                      long createdAtMillis, long updatedAtMillis) {
        this.jobId = jobId;
        this.kind = kind;
        this.taskIds = taskIds;
        this.state = state;
        this.combinedResultJson = combinedResultJson == null ? "" : combinedResultJson;
        this.retriedTaskIds = retriedTaskIds;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    /** A freshly-submitted job: all sub-tasks created, none retried, nothing reduced yet. */
    public static JobRecord running(String jobId, TaskKindDomain kind, List<String> taskIds, long nowMillis) {
        return new JobRecord(jobId, kind, List.copyOf(taskIds), JobStateDomain.RUNNING, "",
                Set.of(), nowMillis, nowMillis);
    }

    public String getJobId()                  { return jobId; }
    public TaskKindDomain getKind()            { return kind; }
    public List<String> getTaskIds()           { return taskIds; }
    public JobStateDomain getState()           { return state; }
    public String getCombinedResultJson()      { return combinedResultJson; }
    public long getCreatedAtMillis()           { return createdAtMillis; }
    public long getUpdatedAtMillis()           { return updatedAtMillis; }

    public boolean hasBeenRetried(String taskId) {
        return retriedTaskIds.contains(taskId);
    }

    public JobRecord withTaskRetried(String taskId) {
        Set<String> updated = new HashSet<>(retriedTaskIds);
        updated.add(taskId);
        return new JobRecord(jobId, kind, taskIds, state, combinedResultJson,
                Set.copyOf(updated), createdAtMillis, updatedAtMillis);
    }

    /** Finalizes the job with its reduced outcome. Only meaningful once, from {@code RUNNING}. */
    public JobRecord withResult(JobStateDomain finalState, String combinedResultJson, long nowMillis) {
        return new JobRecord(jobId, kind, taskIds, finalState, combinedResultJson,
                retriedTaskIds, createdAtMillis, nowMillis);
    }

    @Override
    public String toString() {
        return "JobRecord{jobId=%s, kind=%s, subTasks=%d, state=%s}"
                .formatted(jobId, kind, taskIds.size(), state);
    }
}
