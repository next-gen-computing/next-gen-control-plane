package com.nextgen.desktop.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Configuration for ControlPlane Server mode with Jackson serialization support.
 */
public class ServerConfig implements Serializable {
    
    @JsonProperty("predictorHost")
    private String predictorHost = "localhost";
    
    @JsonProperty("grpcPort")
    private int grpcPort = 50051;
    
    @JsonProperty("dashboardPort")
    private int dashboardPort = 8085;
    
    @JsonProperty("metricsPort")
    private int metricsPort = 9090;
    
    @JsonProperty("enablePredictor")
    private boolean enablePredictor = false;
    
    @JsonProperty("enableDashboard")
    private boolean enableDashboard = true;
    
    @JsonProperty("enableMetrics")
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
