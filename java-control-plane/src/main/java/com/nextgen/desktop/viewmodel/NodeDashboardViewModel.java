package com.nextgen.desktop.viewmodel;

import com.nextgen.desktop.model.NodeConfig;
import com.nextgen.desktop.service.*;
import javafx.beans.property.*;

/**
 * ViewModel for Node Dashboard with connection monitoring.
 */
public class NodeDashboardViewModel extends BaseViewModel {
    
    private final NodeProcessService nodeProcessService;
    private final MetricsService metricsService;
    private final ConfigurationService configurationService;
    
    // Node status
    private final BooleanProperty nodeRunning = new SimpleBooleanProperty(false);
    private final StringProperty connectionStatus = new SimpleStringProperty("Disconnected");
    private final StringProperty nodeId = new SimpleStringProperty("");
    private final StringProperty serverHost = new SimpleStringProperty("");
    
    // Metrics
    private final DoubleProperty cpuUsage = new SimpleDoubleProperty(0.0);
    private final DoubleProperty memoryUsage = new SimpleDoubleProperty(0.0);
    
    public NodeDashboardViewModel(NodeProcessService nodeProcessService,
                                  MetricsService metricsService,
                                  ConfigurationService configurationService) {
        this.nodeProcessService = nodeProcessService;
        this.metricsService = metricsService;
        this.configurationService = configurationService;
        
        // Bind to metrics service
        cpuUsage.bind(metricsService.localCpuUsageProperty());
        memoryUsage.bind(metricsService.localMemoryUsageProperty());
    }
    
    /**
     * Start the node agent with configuration.
     */
    public void startNode(NodeConfig config) {
        executeAsync(() -> {
            try {
                nodeProcessService.start(config.getNodeId(), config.getServerHost(), config.getServerPort());
                
                javafx.application.Platform.runLater(() -> {
                    nodeRunning.set(true);
                    connectionStatus.set("Connected");
                    nodeId.set(config.getNodeId());
                    serverHost.set(config.getServerHost());
                    setSuccessMessage("Node connected to server");
                });
                
                // Start monitoring
                metricsService.startLocalMonitoring();
                
                // Save configuration
                configurationService.saveNodeConfig(config);
                
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "Connecting to server...");
    }
    
    /**
     * Stop the node agent.
     */
    public void stopNode() {
        executeAsync(() -> {
            nodeProcessService.stop();
            metricsService.stopMonitoring();
            
            javafx.application.Platform.runLater(() -> {
                nodeRunning.set(false);
                connectionStatus.set("Disconnected");
                setSuccessMessage("Node disconnected");
            });
        }, "Disconnecting...");
    }
    
    /**
     * Disconnect from current server and return to mode selection.
     */
    public void disconnect(Runnable onDisconnected) {
        stopNode();
        onDisconnected.run();
    }
    
    // Property accessors
    public BooleanProperty nodeRunningProperty() { return nodeRunning; }
    public boolean isNodeRunning() { return nodeRunning.get(); }
    
    public StringProperty connectionStatusProperty() { return connectionStatus; }
    public String getConnectionStatus() { return connectionStatus.get(); }
    
    public StringProperty nodeIdProperty() { return nodeId; }
    public String getNodeId() { return nodeId.get(); }
    
    public StringProperty serverHostProperty() { return serverHost; }
    public String getServerHost() { return serverHost.get(); }
    
    public DoubleProperty cpuUsageProperty() { return cpuUsage; }
    public double getCpuUsage() { return cpuUsage.get(); }
    
    public DoubleProperty memoryUsageProperty() { return memoryUsage; }
    public double getMemoryUsage() { return memoryUsage.get(); }
}
