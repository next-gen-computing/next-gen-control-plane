package com.nextgen.desktop.ui.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/**
 * Model representing a connected node with real-time metrics.
 * Uses JavaFX properties for automatic UI binding.
 */
public class NodeModel {
    private final StringProperty id = new SimpleStringProperty("");
    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty hostname = new SimpleStringProperty("");
    private final StringProperty ip = new SimpleStringProperty("");
    private final IntegerProperty port = new SimpleIntegerProperty(0);
    private final StringProperty status = new SimpleStringProperty("OFFLINE");
    private final DoubleProperty cpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty memoryUsage = new SimpleDoubleProperty(0.0);
    private final StringProperty lastHeartbeat = new SimpleStringProperty("Never");
    private final StringProperty failureProbability = new SimpleStringProperty("N/A");
    private final StringProperty predictedLoad = new SimpleStringProperty("N/A");
    private final StringProperty recommendation = new SimpleStringProperty("N/A");

    /**
     * True when the corresponding usage figure is the last known value rather than a current one,
     * because the node could not read the metric. The UI must render these as "n/a", never as the
     * retained number — that number is history, not a measurement of now.
     */
    private final BooleanProperty cpuStale = new SimpleBooleanProperty(true);
    private final BooleanProperty memoryStale = new SimpleBooleanProperty(true);

    // Stage RR: richer per-node data the control plane already collects and reports (NodeInfo/
    // NodeCapabilities) but this model didn't previously map — battery/AC power, predictive risk score
    // and its real reasons, heartbeat RTT trend, and declared hardware. Every "available"/"known" flag
    // mirrors the corresponding proto field's own honesty discipline: false means genuinely unknown,
    // never a fabricated reading.
    private final DoubleProperty batteryPercent = new SimpleDoubleProperty(0.0);
    private final BooleanProperty batteryAvailable = new SimpleBooleanProperty(false);
    private final BooleanProperty onAcPower = new SimpleBooleanProperty(false);
    private final BooleanProperty onAcPowerKnown = new SimpleBooleanProperty(false);
    private final DoubleProperty riskScore = new SimpleDoubleProperty(0.0);
    private final BooleanProperty atRisk = new SimpleBooleanProperty(false);
    private final ObservableList<String> riskReasons = FXCollections.observableArrayList();
    private final DoubleProperty previousRttSeconds = new SimpleDoubleProperty(0.0);
    private final BooleanProperty previousRttAvailable = new SimpleBooleanProperty(false);
    private final IntegerProperty cpuCores = new SimpleIntegerProperty(0);
    private final LongProperty totalMemoryBytes = new SimpleLongProperty(0L);

    public NodeModel() {}

    public NodeModel(String id, String name, String hostname, String ip, int port) {
        this.id.set(id);
        this.name.set(name);
        this.hostname.set(hostname);
        this.ip.set(ip);
        this.port.set(port);
    }

    // Property accessors for JavaFX binding
    public StringProperty idProperty() { return id; }
    public StringProperty nameProperty() { return name; }
    public StringProperty hostnameProperty() { return hostname; }
    public StringProperty ipProperty() { return ip; }
    public IntegerProperty portProperty() { return port; }
    public StringProperty statusProperty() { return status; }
    public DoubleProperty cpuUsageProperty() { return cpuUsage; }
    public DoubleProperty memoryUsageProperty() { return memoryUsage; }
    public StringProperty lastHeartbeatProperty() { return lastHeartbeat; }
    public StringProperty failureProbabilityProperty() { return failureProbability; }
    public StringProperty predictedLoadProperty() { return predictedLoad; }
    public StringProperty recommendationProperty() { return recommendation; }
    public BooleanProperty cpuStaleProperty() { return cpuStale; }
    public BooleanProperty memoryStaleProperty() { return memoryStale; }
    public DoubleProperty batteryPercentProperty() { return batteryPercent; }
    public BooleanProperty batteryAvailableProperty() { return batteryAvailable; }
    public BooleanProperty onAcPowerProperty() { return onAcPower; }
    public BooleanProperty onAcPowerKnownProperty() { return onAcPowerKnown; }
    public DoubleProperty riskScoreProperty() { return riskScore; }
    public BooleanProperty atRiskProperty() { return atRisk; }
    public ObservableList<String> getRiskReasons() { return riskReasons; }
    public DoubleProperty previousRttSecondsProperty() { return previousRttSeconds; }
    public BooleanProperty previousRttAvailableProperty() { return previousRttAvailable; }
    public IntegerProperty cpuCoresProperty() { return cpuCores; }
    public LongProperty totalMemoryBytesProperty() { return totalMemoryBytes; }

    // Convenience getters
    public String getId() { return id.get(); }
    public String getName() { return name.get(); }
    public String getHostname() { return hostname.get(); }
    public String getIp() { return ip.get(); }
    public int getPort() { return port.get(); }
    public String getStatus() { return status.get(); }
    public double getCpuUsage() { return cpuUsage.get(); }
    public double getMemoryUsage() { return memoryUsage.get(); }
    public String getLastHeartbeat() { return lastHeartbeat.get(); }
    public String getFailureProbability() { return failureProbability.get(); }
    public String getPredictedLoad() { return predictedLoad.get(); }
    public String getRecommendation() { return recommendation.get(); }
    public boolean isCpuStale() { return cpuStale.get(); }
    public boolean isMemoryStale() { return memoryStale.get(); }
    public double getBatteryPercent() { return batteryPercent.get(); }
    public boolean isBatteryAvailable() { return batteryAvailable.get(); }
    public boolean isOnAcPower() { return onAcPower.get(); }
    public boolean isOnAcPowerKnown() { return onAcPowerKnown.get(); }
    public double getRiskScore() { return riskScore.get(); }
    public boolean isAtRisk() { return atRisk.get(); }
    public double getPreviousRttSeconds() { return previousRttSeconds.get(); }
    public boolean isPreviousRttAvailable() { return previousRttAvailable.get(); }
    public int getCpuCores() { return cpuCores.get(); }
    public long getTotalMemoryBytes() { return totalMemoryBytes.get(); }

    /** CPU for display: a percentage when measured, {@code "n/a"} when the reading is not current. */
    public String getCpuUsageText() { return formatUsage(getCpuUsage(), isCpuStale()); }

    /** Memory for display, with the same honesty rule as {@link #getCpuUsageText()}. */
    public String getMemoryUsageText() { return formatUsage(getMemoryUsage(), isMemoryStale()); }

    private static String formatUsage(double value, boolean stale) {
        return stale ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f%%", value);
    }

    // Convenience setters
    public void setId(String id) { this.id.set(id); }
    public void setName(String name) { this.name.set(name); }
    public void setHostname(String hostname) { this.hostname.set(hostname); }
    public void setIp(String ip) { this.ip.set(ip); }
    public void setPort(int port) { this.port.set(port); }
    public void setStatus(String status) { this.status.set(status); }
    public void setCpuUsage(double cpuUsage) { this.cpuUsage.set(cpuUsage); }
    public void setMemoryUsage(double memoryUsage) { this.memoryUsage.set(memoryUsage); }
    public void setLastHeartbeat(String lastHeartbeat) { this.lastHeartbeat.set(lastHeartbeat); }
    public void setFailureProbability(String failureProbability) { this.failureProbability.set(failureProbability); }
    public void setPredictedLoad(String predictedLoad) { this.predictedLoad.set(predictedLoad); }
    public void setRecommendation(String recommendation) { this.recommendation.set(recommendation); }
    public void setCpuStale(boolean stale) { this.cpuStale.set(stale); }
    public void setMemoryStale(boolean stale) { this.memoryStale.set(stale); }
    public void setBatteryPercent(double v) { this.batteryPercent.set(v); }
    public void setBatteryAvailable(boolean v) { this.batteryAvailable.set(v); }
    public void setOnAcPower(boolean v) { this.onAcPower.set(v); }
    public void setOnAcPowerKnown(boolean v) { this.onAcPowerKnown.set(v); }
    public void setRiskScore(double v) { this.riskScore.set(v); }
    public void setAtRisk(boolean v) { this.atRisk.set(v); }
    public void setRiskReasons(List<String> reasons) { this.riskReasons.setAll(reasons); }
    public void setPreviousRttSeconds(double v) { this.previousRttSeconds.set(v); }
    public void setPreviousRttAvailable(boolean v) { this.previousRttAvailable.set(v); }
    public void setCpuCores(int v) { this.cpuCores.set(v); }
    public void setTotalMemoryBytes(long v) { this.totalMemoryBytes.set(v); }

    @Override
    public String toString() {
        return getName() + " (" + getId() + ")";
    }
}
