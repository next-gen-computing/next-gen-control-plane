package com.nextgen.controlplane.db;

import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeStatus;
import com.nextgen.proto.ControlPlaneProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC repository for persisting {@link NodeRecord} state to the {@code nodes} table.
 *
 * <p>The in-memory {@link com.nextgen.controlplane.NodeRegistry} is the primary source
 * of truth for live scheduling decisions. This repository is the durable mirror
 * that survives restarts and enables the REST API to serve historical data.
 *
 * <p>DB write failures are logged and swallowed — they must never affect the
 * gRPC heartbeat path or the in-memory registry.
 */
@Repository
public class NodeRepository {
    private static final Logger LOG = LoggerFactory.getLogger(NodeRepository.class);

    private final JdbcTemplate jdbc;

    public NodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upserts a node record. Called on every heartbeat arrival.
     * Uses H2-compatible MERGE syntax (also works with Postgres via H2 MySQL mode).
     */
    public void upsert(NodeRecord node) {
        try {
            jdbc.update("""
                    MERGE INTO nodes (
                        node_id, hostname, ip, port, status, agent_version,
                        cpu_cores, total_memory_bytes, os_name,
                        cpu_usage, memory_usage, battery_percent, risk_score,
                        last_heartbeat_ms, updated_at
                    ) KEY (node_id) VALUES (
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, CURRENT_TIMESTAMP
                    )
                    """,
                    node.getNodeId(),
                    node.getHostname(),
                    node.getIp(),
                    node.getPort(),
                    node.getStatus().name(),
                    node.getAgentVersion(),
                    node.getCapabilities().getCpuCores(),
                    node.getCapabilities().getTotalMemoryBytes(),
                    node.getCapabilities().getOsName(),
                    node.isCpuStale()    ? null : (double) node.getCpuUsage(),
                    node.isMemoryStale() ? null : (double) node.getMemoryUsage(),
                    null,
                    node.isAtRisk() ? node.getRiskScore() : null,
                    node.getLastHeartbeatMillis()
            );
        } catch (Exception e) {
            LOG.warn("Failed to upsert node {} to DB: {}", node.getNodeId(), e.getMessage());
        }
    }

    /** Updates risk score for a single node (called by RiskMonitor). */
    public void updateRiskScore(String nodeId, double riskScore) {
        try {
            jdbc.update(
                    "UPDATE nodes SET risk_score = ?, updated_at = CURRENT_TIMESTAMP WHERE node_id = ?",
                    riskScore, nodeId);
        } catch (Exception e) {
            LOG.warn("Failed to update risk score for node {}: {}", nodeId, e.getMessage());
        }
    }

    /** Marks a node DEAD (called by HeartbeatMonitor on timeout). */
    public void markDead(String nodeId) {
        try {
            jdbc.update(
                    "UPDATE nodes SET status = 'DEAD', updated_at = CURRENT_TIMESTAMP WHERE node_id = ?",
                    nodeId);
        } catch (Exception e) {
            LOG.warn("Failed to mark node {} dead in DB: {}", nodeId, e.getMessage());
        }
    }

    public List<NodeRecord> findAll() {
        return jdbc.query("SELECT * FROM nodes ORDER BY created_at", this::mapRow);
    }

    public Optional<NodeRecord> findById(String nodeId) {
        List<NodeRecord> rows = jdbc.query(
                "SELECT * FROM nodes WHERE node_id = ?", this::mapRow, nodeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> findByStatus(String status) {
        return jdbc.queryForList(
                "SELECT * FROM nodes WHERE status = ? ORDER BY last_heartbeat_ms DESC", status);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM nodes", Long.class);
        return n == null ? 0L : n;
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    private NodeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        // Reconstruct a NodeCapabilities proto from the flat columns
        ControlPlaneProto.NodeCapabilities caps = ControlPlaneProto.NodeCapabilities.newBuilder()
                .setCpuCores(rs.getInt("cpu_cores"))
                .setTotalMemoryBytes(rs.getLong("total_memory_bytes"))
                .setOsName(rs.getString("os_name") == null ? "" : rs.getString("os_name"))
                .build();

        String statusStr = rs.getString("status");
        NodeStatus status;
        try {
            status = NodeStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            status = NodeStatus.ALIVE;
        }

        long heartbeatMs = rs.getLong("last_heartbeat_ms");
        String nodeId    = rs.getString("node_id");
        String hostname  = rs.getString("hostname");
        String ip        = rs.getString("ip");
        int    port      = rs.getInt("port");
        String agentVer  = rs.getString("agent_version");

        // Build a fresh record with the persisted capabilities, then apply status.
        NodeRecord record = NodeRecord.fresh(nodeId, ip, port,
                hostname == null ? nodeId : hostname,
                caps,
                agentVer == null ? "" : agentVer,
                heartbeatMs);

        if (status != NodeStatus.ALIVE) {
            record = record.withStatus(status);
        }
        return record;
    }
}
