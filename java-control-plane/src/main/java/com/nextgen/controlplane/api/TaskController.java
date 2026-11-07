package com.nextgen.controlplane.api;

import com.nextgen.controlplane.db.TaskRepository;
import com.nextgen.controlplane.db.AuditRepository;
import com.nextgen.controlplane.task.TaskRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for task history and state queries.
 *
 * <p>All reads go to the DB (persistent, paginated). Write-path (submit/cancel)
 * still goes through gRPC — the REST API is read-only for tasks for now.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskRepository taskRepository;
    private final AuditRepository auditRepository;

    public TaskController(TaskRepository taskRepository, AuditRepository auditRepository) {
        this.taskRepository = taskRepository;
        this.auditRepository = auditRepository;
    }

    /**
     * GET /api/v1/tasks?page=0&size=50&state=COMPLETED
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listTasks(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    String state) {

        List<TaskRecord> tasks = (state != null && !state.isBlank())
                ? taskRepository.findByState(state.toUpperCase(), page, size)
                : taskRepository.findAll(page, size);

        long total = (state != null && !state.isBlank())
                ? taskRepository.countByState(state.toUpperCase())
                : taskRepository.count();

        return ResponseEntity.ok(Map.of(
                "tasks",      tasks.stream().map(TaskController::toMap).toList(),
                "total",      total,
                "page",       page,
                "size",       size,
                "totalPages", (int) Math.ceil((double) total / size)
        ));
    }

    /**
     * GET /api/v1/tasks/{taskId}
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        return taskRepository.findById(taskId)
                .map(t -> ResponseEntity.ok(toMap(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/tasks/{taskId}/audit — audit trail for a specific task
     */
    @GetMapping("/{taskId}/audit")
    public ResponseEntity<List<Map<String, Object>>> getTaskAudit(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                auditRepository.findByEntity("TASK", taskId, page, size));
    }

    /**
     * GET /api/v1/tasks/node/{nodeId} — all tasks assigned to a node
     */
    @GetMapping("/node/{nodeId}")
    public ResponseEntity<List<Map<String, Object>>> tasksByNode(@PathVariable String nodeId) {
        return ResponseEntity.ok(
                taskRepository.findByNodeId(nodeId).stream().map(TaskController::toMap).toList());
    }

    // ── Stats endpoint ────────────────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "total",       taskRepository.count(),
                "queued",      taskRepository.countByState("QUEUED"),
                "dispatched",  taskRepository.countByState("DISPATCHED"),
                "running",     taskRepository.countByState("RUNNING"),
                "completed",   taskRepository.countByState("COMPLETED"),
                "failed",      taskRepository.countByState("FAILED"),
                "cancelled",   taskRepository.countByState("CANCELLED")
        ));
    }

    // ── Mapper ────────────────────────────────────────────────────────────────
    private static Map<String, Object> toMap(TaskRecord t) {
        return Map.ofEntries(
                Map.entry("taskId",          t.getTaskId()),
                Map.entry("jobId",           t.getJobId() == null ? "" : t.getJobId()),
                Map.entry("kind",            t.getKind().name()),
                Map.entry("state",           t.getState().name()),
                Map.entry("assignedNodeId",  t.getAssignedNodeId() == null ? "" : t.getAssignedNodeId()),
                Map.entry("attempt",         t.getAttempt()),
                Map.entry("resultJson",      t.getResultJson() == null ? "" : t.getResultJson()),
                Map.entry("error",           t.getError() == null ? "" : t.getError()),
                Map.entry("createdAtMs",     t.getCreatedAtMillis()),
                Map.entry("dispatchedAtMs",  t.getDispatchedAtMillis()),
                Map.entry("completedAtMs",   t.getCompletedAtMillis())
        );
    }
}
