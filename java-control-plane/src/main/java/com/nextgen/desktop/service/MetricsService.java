package com.nextgen.desktop.service;

import com.nextgen.desktop.model.NodeStatus;
import com.nextgen.desktop.repository.NodeRepository;
import javafx.application.Platform;
import javafx.beans.property.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.*;

/**
 * Service for collecting and providing system metrics.
 */
public class MetricsService {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsService.class);
    
    private final NodeRepository nodeRepository;
    private ScheduledExecutorService scheduler;
    
    // Local system metrics
    private final DoubleProperty localCpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty localMemoryUsage = new SimpleDoubleProperty(0.0);
    private final BooleanProperty monitoring = new SimpleBooleanProperty(false);
    
    // Aggregated cluster metrics (for server mode)
    private final DoubleProperty clusterAvgCpu = new SimpleDoubleProperty(0.0);
    private final DoubleProperty clusterAvgMemory = new SimpleDoubleProperty(0.0);
    private final IntegerProperty totalNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty aliveNodes = new SimpleIntegerProperty(0);
    
    public MetricsService(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }
    
    /**
     * Start monitoring local system metrics.
     */
    public void startLocalMonitoring() {
        if (monitoring.get()) return;
        
        monitoring.set(true);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Metrics-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(this::updateLocalMetrics, 0, 1, TimeUnit.SECONDS);
        LOG.info("Local metrics monitoring started");
    }
    
    /**
     * Stop monitoring.
     */
    public void stopMonitoring() {
        monitoring.set(false);
        if (scheduler != null) {
            scheduler.shutdownNow();
            LOG.info("Metrics monitoring stopped");
        }
    }
    
    private void updateLocalMetrics() {
        try {
            com.sun.management.OperatingSystemMXBean osBean = 
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            
            // CPU
            double cpuLoad = osBean.getCpuLoad();
            if (cpuLoad >= 0) {
                final double cpuPercent = cpuLoad * 100.0;
                Platform.runLater(() -> localCpuUsage.set(cpuPercent));
            }
            
            // Memory
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            final double memoryPercent = (double) usedMemory / totalMemory * 100.0;
            Platform.runLater(() -> localMemoryUsage.set(memoryPercent));
            
        } catch (Exception e) {
            LOG.debug("Failed to update metrics: {}", e.getMessage());
        }
    }
    
    /**
     * Update cluster metrics from repository.
     */
    public void updateClusterMetrics() {
        if (nodeRepository != null) {
            Platform.runLater(() -> {
                totalNodes.set(nodeRepository.count());
                aliveNodes.set((int) nodeRepository.countAlive());
                clusterAvgCpu.set(nodeRepository.getAverageCpuUsage());
                clusterAvgMemory.set(nodeRepository.getAverageMemoryUsage());
            });
        }
    }
    
    // Property accessors
    public DoubleProperty localCpuUsageProperty() { return localCpuUsage; }
    public double getLocalCpuUsage() { return localCpuUsage.get(); }
    
    public DoubleProperty localMemoryUsageProperty() { return localMemoryUsage; }
    public double getLocalMemoryUsage() { return localMemoryUsage.get(); }
    
    public BooleanProperty monitoringProperty() { return monitoring; }
    public boolean isMonitoring() { return monitoring.get(); }
    
    public DoubleProperty clusterAvgCpuProperty() { return clusterAvgCpu; }
    public double getClusterAvgCpu() { return clusterAvgCpu.get(); }
    
    public DoubleProperty clusterAvgMemoryProperty() { return clusterAvgMemory; }
    public double getClusterAvgMemory() { return clusterAvgMemory.get(); }
    
    public IntegerProperty totalNodesProperty() { return totalNodes; }
    public int getTotalNodes() { return totalNodes.get(); }
    
    public IntegerProperty aliveNodesProperty() { return aliveNodes; }
    public int getAliveNodes() { return aliveNodes.get(); }
    
    public void dispose() {
        stopMonitoring();
    }
}
