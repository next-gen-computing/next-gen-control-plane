package com.nextgen.desktop.ui.model;

import javafx.beans.property.*;

/**
 * Summary statistics for the entire cluster.
 *
 * <p>{@code avgCpuUsage} and {@code avgMemoryUsage} use a negative sentinel to mean "nothing was
 * measurable" — an empty or wholly-unreadable cluster must not report {@code 0.0}, which is
 * indistinguishable from a real idle reading. Use {@link #getAvgCpuUsageText()} for display.
 */
public class ClusterSummary {
    private final IntegerProperty totalNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty healthyNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty warningNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty offlineNodes = new SimpleIntegerProperty(0);
    // Default to the "nothing measurable" sentinel, not 0.0: a ClusterSummary that has never been
    // through updateClusterSummary() yet (e.g. read via the local UI server before monitoring has
    // started) must not read as "measured, and idle" — see hasCpuReading()/hasMemoryReading() below.
    private final DoubleProperty avgCpuUsage = new SimpleDoubleProperty(-1);
    private final DoubleProperty avgMemoryUsage = new SimpleDoubleProperty(-1);
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

    /** True when at least one node supplied a usable CPU reading. */
    public boolean hasCpuReading() { return avgCpuUsage.get() >= 0; }

    /** True when at least one node supplied a usable memory reading. */
    public boolean hasMemoryReading() { return avgMemoryUsage.get() >= 0; }

    public String getAvgCpuUsageText() { return format(avgCpuUsage.get()); }

    public String getAvgMemoryUsageText() { return format(avgMemoryUsage.get()); }

    /**
     * Cluster health as a percentage of nodes reporting healthy, or -1 when there is nothing to
     * assess. The dashboard previously showed a green 100% for an empty or disconnected cluster,
     * because it computed {@code total > 0 ? healthy * 100.0 / total : 100}.
     */
    public double getHealthPercent() {
        int total = totalNodes.get();
        return total > 0 ? healthyNodes.get() * 100.0 / total : -1;
    }

    public boolean hasHealthReading() { return getHealthPercent() >= 0; }

    private static String format(double value) {
        return value < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f%%", value);
    }
}
