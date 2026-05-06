package com.nextgen.desktop.ui.model;

import javafx.beans.property.*;

/**
 * Summary statistics for the entire cluster.
 */
public class ClusterSummary {
    private final IntegerProperty totalNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty healthyNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty warningNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty offlineNodes = new SimpleIntegerProperty(0);
    private final DoubleProperty avgCpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty avgMemoryUsage = new SimpleDoubleProperty(0.0);
    private final IntegerProperty activeTasks = new SimpleIntegerProperty(0);
    private final StringProperty lastUpdated = new SimpleStringProperty("Never");

    public IntegerProperty totalNodesProperty() { return totalNodes; }
    public IntegerProperty healthyNodesProperty() { return healthyNodes; }
    public IntegerProperty warningNodesProperty() { return warningNodes; }
    public IntegerProperty offlineNodesProperty() { return offlineNodes; }
    public DoubleProperty avgCpuUsageProperty() { return avgCpuUsage; }
    public DoubleProperty avgMemoryUsageProperty() { return avgMemoryUsage; }
    public IntegerProperty activeTasksProperty() { return activeTasks; }
    public StringProperty lastUpdatedProperty() { return lastUpdated; }

    public int getTotalNodes() { return totalNodes.get(); }
    public int getHealthyNodes() { return healthyNodes.get(); }
    public int getWarningNodes() { return warningNodes.get(); }
    public int getOfflineNodes() { return offlineNodes.get(); }
    public double getAvgCpuUsage() { return avgCpuUsage.get(); }
    public double getAvgMemoryUsage() { return avgMemoryUsage.get(); }
    public int getActiveTasks() { return activeTasks.get(); }
    public String getLastUpdated() { return lastUpdated.get(); }

    public void setTotalNodes(int totalNodes) { this.totalNodes.set(totalNodes); }
    public void setHealthyNodes(int healthyNodes) { this.healthyNodes.set(healthyNodes); }
    public void setWarningNodes(int warningNodes) { this.warningNodes.set(warningNodes); }
    public void setOfflineNodes(int offlineNodes) { this.offlineNodes.set(offlineNodes); }
    public void setAvgCpuUsage(double avgCpuUsage) { this.avgCpuUsage.set(avgCpuUsage); }
    public void setAvgMemoryUsage(double avgMemoryUsage) { this.avgMemoryUsage.set(avgMemoryUsage); }
    public void setActiveTasks(int activeTasks) { this.activeTasks.set(activeTasks); }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated.set(lastUpdated); }
}
