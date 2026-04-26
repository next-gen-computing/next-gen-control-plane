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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * Overview dashboard view for Server Mode
 * Shows cluster status, node count, and average metrics
 */
public class OverviewView extends VBox {
    private static final Logger LOG = LoggerFactory.getLogger(OverviewView.class);
    
    private static final String DARK_BG = "#0d1117";
    private static final String CARD_BG = "#161b22";
    private static final String BORDER_COLOR = "#30363d";
    private static final String ACCENT_BLUE = "#3b82f6";
    private static final String ACCENT_GREEN = "#22c55e";
    private static final String ACCENT_RED = "#ef4444";
    private static final String TEXT_PRIMARY = "#f0f6fc";
    private static final String TEXT_SECONDARY = "#7d8590";
    
    private Label totalNodesLabel;
    private Label aliveNodesLabel;
    private Label deadNodesLabel;
    private Label avgCpuLabel;
    private Label avgMemoryLabel;
    private ProgressBar cpuProgress;
    private ProgressBar memoryProgress;
    private Timeline updateTimeline;
    
    public OverviewView() {
        setSpacing(24);
        setPadding(new Insets(40));
        setStyle(String.format("-fx-background-color: %s;", DARK_BG));
        
        // Header
        Label title = new Label("Cluster Overview");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web(TEXT_PRIMARY));
        
        Label subtitle = new Label("Real-time cluster health and performance metrics");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        subtitle.setTextFill(Color.web(TEXT_SECONDARY));
        
        getChildren().addAll(title, subtitle);
        
        // Stats cards
        HBox statsRow = new HBox(20);
        statsRow.setAlignment(Pos.CENTER_LEFT);
        
        totalNodesLabel = createStatsCard("Total Nodes", "0", ACCENT_BLUE);
        aliveNodesLabel = createStatsCard("Alive", "0", ACCENT_GREEN);
        deadNodesLabel = createStatsCard("Dead", "0", ACCENT_RED);
        
        statsRow.getChildren().addAll(totalNodesLabel, aliveNodesLabel, deadNodesLabel);
        getChildren().add(statsRow);
        
        // Average metrics section
        VBox metricsBox = createMetricsSection();
        getChildren().add(metricsBox);
        
        // Start polling for updates
        startPolling();
    }
    
    private Label createStatsCard(String title, String value, String accentColor) {
        VBox card = new VBox(8);
        card.setPrefWidth(200);
        card.setPadding(new Insets(24));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 12px; " +
            "-fx-border-color: %s; -fx-border-radius: 12px; -fx-border-width: 1px;",
            CARD_BG, BORDER_COLOR));
        
        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        titleLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 36));
        valueLabel.setTextFill(Color.web(accentColor));
        
        card.getChildren().addAll(titleLabel, valueLabel);
        
        // Store reference for updates
        if (title.equals("Total Nodes")) totalNodesLabel = valueLabel;
        else if (title.equals("Alive")) aliveNodesLabel = valueLabel;
        else if (title.equals("Dead")) deadNodesLabel = valueLabel;
        
        return valueLabel;
    }
    
    private VBox createMetricsSection() {
        VBox section = new VBox(16);
        section.setPadding(new Insets(24));
        section.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 12px;",
            CARD_BG));
        
        Label sectionTitle = new Label("Average Cluster Metrics");
        sectionTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        sectionTitle.setTextFill(Color.web(TEXT_PRIMARY));
        
        // CPU row
        HBox cpuRow = new HBox(16);
        cpuRow.setAlignment(Pos.CENTER_LEFT);
        
        Label cpuLabel = new Label("CPU Usage");
        cpuLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        cpuLabel.setTextFill(Color.web(TEXT_SECONDARY));
        cpuLabel.setPrefWidth(100);
        
        cpuProgress = new ProgressBar(0);
        cpuProgress.setPrefWidth(400);
        cpuProgress.setStyle("-fx-accent: #3b82f6;");
        HBox.setHgrow(cpuProgress, Priority.ALWAYS);
        
        avgCpuLabel = new Label("0.0%");
        avgCpuLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        avgCpuLabel.setTextFill(Color.web(TEXT_PRIMARY));
        avgCpuLabel.setPrefWidth(80);
        
        cpuRow.getChildren().addAll(cpuLabel, cpuProgress, avgCpuLabel);
        
        // Memory row
        HBox memoryRow = new HBox(16);
        memoryRow.setAlignment(Pos.CENTER_LEFT);
        
        Label memoryLabel = new Label("Memory");
        memoryLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        memoryLabel.setTextFill(Color.web(TEXT_SECONDARY));
        memoryLabel.setPrefWidth(100);
        
        memoryProgress = new ProgressBar(0);
        memoryProgress.setPrefWidth(400);
        memoryProgress.setStyle("-fx-accent: #22c55e;");
        HBox.setHgrow(memoryProgress, Priority.ALWAYS);
        
        avgMemoryLabel = new Label("0.0%");
        avgMemoryLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        avgMemoryLabel.setTextFill(Color.web(TEXT_PRIMARY));
        avgMemoryLabel.setPrefWidth(80);
        
        memoryRow.getChildren().addAll(memoryLabel, memoryProgress, avgMemoryLabel);
        
        section.getChildren().addAll(sectionTitle, cpuRow, memoryRow);
        
        return section;
    }
    
    private void startPolling() {
        updateTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> fetchMetrics()));
        updateTimeline.setCycleCount(Timeline.INDEFINITE);
        updateTimeline.play();
    }
    
    private void fetchMetrics() {
        CompletableFuture.runAsync(() -> {
            try {
                URL url = URI.create("http://localhost:8085/api/nodes").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    parseAndUpdate(response.toString());
                }
                conn.disconnect();
            } catch (Exception e) {
                LOG.debug("Failed to fetch metrics: {}", e.getMessage());
            }
        });
    }
    
    private void parseAndUpdate(String json) {
        Platform.runLater(() -> {
            try {
                // Simple JSON parsing for display
                int totalNodes = 0, aliveNodes = 0, deadNodes = 0;
                double avgCpu = 0.0, avgMemory = 0.0;
                
                // Extract summary values from JSON
                if (json.contains("\"summary\"")) {
                    int summaryStart = json.indexOf("\"summary\":");
                    String summary = json.substring(summaryStart);
                    
                    totalNodes = extractInt(summary, "\"totalNodes\":");
                    aliveNodes = extractInt(summary, "\"aliveNodes\":");
                    deadNodes = extractInt(summary, "\"deadNodes\":");
                    avgCpu = extractDouble(summary, "\"avgCpu\":");
                    avgMemory = extractDouble(summary, "\"avgMemory\":");
                }
                
                totalNodesLabel.setText(String.valueOf(totalNodes));
                aliveNodesLabel.setText(String.valueOf(aliveNodes));
                deadNodesLabel.setText(String.valueOf(deadNodes));
                
                avgCpuLabel.setText(String.format("%.1f%%", avgCpu));
                avgMemoryLabel.setText(String.format("%.1f%%", avgMemory));
                
                cpuProgress.setProgress(avgCpu / 100.0);
                memoryProgress.setProgress(avgMemory / 100.0);
                
            } catch (Exception e) {
                LOG.debug("Failed to parse metrics: {}", e.getMessage());
            }
        });
    }
    
    private int extractInt(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx == -1) return 0;
            int start = idx + key.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }
    
    private double extractDouble(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx == -1) return 0.0;
            int start = idx + key.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
    
    public void stop() {
        if (updateTimeline != null) {
            updateTimeline.stop();
        }
    }
}
