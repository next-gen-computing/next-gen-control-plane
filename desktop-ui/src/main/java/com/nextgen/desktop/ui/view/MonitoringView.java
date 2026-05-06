package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Live monitoring screen with performance metrics, real-time charts, and logs.
 */
public class MonitoringView {
    private final NodeMonitoringService monitoringService;
    private final VBox root;
    private final VBox logsContainer;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final XYChart.Series<Number, Number> cpuSeries;
    private final XYChart.Series<Number, Number> memorySeries;
    private int tick = 0;
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();

    public MonitoringView(NodeMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
        this.cpuSeries = new XYChart.Series<>();
        this.cpuSeries.setName("CPU %");
        this.memorySeries = new XYChart.Series<>();
        this.memorySeries.setName("Memory %");
        this.root = createView();
        this.logsContainer = (VBox) root.lookup("#logsContainer");

        addLog("Monitoring started. Waiting for node connections...");
        startChartUpdater();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createView() {
        VBox view = new VBox(24);
        view.setPadding(new Insets(24));
        view.getStyleClass().add("monitoring-view");

        // Title
        Label title = new Label("Live Monitoring");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        title.setTextFill(Color.web("#F8FAFC"));

        // Metrics row
        HBox metricsRow = createMetricsRow();

        // Charts section
        HBox chartsRow = createChartsRow();

        // Logs
        Label logsTitle = new Label("Application Logs");
        logsTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        logsTitle.setTextFill(Color.web("#F8FAFC"));

        VBox logs = new VBox(4);
        logs.setPadding(new Insets(12));
        logs.setStyle("-fx-background-color: rgba(11, 15, 25, 0.6); -fx-background-radius: 12px; -fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 12px;");
        logs.setId("logsContainer");

        ScrollPane logsScroll = new ScrollPane(logs);
        logsScroll.setFitToWidth(true);
        logsScroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(logsScroll, Priority.ALWAYS);

        view.getChildren().addAll(title, metricsRow, chartsRow, logsTitle, logsScroll);
        VBox.setVgrow(logsScroll, Priority.ALWAYS);

        // Listen for node changes
        monitoringService.getNodes().addListener((javafx.collections.ListChangeListener<NodeModel>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (NodeModel node : change.getAddedSubList()) {
                        addLog("Node connected: " + node.getName() + " (" + node.getIp() + ")");
                    }
                }
            }
        });

        return view;
    }

    private HBox createMetricsRow() {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        row.getChildren().addAll(
                createGlassMetricCard("Cluster CPU", "0.0%", "#3B82F6"),
                createGlassMetricCard("Cluster Memory", "0.0%", "#10B981"),
                createGlassMetricCard("Total Nodes", "0", "#8B5CF6"),
                createGlassMetricCard("Active Tasks", "0", "#F59E0B")
        );

        var summary = monitoringService.getClusterSummary();
        summary.avgCpuUsageProperty().addListener((obs, old, val) ->
                updateMetricCard(row, 0, String.format("%.1f%%", val.doubleValue())));
        summary.avgMemoryUsageProperty().addListener((obs, old, val) ->
                updateMetricCard(row, 1, String.format("%.1f%%", val.doubleValue())));
        summary.totalNodesProperty().addListener((obs, old, val) ->
                updateMetricCard(row, 2, String.valueOf(val.intValue())));
        summary.activeTasksProperty().addListener((obs, old, val) ->
                updateMetricCard(row, 3, String.valueOf(val.intValue())));

        return row;
    }

    private VBox createGlassMetricCard(String label, String value, String color) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(20));
        card.setPrefWidth(200);
        card.setStyle(String.format(
                "-fx-background-color: rgba(30, 41, 59, 0.45); -fx-background-radius: 16px; " +
                        "-fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 16px; -fx-border-width: 1px;"));
        card.setAlignment(Pos.CENTER);

        Region accent = new Region();
        accent.setPrefHeight(3);
        accent.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 2px;", color));
        VBox.setMargin(accent, new Insets(-20, -20, 10, -20));

        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        valueLabel.setTextFill(Color.web(color));
        valueLabel.setId("metric-value-" + label.hashCode());

        Label labelLabel = new Label(label);
        labelLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        labelLabel.setTextFill(Color.web("#94A3B8"));

        card.getChildren().addAll(accent, valueLabel, labelLabel);
        return card;
    }

    private void updateMetricCard(HBox row, int index, String value) {
        Platform.runLater(() -> {
            if (index < row.getChildren().size()) {
                VBox card = (VBox) row.getChildren().get(index);
                for (javafx.scene.Node child : card.getChildren()) {
                    if (child instanceof Label lbl && lbl.getFont().getSize() == 28) {
                        lbl.setText(value);
                    }
                }
            }
        });
    }

    private HBox createChartsRow() {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (s)");
        xAxis.setForceZeroInRange(false);
        xAxis.setTickLabelsVisible(false);
        xAxis.setPrefWidth(0);

        NumberAxis yAxis = new NumberAxis(0, 100, 10);
        yAxis.setLabel("%");
        yAxis.setAutoRanging(false);

        AreaChart<Number, Number> chart = new AreaChart<>(xAxis, yAxis);
        chart.setTitle("Cluster CPU & Memory History");
        chart.getData().addAll(cpuSeries, memorySeries);
        chart.setPrefHeight(260);
        chart.setPrefWidth(600);
        chart.setLegendVisible(true);
        chart.setCreateSymbols(false);
        chart.setStyle("-fx-background-color: transparent;");

        cpuSeries.getNode().setStyle("-fx-stroke: #3B82F6; -fx-fill: #3B82F622;");
        memorySeries.getNode().setStyle("-fx-stroke: #10B981; -fx-fill: #10B98122;");

        row.getChildren().add(chart);
        HBox.setHgrow(chart, Priority.ALWAYS);
        return row;
    }

    private void startChartUpdater() {
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(2),
                        e -> {
                            tick++;
                            double cpu = monitoringService.getClusterSummary().getAvgCpuUsage();
                            double mem = monitoringService.getClusterSummary().getAvgMemoryUsage();
                            Platform.runLater(() -> {
                                cpuSeries.getData().add(new XYChart.Data<>(tick, cpu));
                                memorySeries.getData().add(new XYChart.Data<>(tick, mem));
                                if (cpuSeries.getData().size() > 30) {
                                    cpuSeries.getData().remove(0);
                                    memorySeries.getData().remove(0);
                                }
                            });
                        }
                )
        );
        timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timeline.play();
    }

    private void addLog(String message) {
        logQueue.offer(message);
        Platform.runLater(() -> {
            while (!logQueue.isEmpty()) {
                String msg = logQueue.poll();
                String timestamp = LocalDateTime.now().format(timeFormatter);
                Label logLine = new Label("[" + timestamp + "] " + msg);
                logLine.setFont(Font.font("JetBrains Mono", 12));
                logLine.setTextFill(Color.web("#94A3B8"));
                logLine.setWrapText(true);
                logsContainer.getChildren().add(logLine);
            }
            if (logsContainer.getChildren().size() > 100) {
                logsContainer.getChildren().remove(0, logsContainer.getChildren().size() - 100);
            }
        });
    }
}
