package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.desktop.ui.model.TaskModel;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles task submission, splitting, progress tracking, and result aggregation.
 */
public class TaskExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutionService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final GrpcConnectionManager connectionManager;
    private final ObservableList<TaskModel> tasks = FXCollections.observableArrayList();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-exec");
        t.setDaemon(true);
        return t;
    });

    public TaskExecutionService(GrpcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public TaskModel submitTask(TaskModel.TaskType type, String parameters) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        TaskModel task = new TaskModel(taskId, type, parameters);
        task.setCreatedAt(LocalDateTime.now().format(TIME_FORMATTER));
        task.setStatus(TaskModel.TaskStatus.PENDING);

        tasks.add(task);

        executor.submit(() -> executeTask(task));
        return task;
    }

    private void executeTask(TaskModel task) {
        try {
            Platform.runLater(() -> task.setStatus(TaskModel.TaskStatus.RUNNING));

            ControlPlaneClient client = connectionManager.getControlPlaneClient();
            if (client == null) {
                Platform.runLater(() -> {
                    task.setStatus(TaskModel.TaskStatus.FAILED);
                    task.setResult("No gRPC connection available");
                });
                return;
            }

            // Simulate progress
            for (int i = 0; i <= 100; i += 10) {
                final int progress = i;
                Platform.runLater(() -> task.setProgress(progress));
                Thread.sleep(300);
            }

            // Submit to gRPC
            var response = client.submitTask(task.getId(), buildPayload(task));

            Platform.runLater(() -> {
                task.setStatus(TaskModel.TaskStatus.COMPLETED);
                task.setResult(response.getResult());
                task.setAssignedNode(response.getAssignedNode());
                task.setCompletedAt(LocalDateTime.now().format(TIME_FORMATTER));
                task.setProgress(100.0);
            });

            LOG.info("Task {} completed with result: {}", task.getId(), response.getResult());

        } catch (Exception e) {
            LOG.error("Task {} failed", task.getId(), e);
            Platform.runLater(() -> {
                task.setStatus(TaskModel.TaskStatus.FAILED);
                task.setResult("Error: " + e.getMessage());
            });
        }
    }

    private String buildPayload(TaskModel task) {
        return String.format("{\"type\":\"%s\",\"params\":\"%s\"}",
                task.getType().name(), task.getPayload());
    }

    public ObservableList<TaskModel> getTasks() {
        return tasks;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
