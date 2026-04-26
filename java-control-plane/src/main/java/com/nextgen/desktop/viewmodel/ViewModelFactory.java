package com.nextgen.desktop.viewmodel;

import com.nextgen.desktop.service.*;
import com.nextgen.desktop.repository.*;

/**
 * Factory for creating ViewModels with their dependencies injected.
 * Ensures proper dependency injection across the application.
 */
public class ViewModelFactory {
    
    private static ViewModelFactory instance;
    
    private final ConfigurationService configurationService;
    private final ServerProcessService serverProcessService;
    private final NodeProcessService nodeProcessService;
    private final ApiPollingService apiPollingService;
    private final MetricsService metricsService;
    private final NodeRepository nodeRepository;
    private final ErrorHandler errorHandler;
    
    private ViewModelFactory() {
        // Create services in dependency order
        this.configurationService = new ConfigurationService();
        this.errorHandler = new ErrorHandler();
        this.nodeRepository = new NodeRepository();
        this.metricsService = new MetricsService(nodeRepository);
        this.apiPollingService = new ApiPollingService(nodeRepository, errorHandler);
        this.serverProcessService = new ServerProcessService(errorHandler);
        this.nodeProcessService = new NodeProcessService(errorHandler);
    }
    
    public static ViewModelFactory getInstance() {
        if (instance == null) {
            instance = new ViewModelFactory();
        }
        return instance;
    }
    
    public static void resetInstance() {
        if (instance != null) {
            instance.dispose();
            instance = null;
        }
    }
    
    public ModeSelectionViewModel createModeSelectionViewModel() {
        return new ModeSelectionViewModel(configurationService);
    }
    
    public ServerDashboardViewModel createServerDashboardViewModel() {
        return new ServerDashboardViewModel(
            serverProcessService, 
            apiPollingService, 
            metricsService,
            nodeRepository
        );
    }
    
    public NodeDashboardViewModel createNodeDashboardViewModel() {
        return new NodeDashboardViewModel(
            nodeProcessService,
            metricsService,
            configurationService
        );
    }
    
    private void dispose() {
        serverProcessService.dispose();
        nodeProcessService.dispose();
        apiPollingService.dispose();
        metricsService.dispose();
        configurationService.dispose();
    }
    
    // Getters for services (for testing)
    ConfigurationService getConfigurationService() { return configurationService; }
    ServerProcessService getServerProcessService() { return serverProcessService; }
    NodeProcessService getNodeProcessService() { return nodeProcessService; }
    ApiPollingService getApiPollingService() { return apiPollingService; }
    MetricsService getMetricsService() { return metricsService; }
    NodeRepository getNodeRepository() { return nodeRepository; }
    ErrorHandler getErrorHandler() { return errorHandler; }
}
