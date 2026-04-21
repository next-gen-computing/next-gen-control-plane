package com.nextgen.controlplane;

/**
 * In-memory record for a registered node.
 * Thread-safety: fields are volatile so the HeartbeatMonitor can read consistent values.
 */
public class NodeRecord {
    private final String nodeId;
    private final String ip;
    private final int port;
    private final String hostname;

    private volatile float cpuUsage;
    private volatile float memoryUsage;
    private volatile long lastHeartbeatMillis;
    private volatile String status; // ALIVE or SUSPECTED_DEAD

    public NodeRecord(String nodeId, String ip, int port, String hostname) {
        this.nodeId = nodeId;
        this.ip = ip;
        this.port = port;
        this.hostname = hostname;
        this.cpuUsage = 0.0f;
        this.memoryUsage = 0.0f;
        this.lastHeartbeatMillis = System.currentTimeMillis();
        this.status = "ALIVE";
    }

    // ── Getters ──────────────────────────────────────
    public String getNodeId()             { return nodeId; }
    public String getIp()                 { return ip; }
    public int    getPort()               { return port; }
    public String getHostname()           { return hostname; }
    public float  getCpuUsage()           { return cpuUsage; }
    public float  getMemoryUsage()        { return memoryUsage; }
    public long   getLastHeartbeatMillis(){ return lastHeartbeatMillis; }
    public String getStatus()             { return status; }

    // ── Setters ──────────────────────────────────────
    public void setCpuUsage(float cpuUsage)                   { this.cpuUsage = cpuUsage; }
    public void setMemoryUsage(float memoryUsage)             { this.memoryUsage = memoryUsage; }
    public void setLastHeartbeatMillis(long lastHeartbeatMillis) { this.lastHeartbeatMillis = lastHeartbeatMillis; }
    public void setStatus(String status)                      { this.status = status; }

    @Override
    public String toString() {
        return String.format("NodeRecord{id=%s, ip=%s, cpu=%.1f%%, mem=%.1f%%, status=%s}",
                nodeId, ip, cpuUsage, memoryUsage, status);
    }
}
