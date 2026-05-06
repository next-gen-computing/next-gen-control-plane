package com.nextgen.desktop.ui.model;

import javafx.beans.property.*;

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

    @Override
    public String toString() {
        return getName() + " (" + getId() + ")";
    }
}
