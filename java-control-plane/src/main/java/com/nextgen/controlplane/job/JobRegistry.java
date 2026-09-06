package com.nextgen.controlplane.job;

import com.nextgen.controlplane.task.TaskKindDomain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The authoritative store of jobs, mirroring {@code TaskRegistry}'s concurrency contract exactly:
 * every mutation goes through {@code compute}/{@code computeIfPresent}, holding the per-key bin lock
 * so read-decide-write is atomic per job. No logging, metrics, or I/O inside a remapping function.
 */
public final class JobRegistry {

    private final ConcurrentHashMap<String, JobRecord> jobs;
    private final LongSupplier clock;

    public JobRegistry() {
        this(new ConcurrentHashMap<>(), System::currentTimeMillis);
    }

    public JobRegistry(ConcurrentHashMap<String, JobRecord> backing, LongSupplier clock) {
        this.jobs = backing;
        this.clock = clock;
    }

    /** Creates a new job record in the {@code RUNNING} state, owning the given sub-task ids. Stage Y:
     * idempotent on an existing id — mirrors {@code TaskRegistry.createAndQueue}'s identical fix; an
     * unconditional {@code put} here let a retried {@code SubmitJob} call silently reset an already-
     * reduced (COMPLETED/FAILED/PARTIAL_FAILURE) job's record back to a fresh RUNNING one, discarding
     * its real combined result. Returns the EXISTING record unchanged if {@code jobId} already exists. */
    public JobRecord createJob(String jobId, TaskKindDomain kind, List<String> taskIds) {
        JobRecord fresh = JobRecord.running(jobId, kind, taskIds, clock.getAsLong());
        return jobs.computeIfAbsent(jobId, key -> fresh);
    }

    /** Stage PP: same as {@link #createJob} but records which job this one's rolling update replaced —
     * see {@link JobRecord#getSupersedesJobId()}. Same Stage Y idempotency guard as {@link #createJob}. */
    public JobRecord createUpdateJob(String jobId, TaskKindDomain kind, List<String> taskIds,
                                     String supersedesJobId) {
        JobRecord fresh = JobRecord.runningUpdate(jobId, kind, taskIds, supersedesJobId, clock.getAsLong());
        return jobs.computeIfAbsent(jobId, key -> fresh);
    }

    /** Records that {@code taskId}'s one reactive retry has now been used. */
    public Optional<JobRecord> markTaskRetried(String jobId, String taskId) {
        JobRecord[] updated = new JobRecord[1];
        jobs.computeIfPresent(jobId, (key, existing) -> updated[0] = existing.withTaskRetried(taskId));
        return Optional.ofNullable(updated[0]);
    }

    /**
     * Finalizes a job with its reduced outcome — but only from {@code RUNNING}, so a job already
     * finalized by a concurrent caller is never finalized a second time (an idempotent no-op instead).
     */
    public Optional<JobRecord> completeJob(String jobId, JobStateDomain finalState, String combinedResultJson) {
        long now = clock.getAsLong();
        JobRecord[] updated = new JobRecord[1];
        jobs.computeIfPresent(jobId, (key, existing) -> {
            if (existing.getState() != JobStateDomain.RUNNING) {
                return existing;
            }
            return updated[0] = existing.withResult(finalState, combinedResultJson, now);
        });
        return Optional.ofNullable(updated[0]);
    }

    public Optional<JobRecord> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** All jobs, newest first. */
    public List<JobRecord> snapshot() {
        List<JobRecord> all = new ArrayList<>(jobs.values());
        all.sort(Comparator.comparingLong(JobRecord::getCreatedAtMillis).reversed());
        return Collections.unmodifiableList(all);
    }
}
