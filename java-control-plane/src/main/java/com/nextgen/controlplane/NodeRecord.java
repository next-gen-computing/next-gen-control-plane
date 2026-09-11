package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;

import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of a registered node.
 *
 * <p><b>Why immutable.</b> The previous implementation used independently-{@code volatile} mutable
 * fields. Each individual write was atomic, but the record as a whole never was, which produced two
 * real bugs:
 *
 * <ul>
 *   <li><i>Lost update.</i> A heartbeat thread could read the record reference, a registration
 *       thread could then replace the map entry, and the heartbeat would write into the now-orphaned
 *       object. The heartbeat was silently discarded and the node was marked SUSPECTED_DEAD despite
 *       an unbroken heartbeat stream.</li>
 *   <li><i>Torn read.</i> A reader could observe a new CPU value alongside an old status.</li>
 * </ul>
 *
 * <p>With an immutable record, one {@code map.get()} yields a fully consistent snapshot with no
 * locking on the read path, and every mutation is funnelled through
 * {@link NodeRegistry}'s {@code compute}/{@code computeIfPresent} calls, which hold the per-key bin
 * lock and so make read-decide-write atomic per node.
 *
 * <p><b>Staleness.</b> {@code cpuStale}/{@code memoryStale} mark a value as the last known-good
 * reading rather than a current one, set when an agent reports that it could not read the metric.
 * The value is retained so history is not lost, but consumers must render it as unavailable. This is
 * what prevents an unreadable OS metric from being displayed as a real-looking {@code 0.0}.
 */
public final class NodeRecord {

    private final String nodeId;
    private final String ip;
    private final int port;
    private final String hostname;

    private final float cpuUsage;
    private final float memoryUsage;
    private final boolean cpuStale;
    private final boolean memoryStale;

    private final long lastHeartbeatMillis;
    private final long registeredAtMillis;
    private final long heartbeatSequence;

    private final NodeStatus status;
    private final String agentVersion;
    private final ControlPlaneProto.NodeCapabilities capabilities;
    private final ControlPlaneProto.CertificateInfo certificate;

    /**
     * Predicted-failure risk, as last assessed by {@code RiskMonitor} against this node's
     * {@code NodeHistory} trend. Advisory, alongside {@link #status} rather than folded into it: a
     * node can be simultaneously {@code ALIVE} (confirmed reachable right now) and {@code atRisk}
     * (predicted to fail soon) — conflating the two would corrupt the existing reactive path's
     * meaning. {@code riskAssessedAtMillis == 0} means no assessment has ever run for this node yet.
     */
    private final double riskScore;
    private final boolean atRisk;
    private final List<String> riskReasons;
    private final long riskAssessedAtMillis;

    /**
     * Convenience constructor for a freshly-registered node with no readings yet.
     *
     * <p>CPU and memory start at {@code 0} but are marked <b>stale</b>, because no reading has been
     * taken — zero here means "unknown", not "idle".
     */
    public NodeRecord(String nodeId, String ip, int port, String hostname) {
        this(nodeId, ip, port, hostname,
                0.0f, 0.0f, true, true,
                System.currentTimeMillis(), System.currentTimeMillis(), 0L,
                NodeStatus.ALIVE, "",
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(),
                ControlPlaneProto.CertificateInfo.getDefaultInstance(),
                0.0, false, List.of(), 0L);
    }

    private NodeRecord(String nodeId, String ip, int port, String hostname,
                       float cpuUsage, float memoryUsage, boolean cpuStale, boolean memoryStale,
                       long lastHeartbeatMillis, long registeredAtMillis, long heartbeatSequence,
                       NodeStatus status, String agentVersion,
                       ControlPlaneProto.NodeCapabilities capabilities,
                       ControlPlaneProto.CertificateInfo certificate,
                       double riskScore, boolean atRisk, List<String> riskReasons, long riskAssessedAtMillis) {
        this.nodeId = nodeId;
        this.ip = ip;
        this.port = port;
        this.hostname = hostname;
        this.cpuUsage = cpuUsage;
        this.memoryUsage = memoryUsage;
        this.cpuStale = cpuStale;
        this.memoryStale = memoryStale;
        this.lastHeartbeatMillis = lastHeartbeatMillis;
        this.registeredAtMillis = registeredAtMillis;
        this.heartbeatSequence = heartbeatSequence;
        this.status = status;
        this.agentVersion = agentVersion == null ? "" : agentVersion;
        this.capabilities = capabilities == null
                ? ControlPlaneProto.NodeCapabilities.getDefaultInstance() : capabilities;
        this.certificate = certificate == null
                ? ControlPlaneProto.CertificateInfo.getDefaultInstance() : certificate;
        this.riskScore = riskScore;
        this.atRisk = atRisk;
        this.riskReasons = riskReasons == null ? List.of() : riskReasons;
        this.riskAssessedAtMillis = riskAssessedAtMillis;
    }

    /** Creates the initial record for a node the registry has never seen. */
    public static NodeRecord fresh(String nodeId, String ip, int port, String hostname,
                                   ControlPlaneProto.NodeCapabilities capabilities,
                                   String agentVersion, long nowMillis) {
        return new NodeRecord(nodeId, ip, port, hostname,
                0.0f, 0.0f, true, true,
                nowMillis, nowMillis, 0L,
                NodeStatus.ALIVE, agentVersion, capabilities,
                ControlPlaneProto.CertificateInfo.getDefaultInstance(),
                0.0, false, List.of(), 0L);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getNodeId()                { return nodeId; }
    public String getIp()                    { return ip; }
    public int    getPort()                  { return port; }
    public String getHostname()              { return hostname; }
    public float  getCpuUsage()              { return cpuUsage; }
    public float  getMemoryUsage()           { return memoryUsage; }
    public boolean isCpuStale()              { return cpuStale; }
    public boolean isMemoryStale()           { return memoryStale; }
    public long   getLastHeartbeatMillis()   { return lastHeartbeatMillis; }
    public long   getRegisteredAtMillis()    { return registeredAtMillis; }
    public long   getHeartbeatSequence()     { return heartbeatSequence; }
    public NodeStatus getStatus()            { return status; }
    public String getAgentVersion()          { return agentVersion; }

    public ControlPlaneProto.NodeCapabilities getCapabilities() { return capabilities; }
    public ControlPlaneProto.CertificateInfo  getCertificate()  { return certificate; }

    /** The legacy {@code "ALIVE"}/{@code "SUSPECTED_DEAD"} string, for the dashboard JSON. */
    public String getStatusName() { return status.wireName(); }

    public double getRiskScore()             { return riskScore; }
    public boolean isAtRisk()                { return atRisk; }
    public List<String> getRiskReasons()     { return riskReasons; }
    public long   getRiskAssessedAtMillis()  { return riskAssessedAtMillis; }

    // ── Copy-on-write mutators ───────────────────────────────────────────────

    /**
     * Applies a heartbeat.
     *
     * <p>When {@code cpuValid} is false the previous CPU value is <b>preserved</b> and flagged
     * stale rather than being overwritten with the meaningless payload value. Same for memory.
     * Receiving a heartbeat always restores {@link NodeStatus#ALIVE} unless the node has been
     * deregistered, which only an explicit re-registration may undo.
     */
    public NodeRecord withHeartbeat(float cpu, boolean cpuValid,
                                    float mem, boolean memValid,
                                    long nowMillis, long sequence) {
        NodeStatus next = (status == NodeStatus.DEREGISTERED || status == NodeStatus.DRAINING)
                ? status : NodeStatus.ALIVE;
        return new NodeRecord(nodeId, ip, port, hostname,
                cpuValid ? cpu : cpuUsage, memValid ? mem : memoryUsage,
                !cpuValid, !memValid,
                nowMillis, registeredAtMillis, sequence,
                next, agentVersion, capabilities, certificate,
                riskScore, atRisk, riskReasons, riskAssessedAtMillis);
    }

    public NodeRecord withStatus(NodeStatus newStatus) {
        if (newStatus == status) {
            return this;
        }
        return new NodeRecord(nodeId, ip, port, hostname,
                cpuUsage, memoryUsage, cpuStale, memoryStale,
                lastHeartbeatMillis, registeredAtMillis, heartbeatSequence,
                newStatus, agentVersion, capabilities, certificate,
                riskScore, atRisk, riskReasons, riskAssessedAtMillis);
    }

    /**
     * Merges a re-registration into an existing record.
     *
     * <p>Network identity (ip/port/hostname) is legitimately mutable — a node can move under DHCP —
     * so it is taken from the new registration. Live telemetry is <b>carried forward</b>: the old
     * code replaced the whole record here, which reset a healthy node's CPU and memory to a
     * phantom 0% on every reconnect.
     */
    public NodeRecord withRegistration(String newIp, int newPort, String newHostname,
                                       ControlPlaneProto.NodeCapabilities newCapabilities,
                                       String newAgentVersion, long nowMillis) {
        return new NodeRecord(nodeId, newIp, newPort, newHostname,
                cpuUsage, memoryUsage, cpuStale, memoryStale,
                nowMillis, registeredAtMillis, heartbeatSequence,
                NodeStatus.ALIVE,
                newAgentVersion == null || newAgentVersion.isEmpty() ? agentVersion : newAgentVersion,
                newCapabilities == null
                        || newCapabilities.equals(ControlPlaneProto.NodeCapabilities.getDefaultInstance())
                        ? capabilities : newCapabilities,
                certificate,
                riskScore, atRisk, riskReasons, riskAssessedAtMillis);
    }

    public NodeRecord withCertificate(ControlPlaneProto.CertificateInfo newCertificate) {
        return new NodeRecord(nodeId, ip, port, hostname,
                cpuUsage, memoryUsage, cpuStale, memoryStale,
                lastHeartbeatMillis, registeredAtMillis, heartbeatSequence,
                status, agentVersion, capabilities, newCertificate,
                riskScore, atRisk, riskReasons, riskAssessedAtMillis);
    }

    /** Records a fresh risk assessment from {@code RiskMonitor}. See the field Javadoc above. */
    public NodeRecord withRisk(double newRiskScore, boolean newAtRisk, List<String> newReasons, long nowMillis) {
        return new NodeRecord(nodeId, ip, port, hostname,
                cpuUsage, memoryUsage, cpuStale, memoryStale,
                lastHeartbeatMillis, registeredAtMillis, heartbeatSequence,
                status, agentVersion, capabilities, certificate,
                newRiskScore, newAtRisk, newReasons == null ? List.of() : List.copyOf(newReasons), nowMillis);
    }

    // ── Wire conversion ──────────────────────────────────────────────────────

    /** Projects this record into the {@code NodeInfo} message returned by {@code GetNodes}. */
    public ControlPlaneProto.NodeInfo toProto() {
        return ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setIp(ip)
                .setPort(port)
                .setHostname(hostname)
                .setCpu(cpuUsage)
                .setMemory(memoryUsage)
                .setStatus(status.toProto())
                .setLastHeartbeatEpochMillis(lastHeartbeatMillis)
                .setCapabilities(capabilities)
                .setCertificate(certificate)
                .setAgentVersion(agentVersion)
                .setRegisteredAtEpochMillis(registeredAtMillis)
                .setCpuStale(cpuStale)
                .setMemoryStale(memoryStale)
                .setRiskScore((float) riskScore)
                .setAtRisk(atRisk)
                .setRiskAssessedAtEpochMillis(riskAssessedAtMillis)
                .addAllRiskReasons(riskReasons)
                .build();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "NodeRecord{id=%s, ip=%s, cpu=%s, mem=%s, status=%s}",
                nodeId, ip,
                cpuStale ? "n/a" : String.format(Locale.ROOT, "%.1f%%", cpuUsage),
                memoryStale ? "n/a" : String.format(Locale.ROOT, "%.1f%%", memoryUsage),
                status.wireName());
    }
}
