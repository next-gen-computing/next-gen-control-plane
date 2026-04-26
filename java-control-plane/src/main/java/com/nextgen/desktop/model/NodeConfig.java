package com.nextgen.desktop.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Configuration for Node Agent mode with Jackson serialization support.
 */
public class NodeConfig implements Serializable {
    
    @JsonProperty("nodeId")
    private String nodeId = "node-" + System.currentTimeMillis();
    
    @JsonProperty("serverHost")
    private String serverHost = "localhost";
    
    @JsonProperty("serverPort")
    private int serverPort = 50051;
    
    @JsonProperty("metricsPort")
    private int metricsPort = 9090;
    
    @JsonProperty("heartbeatInterval")
    private int heartbeatInterval = 2;
    
    @JsonProperty("autoReconnect")
    private boolean autoReconnect = true;
    
    public NodeConfig() {}
    
    // Getters and setters
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    
    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }
    
    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }
    
    public int getMetricsPort() { return metricsPort; }
    public void setMetricsPort(int metricsPort) { this.metricsPort = metricsPort; }
    
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    
    public boolean isAutoReconnect() { return autoReconnect; }
    public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }
}
