package com.nextgen.controlplane.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.job.JobRecord;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.task.TaskStateDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage HH: periodic-snapshot durability for {@link TaskRegistry}/{@link JobRegistry} in single-node
 * ({@code RAFT_ENABLED=false}) mode. Task/job state was previously in-memory only there and did not
 * survive a plain process restart — unlike the Raft-replicated path (Stage J), where every replica
 * already persists via its own durable WAL and always replays from index 1 on restart.
 *
 * <p>Deliberately NOT a WAL — a plain periodic JSON snapshot of every current record, written
 * atomically (write-to-temp-then-{@code ATOMIC_MOVE}, the same idiom {@code BuildContextStore}/
 * {@code PkiPaths} already use elsewhere) so a crash mid-write can never corrupt the previous good
 * snapshot. This is a real, named, accepted tradeoff, not full durability: a crash between two
 * snapshots loses whatever changed in that window — bounded by {@code intervalMillis}, not eliminated.
 * Matches the project plan's own "WAL-style or periodic snapshot" scope, choosing the simpler of the
 * two since a snapshot is trivially correct to restore from (replay a WAL correctly is real additional
 * complexity this single-node fallback path doesn't need — the Raft path already has a real WAL for
 * the case that actually demands it).
 *
 * <p>Never used when Raft is enabled — that path's own WAL is authoritative, and mixing a stale
 * periodic snapshot into a freshly-elected leader's state would risk resurrecting overwritten data.
 */
public final class RegistrySnapshotStore implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrySnapshotStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path snapshotFile;
    private final TaskRegistry taskRegistry;
    private final JobRegistry jobRegistry;
    private final long intervalMillis;

    public RegistrySnapshotStore(Path snapshotFile, TaskRegistry taskRegistry, JobRegistry jobRegistry,
                                 long intervalMillis) {
        this.snapshotFile = snapshotFile;
        this.taskRegistry = taskRegistry;
        this.jobRegistry = jobRegistry;
        this.intervalMillis = intervalMillis;
    }

    @Override
    public void run() {
        LOG.info("RegistrySnapshotStore started (file={}, interval={}ms)", snapshotFile, intervalMillis);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMillis);
                snapshotNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A transient write failure must not kill the sweep thread for the rest of the
                // process's life — same discipline HeartbeatMonitor/RiskMonitor's own sweep loops apply.
                LOG.warn("Registry snapshot failed; continuing", e);
            }
        }
    }

    /** Writes a fresh snapshot of every current task/job right now. Public (not just driven by the
     * interval loop) so a test can call it deterministically without waiting on a timer. */
    public void snapshotNow() {
        Snapshot snapshot = new Snapshot(
                taskRegistry.snapshot().stream().map(RegistrySnapshotStore::toDto).toList(),
                jobRegistry.snapshot().stream().map(RegistrySnapshotStore::toDto).toList());
        try {
            Path parent = snapshotFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = snapshotFile.resolveSibling(snapshotFile.getFileName().toString() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(snapshot));
            try {
                Files.move(tmp, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Cross-filesystem temp/final dirs (unusual — both are under the same parent) would
                // reject an atomic move; falling back keeps this working rather than failing an
                // otherwise-successful snapshot over a non-issue, matching BuildContextStore's own
                // identical fallback.
                Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Could not write registry snapshot to {}: {}", snapshotFile, e.getMessage());
        }
    }

    /** Loads a previously-written snapshot into fresh backing maps for {@link TaskRegistry}/
     * {@link JobRegistry} to be constructed with — or empty maps if none exists yet (first-ever start)
     * or it can't be read (never throws, matching {@code ModelStore}'s own "a persistence problem must
     * not prevent startup" discipline). */
    public static Loaded load(Path snapshotFile) {
        if (!Files.exists(snapshotFile)) {
            return new Loaded(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
        try {
            Snapshot snapshot = MAPPER.readValue(Files.readString(snapshotFile), Snapshot.class);
            ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();
            for (TaskDto dto : snapshot.tasks()) {
                tasks.put(dto.taskId(), fromDto(dto));
            }
            ConcurrentHashMap<String, JobRecord> jobs = new ConcurrentHashMap<>();
            for (JobDto dto : snapshot.jobs()) {
                jobs.put(dto.jobId(), fromDto(dto));
            }
            LOG.info("📦 Restored {} task(s) and {} job(s) from {}", tasks.size(), jobs.size(), snapshotFile);
            return new Loaded(tasks, jobs);
        } catch (Exception e) {
            LOG.warn("Could not load registry snapshot from {} — starting with empty registries: {}",
                    snapshotFile, e.getMessage());
            return new Loaded(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    public record Loaded(ConcurrentHashMap<String, TaskRecord> tasks, ConcurrentHashMap<String, JobRecord> jobs) {
    }

    // ── DTOs — plain data, deliberately decoupled from TaskRecord/JobRecord's own shape so those
    // classes can evolve without this file's on-disk JSON schema silently changing underneath it ──

    private record Snapshot(List<TaskDto> tasks, List<JobDto> jobs) {
    }

    private record TaskDto(String taskId, String jobId, String kind, String payloadJson, String assignedNodeId,
                           String state, String resultJson, String error, long createdAtMillis,
                           long dispatchedAtMillis, long completedAtMillis, int attempt) {
    }

    private record JobDto(String jobId, String kind, List<String> taskIds, String state, String combinedResultJson,
                          List<String> retriedTaskIds, long createdAtMillis, long updatedAtMillis,
                          String supersedesJobId) {
    }

    private static TaskDto toDto(TaskRecord r) {
        return new TaskDto(r.getTaskId(), r.getJobId(), r.getKind().name(), r.getPayloadJson(),
                r.getAssignedNodeId(), r.getState().name(), r.getResultJson(), r.getError(),
                r.getCreatedAtMillis(), r.getDispatchedAtMillis(), r.getCompletedAtMillis(), r.getAttempt());
    }

    private static TaskRecord fromDto(TaskDto dto) {
        return TaskRecord.restore(dto.taskId(), dto.jobId(), TaskKindDomain.valueOf(dto.kind()), dto.payloadJson(),
                dto.assignedNodeId(), TaskStateDomain.valueOf(dto.state()), dto.resultJson(), dto.error(),
                dto.createdAtMillis(), dto.dispatchedAtMillis(), dto.completedAtMillis(), dto.attempt());
    }

    private static JobDto toDto(JobRecord r) {
        return new JobDto(r.getJobId(), r.getKind().name(), r.getTaskIds(), r.getState().name(),
                r.getCombinedResultJson(), List.copyOf(r.getRetriedTaskIds()), r.getCreatedAtMillis(),
                r.getUpdatedAtMillis(), r.getSupersedesJobId());
    }

    private static JobRecord fromDto(JobDto dto) {
        return JobRecord.restore(dto.jobId(), TaskKindDomain.valueOf(dto.kind()), dto.taskIds(),
                JobStateDomain.valueOf(dto.state()), dto.combinedResultJson(), Set.copyOf(dto.retriedTaskIds()),
                dto.createdAtMillis(), dto.updatedAtMillis(), dto.supersedesJobId());
    }
}
