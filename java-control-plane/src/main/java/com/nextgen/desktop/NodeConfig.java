package com.nextgen.desktop;

import java.io.Serializable;

/**
 * Configuration for Node Agent mode
 */
public class NodeConfig implements Serializable {
    private String nodeId = "node-" + System.currentTimeMillis();
    private String serverHost = "localhost";
    private int serverPort = 50051;
    private int metricsPort = 9090;
    private int heartbeatInterval = 2;
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
