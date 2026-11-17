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
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("nodeId", n.getNodeId());
        map.put("hostname", n.getHostname());
        map.put("ip", n.getIp());
        map.put("port", n.getPort());
        map.put("status", n.getStatusName());
        map.put("agentVersion", n.getAgentVersion());
        map.put("cpuCores", n.getCapabilities().getCpuCores());
        map.put("totalMemoryBytes", n.getCapabilities().getTotalMemoryBytes());
        map.put("osName", n.getCapabilities().getOsName());
        map.put("cpuUsage", n.isCpuStale() ? null : n.getCpuUsage());
        map.put("memoryUsage", n.isMemoryStale() ? null : n.getMemoryUsage());
        map.put("cpuStale", n.isCpuStale());
        map.put("memoryStale", n.isMemoryStale());
        map.put("lastHeartbeatMs", n.getLastHeartbeatMillis());
        map.put("heartbeatAgeMs", now - n.getLastHeartbeatMillis());
        map.put("riskScore", n.getRiskScore());
        map.put("atRisk", n.isAtRisk());
        map.put("riskReasons", n.getRiskReasons());
        map.put("riskAssessedAtMillis", n.getRiskAssessedAtMillis());

        Map<String, Object> active = CustomTaskExecutionController.getActiveTaskForNode(n.getNodeId());
        if (active == null) {
            active = CustomTaskExecutionController.getActiveTaskForNode(n.getHostname());
        }
        if (active != null) {
            map.put("activeTask", active);
        }
        return map;
    }
}
