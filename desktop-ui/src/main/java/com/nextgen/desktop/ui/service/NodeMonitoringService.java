package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.client.PredictorClient;
import com.nextgen.desktop.ui.model.ClusterSummary;
import com.nextgen.desktop.ui.model.NodeModel;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the ControlPlane gRPC service for node data and maintains real-time node state.
 */
public class NodeMonitoringService {
    private static final Logger LOG = LoggerFactory.getLogger(NodeMonitoringService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final GrpcConnectionManager connectionManager;
    private final ObservableList<NodeModel> nodes = FXCollections.observableArrayList();
    private final ClusterSummary clusterSummary = new ClusterSummary();
    private final Map<String, NodeModel> nodeMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "node-monitor");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private long refreshIntervalMs = 2000;

    public NodeMonitoringService(GrpcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void startMonitoring() {
        if (running) return;
        running = true;
        scheduler.scheduleAtFixedRate(this::refresh, 0, refreshIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("Node monitoring started with {}ms interval", refreshIntervalMs);
    }

    public void stopMonitoring() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private void refresh() {
        try {
            ControlPlaneClient client = connectionManager.getControlPlaneClient();
            if (client == null) {
                Platform.runLater(() -> setConnectingState());
                return;
            }

            List<com.nextgen.proto.ControlPlaneProto.NodeInfo> grpcNodes = client.getNodes();

            // Fetch predictions for each node
            PredictorClient predictor = connectionManager.getPredictorClient();

            Platform.runLater(() -> {
                updateNodes(grpcNodes, predictor);
                updateClusterSummary();
            });
        } catch (Exception e) {
            LOG.error("Error refreshing node data", e);
        }
    }

    private void updateNodes(List<com.nextgen.proto.ControlPlaneProto.NodeInfo> grpcNodes, PredictorClient predictor) {
        // Mark all existing nodes as potentially offline
        for (NodeModel node : nodeMap.values()) {
            node.setStatus("OFFLINE");
        }

        for (com.nextgen.proto.ControlPlaneProto.NodeInfo info : grpcNodes) {
            NodeModel node = nodeMap.computeIfAbsent(info.getNodeId(), id -> {
                NodeModel n = new NodeModel();
                n.setId(id);
                if (!nodes.contains(n)) {
                    nodes.add(n);
                }
                return n;
            });

            node.setName(info.getHostname());
            node.setHostname(info.getHostname());
            node.setIp(info.getIp());
            node.setPort(info.getPort());
            node.setStatus("HEALTHY");
            node.setCpuUsage(info.getCpu());
            node.setMemoryUsage(info.getMemory());
            node.setLastHeartbeat(LocalDateTime.now().format(TIME_FORMATTER));

            // Try to get prediction
            if (predictor != null) {
                try {
                    PredictorClient.PredictionResult result = predictor.getPrediction(
                            node.getId(), (float) node.getCpuUsage(), (float) node.getMemoryUsage());
                    node.setPredictedLoad(result.getPredictedLoadText());
                    node.setFailureProbability(result.getFailureProbabilityText());
                    node.setRecommendation(result.getRecommendation());
                } catch (Exception e) {
                    node.setPredictedLoad("N/A");
                    node.setFailureProbability("N/A");
                    node.setRecommendation("N/A");
                }
            }
        }

        // Remove nodes that are no longer reported
        nodes.removeIf(n -> "OFFLINE".equals(n.getStatus()) && !grpcNodes.stream()
                .anyMatch(g -> g.getNodeId().equals(n.getId())));
    }

    private void updateClusterSummary() {
        int total = nodes.size();
        int healthy = 0, warning = 0, offline = 0;
        double totalCpu = 0, totalMem = 0;

        for (NodeModel node : nodes) {
            totalCpu += node.getCpuUsage();
            totalMem += node.getMemoryUsage();
            switch (node.getStatus()) {
                case "HEALTHY" -> healthy++;
                case "WARNING" -> warning++;
                case "OFFLINE" -> offline++;
            }
        }

        clusterSummary.setTotalNodes(total);
        clusterSummary.setHealthyNodes(healthy);
        clusterSummary.setWarningNodes(warning);
        clusterSummary.setOfflineNodes(offline);
        clusterSummary.setAvgCpuUsage(total > 0 ? totalCpu / total : 0);
        clusterSummary.setAvgMemoryUsage(total > 0 ? totalMem / total : 0);
        clusterSummary.setLastUpdated(LocalDateTime.now().format(TIME_FORMATTER));
    }

    private void setConnectingState() {
        for (NodeModel node : nodes) {
            node.setStatus("CONNECTING...");
        }
        clusterSummary.setLastUpdated("Connecting...");
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
