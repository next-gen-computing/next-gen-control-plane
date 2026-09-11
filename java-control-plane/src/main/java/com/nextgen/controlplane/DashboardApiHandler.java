package com.nextgen.controlplane;

import com.nextgen.controlplane.raft.LeadershipStatus;
import com.nextgen.controlplane.raft.RaftNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP handler that serves the node registry as JSON on {@code /api/nodes}.
 *
 * <p>All values are real readings taken from node heartbeats. Where a reading is unavailable the
 * payload says so explicitly via the {@code cpuStale}/{@code memoryStale} flags and a {@code null}
 * value, rather than emitting a number the consumer would reasonably believe is current.
 */
public class DashboardApiHandler implements HttpHandler {

    private final NodeRegistry registry;
    /** Null in every non-Raft deployment (both constructors below except the last). Under Raft, a
     * follower's registry receives zero heartbeats — rendering it here would show every node falsely
     * stale — so a follower answers 503 with a leader hint instead of fabricating a "healthy" view. */
    private final RaftNode raftNode;

    /** Legacy constructor: wraps an externally-held map. */
    public DashboardApiHandler(ConcurrentHashMap<String, NodeRecord> registry) {
        this(new NodeRegistry(registry, System::currentTimeMillis));
    }

    public DashboardApiHandler(NodeRegistry registry) {
        this(registry, null);
    }

    public DashboardApiHandler(NodeRegistry registry, RaftNode raftNode) {
        this.registry = registry;
        this.raftNode = raftNode;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS headers for local development
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (raftNode != null && !raftNode.isLeader()) {
            byte[] bytes = notLeaderJson().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        byte[] bytes = buildJson().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Told plainly rather than rendering fictional heartbeat-less "healthy" state — see the class doc. */
    private String notLeaderJson() {
        LeadershipStatus status = raftNode.leadership();
        return "{\"error\":\"not_leader\",\"leaderId\":\"" + escapeJson(status.leaderId())
                + "\",\"leaderAddress\":\"" + escapeJson(status.leaderAddress()) + "\"}";
    }

    /**
     * Builds the JSON response by hand (no external JSON library needed).
     *
     * <p>Every number is formatted with {@link Locale#ROOT}. The previous implementation used the
     * default locale, so on a machine with a comma decimal separator it emitted
     * {@code "cpuUsage":45,50} — structurally invalid JSON that broke the dashboard for anyone
     * outside an English locale.
     */
    String buildJson() {
        long now = System.currentTimeMillis();
        List<NodeRecord> nodes = registry.snapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":").append(now).append(",");
        sb.append("\"nodes\":[");

        int aliveNodes = 0;
        int deadNodes = 0;
        double totalCpu = 0;
        double totalMemory = 0;
        int cpuSamples = 0;
        int memorySamples = 0;

        boolean first = true;
        for (NodeRecord node : nodes) {
            if (!first) sb.append(",");
            first = false;

            if (node.getStatus() == NodeStatus.ALIVE) {
                aliveNodes++;
            } else {
                deadNodes++;
            }

            // Stale readings are excluded from the averages entirely. Averaging in a value we know is
            // not current would quietly bias the cluster figure toward a stale node's last reading.
            if (!node.isCpuStale()) {
                totalCpu += node.getCpuUsage();
                cpuSamples++;
            }
            if (!node.isMemoryStale()) {
                totalMemory += node.getMemoryUsage();
                memorySamples++;
            }

            sb.append("{");
            sb.append("\"nodeId\":\"").append(escapeJson(node.getNodeId())).append("\",");
            sb.append("\"ip\":\"").append(escapeJson(node.getIp())).append("\",");
            sb.append("\"hostname\":\"").append(escapeJson(node.getHostname())).append("\",");
            sb.append("\"port\":").append(node.getPort()).append(",");
            sb.append("\"cpuUsage\":").append(number(node.getCpuUsage(), node.isCpuStale())).append(",");
            sb.append("\"memoryUsage\":").append(number(node.getMemoryUsage(), node.isMemoryStale())).append(",");
            sb.append("\"cpuStale\":").append(node.isCpuStale()).append(",");
            sb.append("\"memoryStale\":").append(node.isMemoryStale()).append(",");
            sb.append("\"status\":\"").append(escapeJson(node.getStatusName())).append("\",");
            sb.append("\"agentVersion\":\"").append(escapeJson(node.getAgentVersion())).append("\",");
            sb.append("\"cpuCores\":").append(node.getCapabilities().getCpuCores()).append(",");
            sb.append("\"totalMemoryBytes\":").append(node.getCapabilities().getTotalMemoryBytes()).append(",");
            sb.append("\"osName\":\"").append(escapeJson(node.getCapabilities().getOsName())).append("\",");
            sb.append("\"lastHeartbeatMs\":").append(node.getLastHeartbeatMillis()).append(",");
            sb.append("\"heartbeatAgeMs\":").append(now - node.getLastHeartbeatMillis());
            sb.append("}");
        }
        sb.append("],");

        sb.append("\"summary\":{");
        sb.append("\"totalNodes\":").append(nodes.size()).append(",");
        sb.append("\"aliveNodes\":").append(aliveNodes).append(",");
        sb.append("\"deadNodes\":").append(deadNodes).append(",");
        // null, not 0, when nothing was measurable — the consumer must be able to tell "no data"
        // apart from "measured zero".
        sb.append("\"avgCpu\":").append(cpuSamples > 0 ? number(totalCpu / cpuSamples, false) : "null").append(",");
        sb.append("\"avgMemory\":").append(memorySamples > 0 ? number(totalMemory / memorySamples, false) : "null");
        sb.append("}");

        sb.append("}");
        return sb.toString();
    }

    /**
     * Renders a metric, or {@code null} when the reading is stale.
     *
     * <p>Also guards NaN and infinity, neither of which is valid JSON — a raw {@code String.format}
     * of either would produce a payload no parser accepts.
     */
    private static String number(double value, boolean stale) {
        if (stale || Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Minimal JSON string escaping. */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                     .replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t");
    }
}
