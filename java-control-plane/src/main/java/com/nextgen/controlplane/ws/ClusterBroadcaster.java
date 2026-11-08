package com.nextgen.controlplane.ws;

import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.job.JobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Scheduled broadcaster that pushes live cluster state to WebSocket subscribers.
 *
 * <p>Runs every 750 ms (matching the existing SSE poll interval in {@code LocalUiServer}) and
 * broadcasts to STOMP topics. The new Next.js frontend subscribes to these topics via
 * {@code @stomp/stompjs}; the existing JavaFX desktop-ui continues using SSE.
 *
 * <p>Topics pushed:
 * <ul>
 *   <li>{@code /topic/nodes}   — full node list snapshot</li>
 *   <li>{@code /topic/cluster} — aggregate summary (counts, averages)</li>
 *   <li>{@code /topic/tasks}   — running/recent task list</li>
 * </ul>
 */
@Component
@EnableScheduling
public class ClusterBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterBroadcaster.class);

    private final SimpMessagingTemplate messaging;
    private final NodeRegistry   nodeRegistry;
    private final TaskRegistry   taskRegistry;
    private final JobRegistry    jobRegistry;

    public ClusterBroadcaster(SimpMessagingTemplate messaging,
                               NodeRegistry   nodeRegistry,
                               TaskRegistry   taskRegistry,
                               JobRegistry    jobRegistry) {
        this.messaging    = messaging;
        this.nodeRegistry = nodeRegistry;
        this.taskRegistry = taskRegistry;
        this.jobRegistry  = jobRegistry;
    }

    @Scheduled(fixedDelay = 750)
    public void broadcastNodes() {
        try {
            long now = System.currentTimeMillis();
            List<NodeRecord> nodes = nodeRegistry.snapshot();

            // Node list
            List<Map<String, Object>> nodeMaps = nodes.stream()
                    .map(n -> toNodeMap(n, now))
                    .toList();
            messaging.convertAndSend("/topic/nodes", nodeMaps);

            // Cluster summary
            long alive = nodes.stream().filter(n -> n.getStatus().name().equals("ALIVE")).count();
            double avgCpu = nodes.stream()
                    .filter(n -> !n.isCpuStale())
                    .mapToDouble(NodeRecord::getCpuUsage)
                    .average().orElse(-1);
            double avgMem = nodes.stream()
                    .filter(n -> !n.isMemoryStale())
                    .mapToDouble(NodeRecord::getMemoryUsage)
                    .average().orElse(-1);

            messaging.convertAndSend("/topic/cluster", Map.of(
                    "totalNodes",    nodes.size(),
                    "healthyNodes",  alive,
                    "offlineNodes",  nodes.size() - alive,
                    "avgCpuUsage",   avgCpu < 0 ? null : avgCpu,
                    "avgMemoryUsage", avgMem < 0 ? null : avgMem,
                    "timestamp",     now
            ));
        } catch (Exception e) {
            LOG.debug("WebSocket broadcast error (non-fatal): {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1500)
    public void broadcastTasks() {
        try {
            // Send only active tasks to avoid flooding large histories
            List<Map<String, Object>> activeTasks = taskRegistry.snapshot().stream()
                    .filter(t -> {
                        String s = t.getState().name();
                        return s.equals("QUEUED") || s.equals("DISPATCHED") || s.equals("RUNNING");
                    })
                    .map(t -> Map.<String, Object>of(
                            "taskId",         t.getTaskId(),
                            "kind",           t.getKind().name(),
                            "state",          t.getState().name(),
                            "assignedNodeId", t.getAssignedNodeId() == null ? "" : t.getAssignedNodeId(),
                            "createdAtMs",    t.getCreatedAtMillis()
                    ))
                    .toList();
            messaging.convertAndSend("/topic/tasks", activeTasks);
        } catch (Exception e) {
            LOG.debug("WebSocket tasks broadcast error (non-fatal): {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private static Map<String, Object> toNodeMap(NodeRecord n, long now) {
        return Map.ofEntries(
                Map.entry("id",            n.getNodeId()),
                Map.entry("name",          n.getHostname()),
                Map.entry("ip",            n.getIp()),
                Map.entry("status",        n.getStatusName()),
                Map.entry("cpuUsage",      n.isCpuStale() ? null : n.getCpuUsage()),
                Map.entry("memoryUsage",   n.isMemoryStale() ? null : n.getMemoryUsage()),
                Map.entry("cpuStale",      n.isCpuStale()),
                Map.entry("memoryStale",   n.isMemoryStale()),
                Map.entry("cpuCores",      n.getCapabilities().getCpuCores()),
                Map.entry("totalMemoryBytes", n.getCapabilities().getTotalMemoryBytes()),
                Map.entry("heartbeatAgeMs", now - n.getLastHeartbeatMillis())
        );
    }
}
