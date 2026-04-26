package com.nextgen.desktop;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;

/**
 * Status view for Node Agent Mode
 * Shows connection status and local metrics
 */
public class NodeStatusView extends VBox {
    private static final Logger LOG = LoggerFactory.getLogger(NodeStatusView.class);
    
    private static final String DARK_BG = "#0d1117";
    private static final String CARD_BG = "#161b22";
    private static final String BORDER_COLOR = "#30363d";
    private static final String ACCENT_GREEN = "#22c55e";
    private static final String ACCENT_RED = "#ef4444";
    private static final String ACCENT_YELLOW = "#eab308";
    private static final String TEXT_PRIMARY = "#f0f6fc";
    private static final String TEXT_SECONDARY = "#7d8590";
    
    private NodeConfig config;
    private Label connectionStatus;
    private Circle statusIndicator;
    private Label nodeIdLabel;
    private Label serverLabel;
    private ProgressBar cpuProgress;
    private Label cpuLabel;
    private ProgressBar memoryProgress;
    private Label memoryLabel;
    private Timeline updateTimeline;
    
    public NodeStatusView(NodeConfig config) {
        this.config = config;
        
        setSpacing(24);
        setPadding(new Insets(40));
        setStyle(String.format("-fx-background-color: %s;", DARK_BG));
        
        // Header
        Label title = new Label("Node Agent Status");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web(TEXT_PRIMARY));
        
        Label subtitle = new Label("Real-time connection and system metrics");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        subtitle.setTextFill(Color.web(TEXT_SECONDARY));
        
        getChildren().addAll(title, subtitle);
        
        // Connection status card
        VBox connectionCard = createConnectionCard();
        getChildren().add(connectionCard);
        
        // System metrics card
        VBox metricsCard = createMetricsCard();
        getChildren().add(metricsCard);
        
        // Configuration card
        VBox configCard = createConfigCard();
        getChildren().add(configCard);
        
        // Start metrics update
        startMetricsUpdate();
    }
    
    private VBox createConnectionCard() {
        VBox card = new VBox(16);
        card.setPadding(new Insets(24));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 12px; " +
            "-fx-border-color: %s; -fx-border-radius: 12px; -fx-border-width: 1px;",
            CARD_BG, BORDER_COLOR));
        
        Label cardTitle = new Label("Connection Status");
        cardTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        cardTitle.setTextFill(Color.web(TEXT_PRIMARY));
        
        HBox statusRow = new HBox(12);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        
        statusIndicator = new Circle(8);
        statusIndicator.setFill(Color.web(ACCENT_YELLOW));
        
        connectionStatus = new Label("Connecting...");
        connectionStatus.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        connectionStatus.setTextFill(Color.web(ACCENT_YELLOW));
        
        statusRow.getChildren().addAll(statusIndicator, connectionStatus);
        
        Label serverInfo = new Label("Server: " + config.getServerHost() + ":" + config.getServerPort());
        serverInfo.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        serverInfo.setTextFill(Color.web(TEXT_SECONDARY));
        
        card.getChildren().addAll(cardTitle, statusRow, serverInfo);
        
        return card;
    }
    
    private VBox createMetricsCard() {
        VBox card = new VBox(16);
        card.setPadding(new Insets(24));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 12px;",
            CARD_BG));
        
        Label cardTitle = new Label("System Metrics");
        cardTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        cardTitle.setTextFill(Color.web(TEXT_PRIMARY));
        
        // CPU
        HBox cpuRow = new HBox(16);
        cpuRow.setAlignment(Pos.CENTER_LEFT);
        
        Label cpuTextLabel = new Label("CPU Usage");
        cpuTextLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        cpuTextLabel.setTextFill(Color.web(TEXT_SECONDARY));
        cpuTextLabel.setPrefWidth(100);
        
        cpuProgress = new ProgressBar(0);
        cpuProgress.setPrefWidth(300);
        HBox.setHgrow(cpuProgress, Priority.ALWAYS);
        
        cpuLabel = new Label("0.0%");
        cpuLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        cpuLabel.setTextFill(Color.web(TEXT_PRIMARY));
        cpuLabel.setPrefWidth(80);
        
        cpuRow.getChildren().addAll(cpuTextLabel, cpuProgress, cpuLabel);
        
        // Memory
        HBox memoryRow = new HBox(16);
        memoryRow.setAlignment(Pos.CENTER_LEFT);
        
        Label memoryTextLabel = new Label("Memory");
        memoryTextLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        memoryTextLabel.setTextFill(Color.web(TEXT_SECONDARY));
        memoryTextLabel.setPrefWidth(100);
        
        memoryProgress = new ProgressBar(0);
        memoryProgress.setPrefWidth(300);
        HBox.setHgrow(memoryProgress, Priority.ALWAYS);
        
        memoryLabel = new Label("0.0%");
        memoryLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        memoryLabel.setTextFill(Color.web(TEXT_PRIMARY));
        memoryLabel.setPrefWidth(80);
        
        memoryRow.getChildren().addAll(memoryTextLabel, memoryProgress, memoryLabel);
        
        card.getChildren().addAll(cardTitle, cpuRow, memoryRow);
        
        return card;
    }
    
    private VBox createConfigCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(24));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 12px;",
            CARD_BG));
        
        Label cardTitle = new Label("Configuration");
        cardTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        cardTitle.setTextFill(Color.web(TEXT_PRIMARY));
        
        nodeIdLabel = new Label("Node ID: " + config.getNodeId());
        nodeIdLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        nodeIdLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        serverLabel = new Label("Server: " + config.getServerHost() + ":" + config.getServerPort());
        serverLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        serverLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        Label heartbeatLabel = new Label("Heartbeat: " + config.getHeartbeatInterval() + "s");
        heartbeatLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        heartbeatLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        Label reconnectLabel = new Label("Auto-reconnect: " + (config.isAutoReconnect() ? "Yes" : "No"));
        reconnectLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        reconnectLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        card.getChildren().addAll(cardTitle, nodeIdLabel, serverLabel, heartbeatLabel, reconnectLabel);
        
        return card;
    }
    
    private void startMetricsUpdate() {
        updateTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateMetrics()));
        updateTimeline.setCycleCount(Timeline.INDEFINITE);
        updateTimeline.play();
    }
    
    private void updateMetrics() {
        Platform.runLater(() -> {
            try {
                com.sun.management.OperatingSystemMXBean osBean = 
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                
                // CPU
                double cpuLoad = osBean.getCpuLoad();
                if (cpuLoad >= 0) {
                    double cpuPercent = cpuLoad * 100.0;
                    cpuProgress.setProgress(cpuLoad);
                    cpuLabel.setText(String.format("%.1f%%", cpuPercent));
                }
                
                // Memory (approximate using Runtime)
                Runtime runtime = Runtime.getRuntime();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;
                double memoryPercent = (double) usedMemory / totalMemory * 100.0;
                
                memoryProgress.setProgress(usedMemory / (double) totalMemory);
                memoryLabel.setText(String.format("%.1f%%", memoryPercent));
                
            } catch (Exception e) {
                LOG.debug("Failed to update metrics: {}", e.getMessage());
            }
        });
    }
    
    public void setConnected(boolean connected) {
        Platform.runLater(() -> {
            if (connected) {
                statusIndicator.setFill(Color.web(ACCENT_GREEN));
                connectionStatus.setText("Connected");
                connectionStatus.setTextFill(Color.web(ACCENT_GREEN));
            } else {
                statusIndicator.setFill(Color.web(ACCENT_RED));
                connectionStatus.setText("Disconnected");
                connectionStatus.setTextFill(Color.web(ACCENT_RED));
            }
        });
    }
    
    public void stop() {
        if (updateTimeline != null) {
            updateTimeline.stop();
        }
    }
}
