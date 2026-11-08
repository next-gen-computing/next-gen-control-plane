package com.nextgen.controlplane.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Immutable append-only audit log repository.
 *
 * <p>Every significant state change in the system is recorded here:
 * task state transitions, job lifecycle, node up/down, account events,
 * and enrollment. Rows are never updated or deleted.
 */
@Repository
public class AuditRepository {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRepository.class);

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an audit event. Never throws — audit failures must not affect
     * the primary operation that triggered them.
     */
    public void log(String actorId, String action, String entityType, String entityId, String detailsJson) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details_json)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    actorId, action, entityType, entityId,
                    detailsJson == null ? "{}" : detailsJson);
        } catch (Exception e) {
            LOG.warn("Failed to write audit log ({} {} {}): {}", action, entityType, entityId, e.getMessage());
        }
    }

    /** Convenience: log with a simple key=value details string. */
    public void log(String actorId, String action, String entityType, String entityId) {
        log(actorId, action, entityType, entityId, "{}");
    }

    /** Paginated query — newest first. */
    public List<Map<String, Object>> findAll(int page, int size) {
        int offset = page * size;
        return jdbc.queryForList(
                "SELECT * FROM audit_log ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                size, offset);
    }

    public List<Map<String, Object>> findByEntity(String entityType, String entityId, int page, int size) {
        int offset = page * size;
        return jdbc.queryForList(
                "SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                entityType, entityId, size, offset);
    }

    public List<Map<String, Object>> findByActor(String actorId, int page, int size) {
        int offset = page * size;
        return jdbc.queryForList(
                "SELECT * FROM audit_log WHERE actor_id = ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                actorId, size, offset);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);
        return n == null ? 0L : n;
    }
}
