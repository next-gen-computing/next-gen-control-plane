package com.nextgen.controlplane.api;

import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

/**
 * REST controller for cluster topology auto-detection, metrics summary, and demo task triggers.
 *
 * <p>Enables the single-page Tomcat Web UI to automatically detect whether the deployment
 * is executing in "Same Laptop (Local Loopback)" or "Distributed Edge Cluster (Multi-Laptop)"
 * mode, and delivers real-time system metrics without requiring external scraping tools.
 */
@RestController
@RequestMapping("/api/v1")
public class TopologyController {

    private final NodeRegistry nodeRegistry;
    private final TaskRegistry taskRegistry;

    public TopologyController(NodeRegistry nodeRegistry, TaskRegistry taskRegistry) {
        this.nodeRegistry = nodeRegistry;
        this.taskRegistry = taskRegistry;
    }

    /**
     * GET /api/v1/topology
     * Analyzes all active node IP addresses and network interfaces to detect the runtime topology.
     */
    @GetMapping("/topology")
    public ResponseEntity<Map<String, Object>> getTopology() {
        List<NodeRecord> nodes = nodeRegistry.snapshot();
        List<String> serverIps = resolveServerIps();
        String hostname = resolveHostname();

        int localCount = 0;
        int remoteCount = 0;
        List<Map<String, Object>> annotatedNodes = new ArrayList<>();

        for (NodeRecord node : nodes) {
            String ip = node.getIp();
            boolean isLocal = isLocalAddress(ip, serverIps);
            if (isLocal) {
                localCount++;
            } else {
                remoteCount++;
            }

            Map<String, Object> nodeInfo = new LinkedHashMap<>();
            nodeInfo.put("nodeId", node.getNodeId());
            nodeInfo.put("hostname", node.getHostname());
            nodeInfo.put("ip", ip);
            nodeInfo.put("port", node.getPort());
            nodeInfo.put("status", node.getStatusName());
            nodeInfo.put("osName", node.getCapabilities().getOsName());
            nodeInfo.put("cpuCores", node.getCapabilities().getCpuCores());
            nodeInfo.put("totalMemoryBytes", node.getCapabilities().getTotalMemoryBytes());
            nodeInfo.put("cpuUsage", node.isCpuStale() ? null : node.getCpuUsage());
            nodeInfo.put("memoryUsage", node.isMemoryStale() ? null : node.getMemoryUsage());
            nodeInfo.put("isLocal", isLocal);
            nodeInfo.put("locationType", isLocal ? "Local Machine (Loopback / IPC)" : "Remote Physical Node (LAN/WAN)");
            annotatedNodes.add(nodeInfo);
        }

        String mode;
        String modeDescription;
        if (nodes.isEmpty()) {
            mode = "STANDALONE_READY";
            modeDescription = "Control Plane active. Ready for worker nodes to join via gRPC.";
        } else if (remoteCount > 0) {
            mode = "DISTRIBUTED_MULTI_LAPTOP";
            modeDescription = "Physical Multi-Node Cluster active. Edge computing across distributed laptops.";
        } else {
            mode = "SAME_LAPTOP_LOOPBACK";
            modeDescription = "Single-Machine Simulation active. Nodes running on local loopback interface.";
        }

        String primaryIp = serverIps.stream()
                .filter(ip -> !ip.equals("127.0.0.1") && !ip.equals("localhost") && !ip.contains(":"))
                .findFirst()
                .orElse("127.0.0.1");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", mode);
        response.put("modeDescription", modeDescription);
        response.put("localNodeCount", localCount);
        response.put("remoteNodeCount", remoteCount);
        response.put("totalNodes", nodes.size());
        response.put("server", Map.of(
                "hostname", hostname,
                "primaryIp", primaryIp,
                "allIps", serverIps,
                "grpcPort", 50051,
                "webUiPort", 8080,
                "metricsPort", 9090,
                "predictorPort", 50052,
                "legacyApiPort", 8085
        ));
        response.put("nodes", annotatedNodes);
        response.put("joinInstructions", Map.of(
                "sameLaptop", "java -jar desktop-ui.jar --role=node --control-plane=127.0.0.1:50051",
                "differentLaptop", "java -jar desktop-ui.jar --role=node --control-plane=" + primaryIp + ":50051"
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/metrics/summary
     * Summarizes key performance and runtime metrics for dashboard display.
     */
    @GetMapping("/metrics/summary")
    public ResponseEntity<Map<String, Object>> getMetricsSummary() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        long heapUsed = mem.getHeapMemoryUsage().getUsed();
        long heapMax = mem.getHeapMemoryUsage().getMax();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        List<NodeRecord> nodes = nodeRegistry.snapshot();
        long aliveNodes = nodes.stream().filter(n -> "ALIVE".equalsIgnoreCase(n.getStatusName())).count();

        double avgCpu = nodes.stream()
                .filter(n -> !n.isCpuStale())
                .mapToDouble(NodeRecord::getCpuUsage)
                .average().orElse(0.0);

        double avgMem = nodes.stream()
                .filter(n -> !n.isMemoryStale())
                .mapToDouble(NodeRecord::getMemoryUsage)
                .average().orElse(0.0);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("uptimeSeconds", uptimeMs / 1000);
        metrics.put("availableProcessors", os.getAvailableProcessors());
        metrics.put("systemLoadAverage", os.getSystemLoadAverage());
        metrics.put("heapUsedMb", Math.round((double) heapUsed / (1024 * 1024) * 10.0) / 10.0);
        metrics.put("heapMaxMb", Math.round((double) heapMax / (1024 * 1024) * 10.0) / 10.0);
        metrics.put("heapUsedPercent", heapMax > 0 ? Math.round((double) heapUsed / heapMax * 1000.0) / 10.0 : 0.0);
        metrics.put("liveNodeCount", aliveNodes);
        metrics.put("totalNodeCount", nodes.size());
        metrics.put("avgClusterCpu", Math.round(avgCpu * 10.0) / 10.0);
        metrics.put("avgClusterMemory", Math.round(avgMem * 10.0) / 10.0);
        metrics.put("totalTasks", taskRegistry.snapshot().size());

        return ResponseEntity.ok(metrics);
    }

    /**
     * POST /api/v1/tasks/demo
     * Allows submitting a test task directly from the Web UI to test and demonstrate live task handling.
     */
    @PostMapping("/tasks/demo")
    public ResponseEntity<Map<String, Object>> submitDemoTask(
            @RequestParam(defaultValue = "1") long startRange,
            @RequestParam(defaultValue = "100000") long endRange) {

        String taskId = "demo-task-" + System.currentTimeMillis();
        String payload = String.format("{\"start\":%d,\"end\":%d,\"timestamp\":%d}",
                startRange, endRange, System.currentTimeMillis());

        TaskRecord task = taskRegistry.createAndQueue(taskId, "", TaskKindDomain.PRIME_COUNT_RANGE, payload);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("state", task.getState().name());
        result.put("kind", task.getKind().name());
        result.put("assignedNode", task.getAssignedNodeId().isEmpty() ? "QUEUED_FOR_SCHEDULER" : task.getAssignedNodeId());
        result.put("payload", payload);
        result.put("submittedAt", System.currentTimeMillis());
        result.put("message", "Task successfully queued in Control Plane engine.");

        return ResponseEntity.ok(result);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private static boolean isLocalAddress(String ip, List<String> serverIps) {
        if (ip == null || ip.isBlank()) return true;
        String clean = ip.trim().toLowerCase();
        if (clean.equals("127.0.0.1") || clean.equals("localhost") || clean.equals("::1") || clean.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        for (String sIp : serverIps) {
            if (clean.equalsIgnoreCase(sIp)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> resolveServerIps() {
        Set<String> ips = new LinkedHashSet<>();
        ips.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String hostAddress = addr.getHostAddress();
                    if (!hostAddress.contains("%")) { // exclude zone indexes
                        ips.add(hostAddress);
                    }
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(ips);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "control-plane-host";
        }
    }
}
