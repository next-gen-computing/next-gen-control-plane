package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.client.PredictorClient;
import com.nextgen.desktop.ui.model.ClusterSummary;
import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.proto.ControlPlaneProto;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the ControlPlane gRPC service for node data and maintains real-time node state.
 *
 * <p>Publishes connectivity through {@link ConnectionStateManager} so the UI can distinguish "the
 * cluster is empty" from "we could not reach the control plane" — a distinction the previous version
 * could not make, because an RPC failure produced an empty list.
 */
public class NodeMonitoringService {
    private static final Logger LOG = LoggerFactory.getLogger(NodeMonitoringService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final GrpcConnectionManager connectionManager;
    private final ConnectionStateManager connectionState;
    private final ObservableList<NodeModel> nodes = FXCollections.observableArrayList();
    private final ClusterSummary clusterSummary = new ClusterSummary();
    private final Map<String, NodeModel> nodeMap = new ConcurrentHashMap<>();
    private final MetricsHistory metricsHistory = new MetricsHistory();
    private final boolean fxThreadDispatch;

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile long refreshIntervalMs = 2000;

    public NodeMonitoringService(GrpcConnectionManager connectionManager) {
        this(connectionManager, new ConnectionStateManager(), true);
    }

    public NodeMonitoringService(GrpcConnectionManager connectionManager,
                                 ConnectionStateManager connectionState) {
        this(connectionManager, connectionState, true);
    }

    /**
     * @param fxThreadDispatch when false, model updates run on the calling thread. Tests use this so
     *                         they need no JavaFX toolkit.
     */
    NodeMonitoringService(GrpcConnectionManager connectionManager,
                          ConnectionStateManager connectionState,
                          boolean fxThreadDispatch) {
        this.connectionManager = connectionManager;
        this.connectionState = connectionState;
        this.fxThreadDispatch = fxThreadDispatch;
    }

    public ConnectionStateManager getConnectionState() {
        return connectionState;
    }

    public synchronized void startMonitoring() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "node-monitor");
            t.setDaemon(true);
            return t;
        });
        connectionState.recordAttempt();
        scheduler.scheduleAtFixedRate(this::refresh, 0, refreshIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("Node monitoring started with {}ms interval", refreshIntervalMs);
    }

    public synchronized void stopMonitoring() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
            scheduler = null;
        }
        LOG.info("Node monitoring stopped");
    }

    public void setRefreshInterval(long intervalMs) {
        this.refreshIntervalMs = intervalMs;
        if (running) {
            stopMonitoring();
            startMonitoring();
        }
    }

    public long getRefreshInterval() {
        return refreshIntervalMs;
    }

    /** One polling cycle. Package-private so tests can drive it deterministically. */
    void refresh() {
        ControlPlaneClient client = connectionManager.getControlPlaneClient();
        if (client == null) {
            connectionState.recordFailure("No control plane channel");
            return;
        }

        List<ControlPlaneProto.NodeInfo> grpcNodes;
        try {
            grpcNodes = client.getNodes();
        } catch (ControlPlaneUnavailableException e) {
            // The poll failed. Do NOT clear the node list — that would render as a healthy empty
            // cluster. Leave the last known data in place and mark the link down so the UI can
            // label everything on screen as stale.
            LOG.warn("Node poll failed: {}", e.shortReason());
            connectionState.recordFailure(e.shortReason());
            connectionState.refreshStaleness();
            return;
        } catch (RuntimeException e) {
            LOG.error("Unexpected error polling nodes", e);
            connectionState.recordFailure("Unexpected error");
            connectionState.refreshStaleness();
            return;
        }

        PredictorClient predictor = connectionManager.getPredictorClient();
        runOnUiThread(() -> {
            updateNodes(grpcNodes, predictor);
            updateClusterSummary();
            connectionState.recordSuccess();
        });
    }

    void updateNodes(List<ControlPlaneProto.NodeInfo> grpcNodes, PredictorClient predictor) {
        Set<String> reported = new HashSet<>();
        long sampledAt = System.currentTimeMillis();

        for (ControlPlaneProto.NodeInfo info : grpcNodes) {
            reported.add(info.getNodeId());
            NodeModel node = nodeMap.computeIfAbsent(info.getNodeId(), id -> {
                NodeModel created = new NodeModel();
                created.setId(id);
                nodes.add(created);
                return created;
            });

            node.setName(info.getHostname().isEmpty() ? info.getNodeId() : info.getHostname());
            node.setHostname(info.getHostname());
            node.setIp(info.getIp());
            node.setPort(info.getPort());
            node.setStatus(mapStatus(info.getStatus()));
            node.setCpuUsage(info.getCpu());
            node.setMemoryUsage(info.getMemory());
            node.setCpuStale(info.getCpuStale());
            node.setMemoryStale(info.getMemoryStale());
            node.setLastHeartbeat(formatHeartbeat(info.getLastHeartbeatEpochMillis()));

            // Feed the charts. A node the control plane reports as dead, or whose reading is stale,
            // records a GAP rather than a value — so the chart breaks the line instead of drawing
            // straight through the outage.
            boolean live = info.getStatus() == ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE;
            metricsHistory.record(info.getNodeId(), MetricsHistory.Metric.CPU, sampledAt,
                    info.getCpu(), live && !info.getCpuStale());
            metricsHistory.record(info.getNodeId(), MetricsHistory.Metric.MEMORY, sampledAt,
                    info.getMemory(), live && !info.getMemoryStale());

            updatePrediction(node, predictor, info);
        }

        // Remove models for nodes the control plane no longer reports at all. Nodes it DOES report as
        // dead stay on screen — an operator needs to see a failed node, not have it silently vanish.
        nodes.removeIf(model -> !reported.contains(model.getId()));
        nodeMap.keySet().removeIf(id -> !reported.contains(id));

        // Nodes the control plane still knows about but did not include in this response get an
        // explicit gap, so their chart line breaks rather than freezing at its last value.
        for (String knownNode : metricsHistory.nodeIds()) {
            if (!reported.contains(knownNode)) {
                metricsHistory.recordGap(knownNode, sampledAt);
            }
        }
    }

    /** The rolling time series behind the charts. */
    public MetricsHistory getMetricsHistory() {
        return metricsHistory;
    }

    /**
     * Maps the control plane's liveness enum onto the UI's status vocabulary.
     *
     * <p>The previous implementation hardcoded {@code "HEALTHY"} for every node, because
     * {@code NodeInfo} carried no status field — so a dead node rendered as healthy and the
     * "Warning" counter was structurally always zero.
     */
    static String mapStatus(ControlPlaneProto.NodeStatus status) {
        if (status == null) {
            return "UNKNOWN";
        }
        // A `default` arm is mandatory: protobuf generates UNRECOGNIZED for every proto3 enum.
        return switch (status) {
            case NODE_STATUS_ALIVE -> "HEALTHY";
            case NODE_STATUS_SUSPECTED_DEAD -> "OFFLINE";
            case NODE_STATUS_DRAINING -> "WARNING";
            case NODE_STATUS_DEREGISTERED -> "OFFLINE";
            default -> "UNKNOWN";
        };
    }

    /** Renders the control plane's heartbeat timestamp; never substitutes the local clock. */
    static String formatHeartbeat(long epochMillis) {
        if (epochMillis <= 0) {
            return "Never";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                .format(TIME_FORMATTER);
    }

    private void updatePrediction(NodeModel node, PredictorClient predictor,
                                  ControlPlaneProto.NodeInfo info) {
        if (predictor == null) {
            setPredictionUnavailable(node);
            return;
        }
        if (info.getCpuStale() || info.getMemoryStale()) {
            // Feeding a stale reading to the predictor yields a confident answer about data we know
            // is not current.
            setPredictionUnavailable(node);
            return;
        }
        try {
            PredictorClient.PredictionResult result =
                    predictor.getPrediction(node.getId(), info.getCpu(), info.getMemory());
            node.setPredictedLoad(result.getPredictedLoadText());
            node.setFailureProbability(result.getFailureProbabilityText());
            node.setRecommendation(result.getRecommendation());
        } catch (RuntimeException e) {
            setPredictionUnavailable(node);
        }
    }

    private static void setPredictionUnavailable(NodeModel node) {
        node.setPredictedLoad("N/A");
        node.setFailureProbability("N/A");
        node.setRecommendation("N/A");
    }

    void updateClusterSummary() {
        int healthy = 0, warning = 0, offline = 0;
        double totalCpu = 0, totalMem = 0;
        int cpuSamples = 0, memSamples = 0;

        for (NodeModel node : nodes) {
            if (!node.isCpuStale()) {
                totalCpu += node.getCpuUsage();
                cpuSamples++;
            }
            if (!node.isMemoryStale()) {
                totalMem += node.getMemoryUsage();
                memSamples++;
            }
            switch (node.getStatus()) {
                case "HEALTHY" -> healthy++;
                case "WARNING" -> warning++;
                case "OFFLINE" -> offline++;
                default -> { /* UNKNOWN counts toward none of the three */ }
            }
        }

        clusterSummary.setTotalNodes(nodes.size());
        clusterSummary.setHealthyNodes(healthy);
        clusterSummary.setWarningNodes(warning);
        clusterSummary.setOfflineNodes(offline);
        // A cluster with nothing measurable reports -1, which the UI renders as "n/a". Reporting 0.0
        // would look like a real, idle cluster.
        clusterSummary.setAvgCpuUsage(cpuSamples > 0 ? totalCpu / cpuSamples : -1);
        clusterSummary.setAvgMemoryUsage(memSamples > 0 ? totalMem / memSamples : -1);
        clusterSummary.setLastUpdated(LocalDateTime.now().format(TIME_FORMATTER));
    }

    private void runOnUiThread(Runnable action) {
        if (fxThreadDispatch && !Platform.isFxApplicationThread()) {
            Platform.runLater(action);
        } else {
            action.run();
        }
    }

    public ObservableList<NodeModel> getNodes() {
        return nodes;
    }

    public ClusterSummary getClusterSummary() {
        return clusterSummary;
    }

    public void shutdown() {
        stopMonitoring();
    }
}
