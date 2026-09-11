package com.nextgen.desktop.ui.model;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Model representing a submitted job — N real sub-tasks over {@code [rangeStart, rangeEnd)}, split
 * and dispatched by the control plane, reduced server-side once every sub-task is terminal.
 */
public class JobModel {
    public enum JobStatus {
        RUNNING,
        COMPLETED,
        PARTIAL_FAILURE,
        FAILED
    }

    private final StringProperty id = new SimpleStringProperty("");
    private final LongProperty rangeStart = new SimpleLongProperty(0);
    private final LongProperty rangeEnd = new SimpleLongProperty(0);
    private final ObjectProperty<JobStatus> status = new SimpleObjectProperty<>(JobStatus.RUNNING);
    private final IntegerProperty subTaskCount = new SimpleIntegerProperty(0);
    private final IntegerProperty completedCount = new SimpleIntegerProperty(0);
    private final IntegerProperty failedCount = new SimpleIntegerProperty(0);
    private final StringProperty combinedResult = new SimpleStringProperty("");
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final StringProperty createdAt = new SimpleStringProperty("");
    private final StringProperty updatedAt = new SimpleStringProperty("");

    public JobModel() {
    }

    public JobModel(String id, long rangeStart, long rangeEnd, int subTaskCount) {
        this.id.set(id);
        this.rangeStart.set(rangeStart);
        this.rangeEnd.set(rangeEnd);
        this.subTaskCount.set(subTaskCount);
    }

    public StringProperty idProperty()             { return id; }
    public LongProperty rangeStartProperty()        { return rangeStart; }
    public LongProperty rangeEndProperty()          { return rangeEnd; }
    public ObjectProperty<JobStatus> statusProperty() { return status; }
    public IntegerProperty subTaskCountProperty()   { return subTaskCount; }
    public IntegerProperty completedCountProperty() { return completedCount; }
    public IntegerProperty failedCountProperty()    { return failedCount; }
    public StringProperty combinedResultProperty()  { return combinedResult; }
    public DoubleProperty progressProperty()        { return progress; }
    public StringProperty createdAtProperty()       { return createdAt; }
    public StringProperty updatedAtProperty()       { return updatedAt; }

    public String getId()                { return id.get(); }
    public long getRangeStart()          { return rangeStart.get(); }
    public long getRangeEnd()            { return rangeEnd.get(); }
    public JobStatus getStatus()         { return status.get(); }
    public int getSubTaskCount()         { return subTaskCount.get(); }
    public int getCompletedCount()       { return completedCount.get(); }
    public int getFailedCount()          { return failedCount.get(); }
    public String getCombinedResult()    { return combinedResult.get(); }
    public double getProgress()          { return progress.get(); }
    public String getCreatedAt()         { return createdAt.get(); }
    public String getUpdatedAt()         { return updatedAt.get(); }

    public void setStatus(JobStatus status)             { this.status.set(status); }
    public void setSubTaskCount(int subTaskCount)       { this.subTaskCount.set(subTaskCount); }
    public void setCompletedCount(int completedCount)   { this.completedCount.set(completedCount); }
    public void setFailedCount(int failedCount)         { this.failedCount.set(failedCount); }
    public void setCombinedResult(String combinedResult) { this.combinedResult.set(combinedResult); }
    public void setProgress(double progress)            { this.progress.set(progress); }
    public void setCreatedAt(String createdAt)          { this.createdAt.set(createdAt); }
    public void setUpdatedAt(String updatedAt)          { this.updatedAt.set(updatedAt); }

    public String getStatusDisplayName() {
        return switch (getStatus()) {
            case RUNNING -> "Running";
            case COMPLETED -> "Completed";
            case PARTIAL_FAILURE -> "Partial Failure";
            case FAILED -> "Failed";
        };
    }
}
