package com.nextgen.controlplane.api;

import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.db.NodeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for node registry data.
 *
 * <p>Replaces {@link com.nextgen.controlplane.DashboardApiHandler} (raw JDK HttpServer)
 * with proper Spring MVC endpoints, pagination, and JSON serialization.
 *
 * <p>The in-memory {@link NodeRegistry} is the authoritative live source;
 * the DB ({@link NodeRepository}) is used for historical queries.
 */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {

    private final NodeRegistry nodeRegistry;
    private final NodeRepository nodeRepository;

    public NodeController(NodeRegistry nodeRegistry, NodeRepository nodeRepository) {
        this.nodeRegistry = nodeRegistry;
        this.nodeRepository = nodeRepository;
    }

    /**
     * GET /api/v1/nodes — returns the current live node list from in-memory registry.
     * Fastest path: no DB round-trip, always reflects the latest heartbeat.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listNodes() {
        List<NodeRecord> nodes = nodeRegistry.snapshot();
        long now = System.currentTimeMillis();

        List<Map<String, Object>> nodeMaps = nodes.stream()
                .map(n -> nodeToMap(n, now))
                .toList();

        long alive = nodes.stream().filter(n -> n.getStatus().name().equals("ALIVE")).count();

        double avgCpu = nodes.stream()
                .filter(n -> !n.isCpuStale())
                .mapToDouble(NodeRecord::getCpuUsage)
                .average().orElse(Double.NaN);

        double avgMem = nodes.stream()
                .filter(n -> !n.isMemoryStale())
                .mapToDouble(NodeRecord::getMemoryUsage)
                .average().orElse(Double.NaN);

        return ResponseEntity.ok(Map.of(
                "timestamp", now,
                "nodes", nodeMaps,
                "summary", Map.of(
                        "totalNodes", nodes.size(),
                        "aliveNodes", alive,
                        "deadNodes", nodes.size() - alive,
                        "avgCpu", Double.isNaN(avgCpu) ? null : Math.round(avgCpu * 100.0) / 100.0,
                        "avgMemory", Double.isNaN(avgMem) ? null : Math.round(avgMem * 100.0) / 100.0
                )
        ));
    }

    /**
     * GET /api/v1/nodes/{nodeId} — returns a single node by ID.
     * Checks in-memory registry first; falls back to DB if not found live.
     */
    @GetMapping("/{nodeId}")
    public ResponseEntity<?> getNode(@PathVariable String nodeId) {
        long now = System.currentTimeMillis();
        return nodeRegistry.snapshot().stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst()
                .map(n -> ResponseEntity.ok(nodeToMap(n, now)))
                .orElseGet(() -> nodeRepository.findById(nodeId)
                        .map(n -> ResponseEntity.ok(nodeToMap(n, now)))
                        .orElse(ResponseEntity.notFound().build()));
    }

    /**
     * GET /api/v1/nodes/status/{status} — filter by status (ALIVE, DEAD, etc.)
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<Map<String, Object>>> listByStatus(@PathVariable String status) {
        // findByStatus returns raw DB row maps — returned directly as JSON
        return ResponseEntity.ok(nodeRepository.findByStatus(status.toUpperCase()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> nodeToMap(NodeRecord n, long now) {
        return Map.ofEntries(
                Map.entry("nodeId", n.getNodeId()),
                Map.entry("hostname", n.getHostname()),
                Map.entry("ip", n.getIp()),
                Map.entry("port", n.getPort()),
                Map.entry("status", n.getStatusName()),
                Map.entry("agentVersion", n.getAgentVersion()),
                Map.entry("cpuCores", n.getCapabilities().getCpuCores()),
                Map.entry("totalMemoryBytes", n.getCapabilities().getTotalMemoryBytes()),
                Map.entry("osName", n.getCapabilities().getOsName()),
                Map.entry("cpuUsage", n.isCpuStale() ? null : n.getCpuUsage()),
                Map.entry("memoryUsage", n.isMemoryStale() ? null : n.getMemoryUsage()),
                Map.entry("cpuStale", n.isCpuStale()),
                Map.entry("memoryStale", n.isMemoryStale()),
                Map.entry("lastHeartbeatMs", n.getLastHeartbeatMillis()),
                Map.entry("heartbeatAgeMs", now - n.getLastHeartbeatMillis())
        );
    }
}
