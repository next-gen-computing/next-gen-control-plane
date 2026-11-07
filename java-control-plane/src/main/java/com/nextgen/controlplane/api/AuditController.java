package com.nextgen.controlplane.api;

import com.nextgen.controlplane.db.AuditRepository;
import com.nextgen.controlplane.db.NodeRepository;
import com.nextgen.controlplane.db.TaskRepository;
import com.nextgen.controlplane.db.JobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the audit log and cluster-wide analytics.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code GET /api/v1/audit}              — paginated event trail, newest first</li>
 *   <li>{@code GET /api/v1/audit/entity/{type}/{id}} — events for a specific entity</li>
 *   <li>{@code GET /api/v1/audit/actor/{id}}   — events triggered by a specific actor</li>
 *   <li>{@code GET /api/v1/cluster/stats}      — cluster-wide aggregate counters</li>
 * </ul>
 */
@RestController
public class AuditController {

    private final AuditRepository auditRepository;
    private final NodeRepository  nodeRepository;
    private final TaskRepository  taskRepository;
    private final JobRepository   jobRepository;

    public AuditController(AuditRepository auditRepository,
                           NodeRepository  nodeRepository,
                           TaskRepository  taskRepository,
                           JobRepository   jobRepository) {
        this.auditRepository = auditRepository;
        this.nodeRepository  = nodeRepository;
        this.taskRepository  = taskRepository;
        this.jobRepository   = jobRepository;
    }

    // ── Audit log endpoints ───────────────────────────────────────────────────

    @GetMapping("/api/v1/audit")
    public ResponseEntity<Map<String, Object>> listAudit(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        List<Map<String, Object>> events = auditRepository.findAll(page, size);
        long total = auditRepository.count();

        return ResponseEntity.ok(Map.of(
                "events",     events,
                "total",      total,
                "page",       page,
                "size",       size,
                "totalPages", (int) Math.ceil((double) total / size)
        ));
    }

    @GetMapping("/api/v1/audit/entity/{entityType}/{entityId}")
    public ResponseEntity<List<Map<String, Object>>> auditForEntity(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                auditRepository.findByEntity(entityType.toUpperCase(), entityId, page, size));
    }

    @GetMapping("/api/v1/audit/actor/{actorId}")
    public ResponseEntity<List<Map<String, Object>>> auditForActor(
            @PathVariable String actorId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(auditRepository.findByActor(actorId, page, size));
    }

    // ── Cluster-wide statistics ───────────────────────────────────────────────

    /**
     * GET /api/v1/cluster/stats — aggregate counts across nodes, tasks, and jobs.
     * Used by the dashboard overview tiles.
     */
    @GetMapping("/api/v1/cluster/stats")
    public ResponseEntity<Map<String, Object>> clusterStats() {
        return ResponseEntity.ok(Map.of(
                "nodes", Map.of(
                        "total", nodeRepository.count(),
                        "alive", nodeRepository.findByStatus("ALIVE").size(),
                        "dead",  nodeRepository.findByStatus("DEAD").size()
                ),
                "tasks", Map.of(
                        "total",      taskRepository.count(),
                        "queued",     taskRepository.countByState("QUEUED"),
                        "running",    taskRepository.countByState("RUNNING"),
                        "completed",  taskRepository.countByState("COMPLETED"),
                        "failed",     taskRepository.countByState("FAILED")
                ),
                "jobs", Map.of(
                        "total",     jobRepository.count()
                ),
                "audit", Map.of(
                        "totalEvents", auditRepository.count()
                )
        ));
    }

    /**
     * GET /api/v1/health — simple health check for load balancers / k8s probes.
     */
    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "next-gen-control-plane"));
    }
}
