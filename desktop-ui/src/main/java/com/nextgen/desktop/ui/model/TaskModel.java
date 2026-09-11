package com.nextgen.desktop.ui.model;

import javafx.beans.property.*;

/**
 * Model representing a submitted task and its execution progress.
 */
public class TaskModel {
    /** The only real, executable task kind the control plane knows how to run today (see TaskKind). */
    public enum TaskType {
        PRIME_COUNT_RANGE
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final StringProperty id = new SimpleStringProperty("");
    private final ObjectProperty<TaskType> type = new SimpleObjectProperty<>();
    private final ObjectProperty<TaskStatus> status = new SimpleObjectProperty<>(TaskStatus.PENDING);
    private final StringProperty payload = new SimpleStringProperty("");
    private final StringProperty result = new SimpleStringProperty("");
    private final StringProperty assignedNode = new SimpleStringProperty("");
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final StringProperty createdAt = new SimpleStringProperty("");
    private final StringProperty completedAt = new SimpleStringProperty("");

    public TaskModel() {}

    public TaskModel(String id, TaskType type, String payload) {
        this.id.set(id);
        this.type.set(type);
        this.payload.set(payload);
    }

    public StringProperty idProperty() { return id; }
    public ObjectProperty<TaskType> typeProperty() { return type; }
    public ObjectProperty<TaskStatus> statusProperty() { return status; }
    public StringProperty payloadProperty() { return payload; }
    public StringProperty resultProperty() { return result; }
    public StringProperty assignedNodeProperty() { return assignedNode; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty createdAtProperty() { return createdAt; }
    public StringProperty completedAtProperty() { return completedAt; }

    public String getId() { return id.get(); }
    public TaskType getType() { return type.get(); }
    public TaskStatus getStatus() { return status.get(); }
    public String getPayload() { return payload.get(); }
    public String getResult() { return result.get(); }
    public String getAssignedNode() { return assignedNode.get(); }
    public double getProgress() { return progress.get(); }
    public String getCreatedAt() { return createdAt.get(); }
    public String getCompletedAt() { return completedAt.get(); }

    public void setId(String id) { this.id.set(id); }
    public void setType(TaskType type) { this.type.set(type); }
    public void setStatus(TaskStatus status) { this.status.set(status); }
    public void setPayload(String payload) { this.payload.set(payload); }
    public void setResult(String result) { this.result.set(result); }
    public void setAssignedNode(String assignedNode) { this.assignedNode.set(assignedNode); }
    public void setProgress(double progress) { this.progress.set(progress); }
    public void setCreatedAt(String createdAt) { this.createdAt.set(createdAt); }
    public void setCompletedAt(String completedAt) { this.completedAt.set(completedAt); }

    public String getTypeDisplayName() {
        return switch (getType()) {
            case PRIME_COUNT_RANGE -> "Prime Count (Range)";
        };
    }

    public String getStatusDisplayName() {
        return switch (getStatus()) {
            case PENDING -> "Pending";
            case RUNNING -> "Running";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }
}
