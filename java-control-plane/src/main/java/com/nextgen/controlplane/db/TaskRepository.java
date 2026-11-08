package com.nextgen.controlplane.db;

import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskStateDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for persisting {@link TaskRecord} to the {@code tasks} table.
 *
 * <p>The in-memory {@link com.nextgen.controlplane.task.TaskRegistry} remains the primary
 * scheduling authority. This repository is the <em>durable mirror</em> that enables:
 * <ul>
 *   <li>Querying task history after restart</li>
 *   <li>Filtering/pagination in the REST API</li>
 *   <li>Audit trail entries via {@link AuditRepository}</li>
 * </ul>
 */
@Repository
public class TaskRepository {
    private static final Logger LOG = LoggerFactory.getLogger(TaskRepository.class);

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new task. Called when ControlPlaneServiceImpl accepts a SubmitTask RPC. */
    public void insert(TaskRecord task) {
        try {
            jdbc.update("""
                    MERGE INTO tasks (
                        task_id, job_id, kind, payload_json, assigned_node_id,
                        state, result_json, error, attempt,
                        created_at_ms, dispatched_at_ms, completed_at_ms
                    ) KEY (task_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    task.getTaskId(),
                    task.getJobId(),
                    task.getKind().name(),
                    task.getPayloadJson(),
                    task.getAssignedNodeId(),
                    task.getState().name(),
                    task.getResultJson(),
                    task.getError(),
                    task.getAttempt(),
                    task.getCreatedAtMillis(),
                    task.getDispatchedAtMillis(),
                    task.getCompletedAtMillis()
            );
        } catch (Exception e) {
            LOG.warn("Failed to insert task {} to DB: {}", task.getTaskId(), e.getMessage());
        }
    }

    /** Updates state (and optionally result/error) of an existing task. */
    public void updateState(TaskRecord task) {
        try {
            jdbc.update("""
                    UPDATE tasks
                    SET state = ?, assigned_node_id = ?, result_json = ?, error = ?,
                        attempt = ?, dispatched_at_ms = ?, completed_at_ms = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE task_id = ?
                    """,
                    task.getState().name(),
                    task.getAssignedNodeId(),
                    task.getResultJson(),
                    task.getError(),
                    task.getAttempt(),
                    task.getDispatchedAtMillis(),
                    task.getCompletedAtMillis(),
                    task.getTaskId()
            );
        } catch (Exception e) {
            LOG.warn("Failed to update task {} in DB: {}", task.getTaskId(), e.getMessage());
        }
    }

    public Optional<TaskRecord> findById(String taskId) {
        List<TaskRecord> rows = jdbc.query(
                "SELECT * FROM tasks WHERE task_id = ?", TASK_MAPPER, taskId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<TaskRecord> findAll(int page, int size) {
        int offset = page * size;
        return jdbc.query(
                "SELECT * FROM tasks ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",
                TASK_MAPPER, size, offset);
    }

    public List<TaskRecord> findByJobId(String jobId) {
        return jdbc.query(
                "SELECT * FROM tasks WHERE job_id = ? ORDER BY created_at_ms",
                TASK_MAPPER, jobId);
    }

    public List<TaskRecord> findByState(String state, int page, int size) {
        int offset = page * size;
        return jdbc.query(
                "SELECT * FROM tasks WHERE state = ? ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",
                TASK_MAPPER, state, size, offset);
    }

    public List<TaskRecord> findByNodeId(String nodeId) {
        return jdbc.query(
                "SELECT * FROM tasks WHERE assigned_node_id = ? ORDER BY created_at_ms DESC",
                TASK_MAPPER, nodeId);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class);
        return n == null ? 0L : n;
    }

    public long countByState(String state) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE state = ?", Long.class, state);
        return n == null ? 0L : n;
    }

    // ── RowMapper ────────────────────────────────────────────────────────────

    private static final RowMapper<TaskRecord> TASK_MAPPER = new TaskRowMapper();

    private static class TaskRowMapper implements RowMapper<TaskRecord> {
        @Override
        public TaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return TaskRecord.restore(
                    rs.getString("task_id"),
                    rs.getString("job_id"),
                    TaskKindDomain.valueOf(rs.getString("kind")),
                    rs.getString("payload_json"),
                    rs.getString("assigned_node_id"),
                    TaskStateDomain.valueOf(rs.getString("state")),
                    rs.getString("result_json"),
                    rs.getString("error"),
                    rs.getLong("created_at_ms"),
                    rs.getLong("dispatched_at_ms"),
                    rs.getLong("completed_at_ms"),
                    rs.getInt("attempt")
            );
        }
    }
}
