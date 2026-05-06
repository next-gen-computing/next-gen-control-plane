package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.model.ClusterSummary;
import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * Main dashboard showing cluster summary and node cards with real-time metrics.
 */
public class DashboardView {
    private final NodeMonitoringService monitoringService;
    private final VBox root;
    private final FlowPane cardsContainer;
    private final HBox summaryBar;

    public DashboardView(NodeMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
        this.root = createView();

        this.cardsContainer = (FlowPane) root.lookup("#cardsContainer");
        this.summaryBar = (HBox) root.lookup("#summaryBar");

        bindData();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createView() {
        VBox view = new VBox(24);
        view.setPadding(new Insets(10));
        view.getStyleClass().add("dashboard-view");

        // Title
        Label title = new Label("Dashboard");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web("#f8fafc"));

        // Summary bar
        HBox summary = createSummaryBar();
        summary.setId("summaryBar");

        // Section title
        Label nodesTitle = new Label("Connected Nodes");
        nodesTitle.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        nodesTitle.setTextFill(Color.web("#f8fafc"));

        // Cards container
        FlowPane cards = new FlowPane(16, 16);
        cards.setId("cardsContainer");
        cards.setPrefWrapLength(Double.MAX_VALUE);

        view.getChildren().addAll(title, summary, nodesTitle, cards);
        return view;
    }

    private HBox createSummaryBar() {
        HBox bar = new HBox(16);
        bar.setAlignment(Pos.CENTER_LEFT);

        bar.getChildren().addAll(
                createSummaryCard("Total Nodes", "0", "#3b82f6"),
                createSummaryCard("Healthy", "0", "#10b981"),
                createSummaryCard("Warning", "0", "#f59e0b"),
                createSummaryCard("Avg CPU", "0%", "#8b5cf6"),
                createSummaryCard("Avg Memory", "0%", "#ec4899")
        );
        return bar;
    }

    private VBox createSummaryCard(String label, String value, String color) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(18, 28, 18, 28));
        card.setStyle(String.format(
                "-fx-background-color: rgba(30, 41, 59, 0.5); -fx-background-radius: 16px; " +
                        "-fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 16px; -fx-border-width: 1px;"));
        card.setPrefWidth(170);
        card.setAlignment(Pos.CENTER);

        // Top colored accent bar
        Region accent = new Region();
        accent.setPrefHeight(3);
        accent.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 2px;", color));
        VBox.setMargin(accent, new Insets(-18, -28, 12, -28));

        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        valueLabel.setTextFill(Color.web(color));
        valueLabel.setId("summary-" + label.toLowerCase().replace(" ", "-"));

        Label labelLabel = new Label(label);
        labelLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        labelLabel.setTextFill(Color.web("#94A3B8"));

        card.getChildren().addAll(accent, valueLabel, labelLabel);

        // Hover lift effect
        card.setOnMouseEntered(e -> {
            card.setStyle(String.format(
                    "-fx-background-color: rgba(30, 41, 59, 0.7); -fx-background-radius: 16px; " +
                            "-fx-border-color: %s44; -fx-border-radius: 16px; -fx-border-width: 1px;", color));
            card.setEffect(new DropShadow(20, Color.web(color + "44")));
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.03); st.setToY(1.03); st.play();
        });
        card.setOnMouseExited(e -> {
            card.setStyle(String.format(
                    "-fx-background-color: rgba(30, 41, 59, 0.5); -fx-background-radius: 16px; " +
                            "-fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 16px; -fx-border-width: 1px;"));
            card.setEffect(null);
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.0); st.setToY(1.0); st.play();
        });

        return card;
    }

    private void bindData() {
        ClusterSummary summary = monitoringService.getClusterSummary();

        // Bind summary values
        summary.totalNodesProperty().addListener((obs, old, val) ->
                updateSummaryLabel("summary-total-nodes", String.valueOf(val)));
        summary.healthyNodesProperty().addListener((obs, old, val) ->
                updateSummaryLabel("summary-healthy", String.valueOf(val)));
        summary.warningNodesProperty().addListener((obs, old, val) ->
                updateSummaryLabel("summary-warning", String.valueOf(val)));
        summary.avgCpuUsageProperty().addListener((obs, old, val) ->
                updateSummaryLabel("summary-avg-cpu", String.format("%.1f%%", val.doubleValue())));
        summary.avgMemoryUsageProperty().addListener((obs, old, val) ->
                updateSummaryLabel("summary-avg-memory", String.format("%.1f%%", val.doubleValue())));

        // Bind node list
        monitoringService.getNodes().addListener((javafx.collections.ListChangeListener<NodeModel>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (NodeModel node : change.getAddedSubList()) {
                        Platform.runLater(() -> addNodeCard(node));
                    }
                }
                if (change.wasRemoved()) {
                    for (NodeModel node : change.getRemoved()) {
                        Platform.runLater(() -> removeNodeCard(node));
                    }
                }
            }
        });

        // Add existing nodes
        for (NodeModel node : monitoringService.getNodes()) {
            addNodeCard(node);
        }
    }

    private void updateSummaryLabel(String id, String value) {
        Platform.runLater(() -> {
            for (javafx.scene.Node node : summaryBar.getChildren()) {
                if (node instanceof VBox card) {
                    for (javafx.scene.Node child : card.getChildren()) {
                        if (child instanceof Label label && id.equals(label.getId())) {
                            label.setText(value);
                        }
                    }
                }
            }
        });
    }

    private void addNodeCard(NodeModel node) {
        NodeCard card = new NodeCard(node);
        card.getRoot().setId("card-" + node.getId());
        cardsContainer.getChildren().add(card.getRoot());
    }

    private void removeNodeCard(NodeModel node) {
        cardsContainer.getChildren().removeIf(n -> ("card-" + node.getId()).equals(n.getId()));
    }
}
