package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.TaskModel;
import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import com.nextgen.desktop.ui.profile.HistoryEntry;
import com.nextgen.proto.ControlPlaneProto;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Submits tasks to the control plane and tracks their real outcome.
 *
 * <p>{@code SubmitTask} is an acceptance, not a final result — a real task takes real time to run.
 * This service submits, then polls {@code GetTaskStatus} until the task reaches a terminal state, so
 * a task is only ever shown as COMPLETED once the control plane says it actually finished, never the
 * instant it was merely placed on a node. There is no client-side task splitting; the control plane
 * performs placement and this service reports what it says.
 */
public class TaskExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutionService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final long POLL_INTERVAL_MS = 1_000;
    /** Safety valve: a task must never sit RUNNING in the UI forever with no way out. */
    private static final long MAX_POLL_DURATION_MS = 10 * 60 * 1_000;

    private final GrpcConnectionManager connectionManager;
    private final DesktopHistoryStore historyStore;
    private final ObservableList<TaskModel> tasks = FXCollections.observableArrayList();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-exec");
        t.setDaemon(true);
        return t;
    });

    public TaskExecutionService(GrpcConnectionManager connectionManager, DesktopHistoryStore historyStore) {
        this.connectionManager = connectionManager;
        this.historyStore = historyStore;
    }

    /** Submits a real prime-count-range task over {@code [rangeStart, rangeEnd)}. */
    public TaskModel submitTask(long rangeStart, long rangeEnd) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("{\"range_start\":%d,\"range_end\":%d}", rangeStart, rangeEnd);
        TaskModel task = new TaskModel(taskId, TaskModel.TaskType.PRIME_COUNT_RANGE, payload);
        task.setCreatedAt(LocalDateTime.now().format(TIME_FORMATTER));
        task.setStatus(TaskModel.TaskStatus.PENDING);

        tasks.add(task);
        recordHistory(task, TaskModel.TaskStatus.PENDING, payload, 0L);

        executor.submit(() -> submitAndPoll(task, payload));
        return task;
    }

    /**
     * Takes status/result explicitly rather than reading them back off {@code task}'s properties —
     * {@link #finish} sets those via {@code Platform.runLater}, so a synchronous read immediately
     * after queuing that update would race and could persist the stale pre-completion state.
     */
    private void recordHistory(TaskModel task, TaskModel.TaskStatus status, String resultOrPayload,
            long completedAtEpochMillis) {
        historyStore.upsert(new HistoryEntry(
                task.getId(), "task", task.getTypeDisplayName(), status.name(),
                clusterLabel(), submittedAtMillis(task), completedAtEpochMillis,
                resultOrPayload == null || resultOrPayload.isBlank() ? task.getPayload() : resultOrPayload));
    }

    /** The submission timestamp is only ever stored as a formatted local time string on the model
     * (for display), so history — which needs a real sortable instant — captures "now" at the point
     * this is first called rather than re-parsing that display string. */
    private final Map<String, Long> submittedAtByTaskId = new java.util.concurrent.ConcurrentHashMap<>();

    private long submittedAtMillis(TaskModel task) {
        return submittedAtByTaskId.computeIfAbsent(task.getId(), id -> System.currentTimeMillis());
    }

    private String clusterLabel() {
        return connectionManager.getCurrentHost() + ":" + connectionManager.getCurrentControlPlanePort();
    }

    private void submitAndPoll(TaskModel task, String payload) {
        ControlPlaneClient client = connectionManager.getControlPlaneClient();
        if (client == null) {
            Platform.runLater(() -> {
                task.setStatus(TaskModel.TaskStatus.FAILED);
                task.setResult("No gRPC connection available");
            });
            return;
        }

        try {
            var response = client.submitTask(
                    task.getId(), ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE, payload);

            Platform.runLater(() -> task.setAssignedNode(response.getAssignedNode()));

            if (!response.getResult().startsWith("ACCEPTED")) {
                // Rejected outright — no node to dispatch to, bad kind, etc. Nothing to poll for.
                finish(task, TaskModel.TaskStatus.FAILED, response.getResult());
                return;
            }

            Platform.runLater(() -> {
                task.setStatus(TaskModel.TaskStatus.RUNNING);
                task.setProgress(25.0);
            });
            pollUntilTerminal(task);

        } catch (ControlPlaneUnavailableException e) {
            LOG.warn("Task {} could not be submitted: {}", task.getId(), e.shortReason());
            finish(task, TaskModel.TaskStatus.FAILED, e.shortReason());
        } catch (Exception e) {
            LOG.error("Task {} failed", task.getId(), e);
            finish(task, TaskModel.TaskStatus.FAILED, "Error: " + e.getMessage());
        }
    }

    private void pollUntilTerminal(TaskModel task) {
        long deadline = System.currentTimeMillis() + MAX_POLL_DURATION_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                var status = connectionManager.getControlPlaneClient().getTaskStatus(task.getId());
                switch (status.getState()) {
                    case TASK_STATE_RUNNING -> Platform.runLater(() -> {
                        task.setStatus(TaskModel.TaskStatus.RUNNING);
                        task.setProgress(60.0);
                    });
                    case TASK_STATE_COMPLETED -> {
                        Platform.runLater(() -> task.setAssignedNode(status.getAssignedNode()));
                        finish(task, TaskModel.TaskStatus.COMPLETED, status.getResultJson());
                        return;
                    }
                    case TASK_STATE_FAILED -> {
                        Platform.runLater(() -> task.setAssignedNode(status.getAssignedNode()));
                        finish(task, TaskModel.TaskStatus.FAILED, status.getError());
                        return;
                    }
                    default -> { /* QUEUED/DISPATCHED/MIGRATING/UNSPECIFIED — still in flight, keep polling */ }
                }
            } catch (ControlPlaneUnavailableException e) {
                // A transient network hiccup mid-poll must not fail a task that may still be running
                // for real — log and keep trying until MAX_POLL_DURATION_MS gives up honestly.
                LOG.warn("Poll for task {} failed: {}", task.getId(), e.shortReason());
            }
        }

        LOG.warn("Task {} never reached a terminal state within {}ms; giving up", task.getId(), MAX_POLL_DURATION_MS);
        finish(task, TaskModel.TaskStatus.FAILED, "Polling timed out — control plane never reported completion");
    }

    private void finish(TaskModel task, TaskModel.TaskStatus status, String result) {
        Platform.runLater(() -> {
            task.setStatus(status);
            task.setResult(result);
            task.setCompletedAt(LocalDateTime.now().format(TIME_FORMATTER));
            task.setProgress(status == TaskModel.TaskStatus.COMPLETED ? 100.0 : 0.0);
        });
        recordHistory(task, status, result, System.currentTimeMillis());
        LOG.info("Task {} finished: status={}, result={}", task.getId(), status, result);
    }

    public ObservableList<TaskModel> getTasks() {
        return tasks;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
