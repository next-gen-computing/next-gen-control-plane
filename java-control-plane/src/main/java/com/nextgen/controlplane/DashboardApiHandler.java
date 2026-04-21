package com.nextgen.controlplane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP handler that serves the node registry as JSON on /api/nodes.
 * All values are REAL readings from the node heartbeats — never random or fake.
 */
public class DashboardApiHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DashboardApiHandler.class);

    private final ConcurrentHashMap<String, NodeRecord> registry;

    public DashboardApiHandler(ConcurrentHashMap<String, NodeRecord> registry) {
        this.registry = registry;
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

        String json = buildJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Builds the JSON response manually (no external JSON library needed).
     * All values come directly from the NodeRecord registry — real OS readings.
     */
    private String buildJson() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Timestamp
        sb.append("\"timestamp\":").append(now).append(",");

        // Nodes array
        sb.append("\"nodes\":[");
        int totalNodes = 0;
        int aliveNodes = 0;
        int deadNodes = 0;
        double totalCpu = 0;
        double totalMemory = 0;

        boolean first = true;
        for (NodeRecord node : registry.values()) {
            if (!first) sb.append(",");
            first = false;
            totalNodes++;

            if ("ALIVE".equals(node.getStatus())) {
                aliveNodes++;
            } else {
                deadNodes++;
            }
            totalCpu += node.getCpuUsage();
            totalMemory += node.getMemoryUsage();

            long heartbeatAge = now - node.getLastHeartbeatMillis();

            sb.append("{");
            sb.append("\"nodeId\":\"").append(escapeJson(node.getNodeId())).append("\",");
            sb.append("\"ip\":\"").append(escapeJson(node.getIp())).append("\",");
            sb.append("\"hostname\":\"").append(escapeJson(node.getHostname())).append("\",");
            sb.append("\"port\":").append(node.getPort()).append(",");
            sb.append("\"cpuUsage\":").append(String.format("%.2f", node.getCpuUsage())).append(",");
            sb.append("\"memoryUsage\":").append(String.format("%.2f", node.getMemoryUsage())).append(",");
            sb.append("\"status\":\"").append(escapeJson(node.getStatus())).append("\",");
            sb.append("\"lastHeartbeatMs\":").append(node.getLastHeartbeatMillis()).append(",");
            sb.append("\"heartbeatAgeMs\":").append(heartbeatAge);
            sb.append("}");
        }
        sb.append("],");

        // Summary
        double avgCpu = totalNodes > 0 ? totalCpu / totalNodes : 0;
        double avgMemory = totalNodes > 0 ? totalMemory / totalNodes : 0;

        sb.append("\"summary\":{");
        sb.append("\"totalNodes\":").append(totalNodes).append(",");
        sb.append("\"aliveNodes\":").append(aliveNodes).append(",");
        sb.append("\"deadNodes\":").append(deadNodes).append(",");
        sb.append("\"avgCpu\":").append(String.format("%.2f", avgCpu)).append(",");
        sb.append("\"avgMemory\":").append(String.format("%.2f", avgMemory));
        sb.append("}");

        sb.append("}");
        return sb.toString();
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
