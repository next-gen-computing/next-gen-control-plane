package com.nextgen.controlplane.api;

import com.nextgen.controlplane.db.JobRepository;
import com.nextgen.controlplane.db.TaskRepository;
import com.nextgen.controlplane.db.AuditRepository;
import com.nextgen.controlplane.job.JobRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for job queries (paginated, filterable by state).
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final AuditRepository auditRepository;

    public JobController(JobRepository jobRepository,
                         TaskRepository taskRepository,
                         AuditRepository auditRepository) {
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.auditRepository = auditRepository;
    }

    /**
     * GET /api/v1/jobs?page=0&size=50&state=RUNNING
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listJobs(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    String state) {

        List<JobRecord> jobs = (state != null && !state.isBlank())
                ? jobRepository.findByState(state.toUpperCase(), page, size)
                : jobRepository.findAll(page, size);

        long total = jobRepository.count();

        return ResponseEntity.ok(Map.of(
                "jobs",       jobs.stream().map(JobController::toMap).toList(),
                "total",      total,
                "page",       page,
                "size",       size,
                "totalPages", (int) Math.ceil((double) total / size)
        ));
    }

    /**
     * GET /api/v1/jobs/{jobId}
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        return jobRepository.findById(jobId)
                .map(j -> ResponseEntity.ok(toMap(j)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/jobs/{jobId}/tasks — all tasks belonging to this job
     */
    @GetMapping("/{jobId}/tasks")
    public ResponseEntity<List<Map<String, Object>>> getJobTasks(@PathVariable String jobId) {
        return ResponseEntity.ok(
                taskRepository.findByJobId(jobId).stream()
                        .map(t -> Map.<String, Object>ofEntries(
                                Map.entry("taskId",         t.getTaskId()),
                                Map.entry("kind",           t.getKind().name()),
                                Map.entry("state",          t.getState().name()),
                                Map.entry("assignedNodeId", t.getAssignedNodeId() == null ? "" : t.getAssignedNodeId()),
                                Map.entry("attempt",        t.getAttempt()),
                                Map.entry("createdAtMs",    t.getCreatedAtMillis()),
                                Map.entry("completedAtMs",  t.getCompletedAtMillis())
                        ))
                        .toList());
    }

    /**
     * GET /api/v1/jobs/{jobId}/audit — audit trail for a specific job
     */
    @GetMapping("/{jobId}/audit")
    public ResponseEntity<List<Map<String, Object>>> getJobAudit(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(auditRepository.findByEntity("JOB", jobId, page, size));
    }

    // ── Mapper ────────────────────────────────────────────────────────────────
    private static Map<String, Object> toMap(JobRecord j) {
        return Map.of(
                "jobId",              j.getJobId(),
                "kind",               j.getKind().name(),
                "state",              j.getState().name(),
                "taskCount",          j.getTaskIds().size(),
                "taskIds",            j.getTaskIds(),
                "combinedResultJson", j.getCombinedResultJson() == null ? "" : j.getCombinedResultJson(),
                "supersedesJobId",    j.getSupersedesJobId() == null ? "" : j.getSupersedesJobId(),
                "createdAtMs",        j.getCreatedAtMillis(),
                "updatedAtMs",        j.getUpdatedAtMillis()
        );
    }
}
