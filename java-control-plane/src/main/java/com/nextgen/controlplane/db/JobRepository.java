package com.nextgen.controlplane.db;

import com.nextgen.controlplane.job.JobRecord;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC repository for persisting {@link JobRecord} to the {@code jobs} and
 * {@code job_tasks} tables.
 */
@Repository
public class JobRepository {
    private static final Logger LOG = LoggerFactory.getLogger(JobRepository.class);

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new job and its task-id references. */
    public void insert(JobRecord job) {
        try {
            jdbc.update("""
                    MERGE INTO jobs (
                        job_id, kind, state, combined_result_json, supersedes_job_id,
                        created_at_ms, updated_at_ms
                    ) KEY (job_id) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    job.getJobId(),
                    job.getKind().name(),
                    job.getState().name(),
                    job.getCombinedResultJson(),
                    job.getSupersedesJobId(),
                    job.getCreatedAtMillis(),
                    job.getUpdatedAtMillis()
            );

            // Insert task-id references (safe to ignore duplicates)
            for (String taskId : job.getTaskIds()) {
                try {
                    jdbc.update(
                            "MERGE INTO job_tasks (job_id, task_id) KEY (job_id, task_id) VALUES (?, ?)",
                            job.getJobId(), taskId);
                } catch (Exception ignored) {
                    // Duplicate task-id reference — benign on re-insert
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to insert job {} to DB: {}", job.getJobId(), e.getMessage());
        }
    }

    /** Updates job state and combined result. Called on every state transition. */
    public void updateState(JobRecord job) {
        try {
            jdbc.update("""
                    UPDATE jobs
                    SET state = ?, combined_result_json = ?, updated_at_ms = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE job_id = ?
                    """,
                    job.getState().name(),
                    job.getCombinedResultJson(),
                    job.getUpdatedAtMillis(),
                    job.getJobId()
            );
        } catch (Exception e) {
            LOG.warn("Failed to update job {} in DB: {}", job.getJobId(), e.getMessage());
        }
    }

    public Optional<JobRecord> findById(String jobId) {
        List<JobRecord> rows = jdbc.query(
                "SELECT * FROM jobs WHERE job_id = ?", JOB_MAPPER, jobId);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(withTaskIds(rows.get(0), jobId));
    }

    public List<JobRecord> findAll(int page, int size) {
        int offset = page * size;
        List<JobRecord> jobs = jdbc.query(
                "SELECT * FROM jobs ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",
                JOB_MAPPER, size, offset);
        return jobs.stream().map(j -> withTaskIds(j, j.getJobId())).toList();
    }

    public List<JobRecord> findByState(String state, int page, int size) {
        int offset = page * size;
        List<JobRecord> jobs = jdbc.query(
                "SELECT * FROM jobs WHERE state = ? ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",
                JOB_MAPPER, state, size, offset);
        return jobs.stream().map(j -> withTaskIds(j, j.getJobId())).toList();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Long.class);
        return n == null ? 0L : n;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JobRecord withTaskIds(JobRecord job, String jobId) {
        List<String> taskIds = jdbc.queryForList(
                "SELECT task_id FROM job_tasks WHERE job_id = ?", String.class, jobId);
        return JobRecord.restore(
                job.getJobId(), job.getKind(), taskIds, job.getState(),
                job.getCombinedResultJson(), Set.of(), // retriedTaskIds not persisted separately
                job.getCreatedAtMillis(), job.getUpdatedAtMillis(), job.getSupersedesJobId());
    }

    // ── RowMapper ────────────────────────────────────────────────────────────

    private static final RowMapper<JobRecord> JOB_MAPPER = (rs, rowNum) -> JobRecord.restore(
            rs.getString("job_id"),
            TaskKindDomain.valueOf(rs.getString("kind")),
            List.of(),  // task ids loaded separately
            JobStateDomain.valueOf(rs.getString("state")),
            rs.getString("combined_result_json"),
            Set.of(),
            rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"),
            rs.getString("supersedes_job_id")
    );
}
