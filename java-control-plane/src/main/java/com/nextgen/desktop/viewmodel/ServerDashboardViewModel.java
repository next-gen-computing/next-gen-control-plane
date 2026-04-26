package com.nextgen.desktop.viewmodel;

import com.nextgen.desktop.model.ServerConfig;
import com.nextgen.desktop.repository.NodeRepository;
import com.nextgen.desktop.service.*;
import javafx.beans.property.*;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

/**
 * ViewModel for Server Dashboard with cluster monitoring.
 */
public class ServerDashboardViewModel extends BaseViewModel {
    
    private final ServerProcessService serverProcessService;
    private final ApiPollingService apiPollingService;
    private final MetricsService metricsService;
    private final NodeRepository nodeRepository;
    
    // Server status
    private final BooleanProperty serverRunning = new SimpleBooleanProperty(false);
    private final StringProperty serverStatus = new SimpleStringProperty("Stopped");
    
    // Cluster metrics
    private final IntegerProperty totalNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty aliveNodes = new SimpleIntegerProperty(0);
    private final IntegerProperty deadNodes = new SimpleIntegerProperty(0);
    private final DoubleProperty avgCpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty avgMemoryUsage = new SimpleDoubleProperty(0.0);
    
    public ServerDashboardViewModel(ServerProcessService serverProcessService,
                                    ApiPollingService apiPollingService,
                                    MetricsService metricsService,
                                    NodeRepository nodeRepository) {
        this.serverProcessService = serverProcessService;
        this.apiPollingService = apiPollingService;
        this.metricsService = metricsService;
        this.nodeRepository = nodeRepository;
        
        // Bind to metrics service
        totalNodes.bind(metricsService.totalNodesProperty());
        aliveNodes.bind(metricsService.aliveNodesProperty());
        avgCpuUsage.bind(metricsService.clusterAvgCpuProperty());
        avgMemoryUsage.bind(metricsService.clusterAvgMemoryProperty());
        
        // Calculate dead nodes
        deadNodes.bind(
            javafx.beans.binding.Bindings.createIntegerBinding(
                () -> totalNodes.get() - aliveNodes.get(),
                totalNodes, aliveNodes
            )
        );
    }
    
    /**
     * Start the server with configuration.
     */
    public void startServer(ServerConfig config) {
        executeAsync(() -> {
            try {
                serverProcessService.start(config.getPredictorHost());
                
                javafx.application.Platform.runLater(() -> {
                    serverRunning.set(true);
                    serverStatus.set("Running");
                    setSuccessMessage("Server started successfully");
                });
                
                // Start polling and monitoring
                apiPollingService.startPolling();
                metricsService.startLocalMonitoring();
                
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "Starting server...");
    }
    
    /**
     * Stop the server.
     */
    public void stopServer() {
        executeAsync(() -> {
            serverProcessService.stop();
            apiPollingService.stopPolling();
            metricsService.stopMonitoring();
            
            javafx.application.Platform.runLater(() -> {
                serverRunning.set(false);
                serverStatus.set("Stopped");
                setSuccessMessage("Server stopped");
            });
        }, "Stopping server...");
    }
    
    /**
     * Refresh cluster metrics.
     */
    public void refreshMetrics() {
        metricsService.updateClusterMetrics();
    }
    
    // Property accessors
    public BooleanProperty serverRunningProperty() { return serverRunning; }
    public boolean isServerRunning() { return serverRunning.get(); }
    
    public StringProperty serverStatusProperty() { return serverStatus; }
    public String getServerStatus() { return serverStatus.get(); }
    
    public IntegerProperty totalNodesProperty() { return totalNodes; }
    public int getTotalNodes() { return totalNodes.get(); }
    
    public IntegerProperty aliveNodesProperty() { return aliveNodes; }
    public int getAliveNodes() { return aliveNodes.get(); }
    
    public IntegerProperty deadNodesProperty() { return deadNodes; }
    public int getDeadNodes() { return deadNodes.get(); }
    
    public DoubleProperty avgCpuUsageProperty() { return avgCpuUsage; }
    public double getAvgCpuUsage() { return avgCpuUsage.get(); }
    
    public DoubleProperty avgMemoryUsageProperty() { return avgMemoryUsage; }
    public double getAvgMemoryUsage() { return avgMemoryUsage.get(); }
}
