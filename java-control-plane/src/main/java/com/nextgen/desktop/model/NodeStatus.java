package com.nextgen.desktop.model;

import javafx.beans.property.*;
import java.time.Instant;

/**
 * Node status model with observable properties for UI binding.
 */
public class NodeStatus {
    private final StringProperty nodeId = new SimpleStringProperty();
    private final StringProperty host = new SimpleStringProperty();
    private final IntegerProperty port = new SimpleIntegerProperty();
    private final DoubleProperty cpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty memoryUsage = new SimpleDoubleProperty(0.0);
    private final BooleanProperty alive = new SimpleBooleanProperty(true);
    private final ObjectProperty<Instant> lastHeartbeat = new SimpleObjectProperty<>(Instant.now());
    private final StringProperty status = new SimpleStringProperty("UNKNOWN");
    
    public NodeStatus() {}
    
    public NodeStatus(String nodeId, String host, int port) {
        this.nodeId.set(nodeId);
        this.host.set(host);
        this.port.set(port);
    }
    
    // Property accessors
    public StringProperty nodeIdProperty() { return nodeId; }
    public String getNodeId() { return nodeId.get(); }
    public void setNodeId(String value) { nodeId.set(value); }
    
    public StringProperty hostProperty() { return host; }
    public String getHost() { return host.get(); }
    public void setHost(String value) { host.set(value); }
    
    public IntegerProperty portProperty() { return port; }
    public int getPort() { return port.get(); }
    public void setPort(int value) { port.set(value); }
    
    public DoubleProperty cpuUsageProperty() { return cpuUsage; }
    public double getCpuUsage() { return cpuUsage.get(); }
    public void setCpuUsage(double value) { cpuUsage.set(value); }
    
    public DoubleProperty memoryUsageProperty() { return memoryUsage; }
    public double getMemoryUsage() { return memoryUsage.get(); }
    public void setMemoryUsage(double value) { memoryUsage.set(value); }
    
    public BooleanProperty aliveProperty() { return alive; }
    public boolean isAlive() { return alive.get(); }
    public void setAlive(boolean value) { alive.set(value); }
    
    public ObjectProperty<Instant> lastHeartbeatProperty() { return lastHeartbeat; }
    public Instant getLastHeartbeat() { return lastHeartbeat.get(); }
    public void setLastHeartbeat(Instant value) { lastHeartbeat.set(value); }
    
    public StringProperty statusProperty() { return status; }
    public String getStatus() { return status.get(); }
    public void setStatus(String value) { status.set(value); }
}
