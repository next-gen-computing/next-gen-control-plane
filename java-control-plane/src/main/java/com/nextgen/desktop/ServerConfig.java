package com.nextgen.desktop;

import java.io.Serializable;

/**
 * Configuration for ControlPlane Server mode
 */
public class ServerConfig implements Serializable {
    private String predictorHost = "localhost";
    private int grpcPort = 50051;
    private int dashboardPort = 8085;
    private int metricsPort = 9090;
    private boolean enablePredictor = false;
    private boolean enableDashboard = true;
    private boolean enableMetrics = true;
    
    public ServerConfig() {}
    
    // Getters and setters
    public String getPredictorHost() { return predictorHost; }
    public void setPredictorHost(String predictorHost) { this.predictorHost = predictorHost; }
    
    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }
    
    public int getDashboardPort() { return dashboardPort; }
    public void setDashboardPort(int dashboardPort) { this.dashboardPort = dashboardPort; }
    
    public int getMetricsPort() { return metricsPort; }
    public void setMetricsPort(int metricsPort) { this.metricsPort = metricsPort; }
    
    public boolean isEnablePredictor() { return enablePredictor; }
    public void setEnablePredictor(boolean enablePredictor) { this.enablePredictor = enablePredictor; }
    
    public boolean isEnableDashboard() { return enableDashboard; }
    public void setEnableDashboard(boolean enableDashboard) { this.enableDashboard = enableDashboard; }
    
    public boolean isEnableMetrics() { return enableMetrics; }
    public void setEnableMetrics(boolean enableMetrics) { this.enableMetrics = enableMetrics; }
}
