package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.model.ClusterSummary;
import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Advanced dashboard with animated cluster health ring, live stats cards,
 * and professional node cards grid with real-time updates.
 */
public class DashboardView {
    private final NodeMonitoringService monitoringService;
    private final VBox root;
    private final FlowPane cardsContainer;

    // Summary stat labels for live updates
    private Label totalNodesValue;
    private Label healthyNodesValue;
    private Label warningNodesValue;
    private Label avgCpuValue;
    private Label avgMemValue;
    private Label lastUpdateValue;
    private Canvas healthRing;
    private Label healthPercentLabel;

    // Track node cards by ID for efficient updates
    private final Map<String, NodeCard> nodeCards = new HashMap<>();

    // Empty state
    private VBox emptyState;

    public DashboardView(NodeMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
        this.cardsContainer = new FlowPane(16, 16);
        this.root = createView();
        bindData();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createView() {
        VBox view = new VBox(20);
        view.setPadding(new Insets(8));
        view.setStyle("-fx-background-color: transparent;");

        // ── Title row ──
        HBox titleRow = new HBox(12);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Dashboard");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 30));
        title.setTextFill(Color.web("#F8FAFC"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        lastUpdateValue = new Label("Updated: --:--:--");
        lastUpdateValue.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        lastUpdateValue.setTextFill(Color.web("#475569"));

        // Live indicator
        HBox liveIndicator = createLiveIndicator();

        titleRow.getChildren().addAll(title, spacer, lastUpdateValue, liveIndicator);

        // ── Top panel: Health Ring + Stats Grid ──
        HBox topPanel = createTopPanel();

        // ── Nodes Section Title ──
        Label nodesTitle = new Label("Connected Nodes");
        nodesTitle.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        nodesTitle.setTextFill(Color.web("#F8FAFC"));
        VBox.setMargin(nodesTitle, new Insets(8, 0, 0, 0));

        // ── Node cards container ──
        cardsContainer.setPrefWrapLength(Double.MAX_VALUE);
        cardsContainer.setHgap(16);
        cardsContainer.setVgap(16);

        // Empty state (shown when no nodes)
        emptyState = createEmptyState();

        // Wrap cards in a StackPane so we can overlay empty state
        StackPane cardsWrapper = new StackPane();
        cardsWrapper.setAlignment(Pos.TOP_LEFT);
        cardsWrapper.getChildren().addAll(cardsContainer, emptyState);

        // ScrollPane for cards
        ScrollPane scrollPane = new ScrollPane(cardsWrapper);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        view.getChildren().addAll(titleRow, topPanel, nodesTitle, scrollPane);

        // Entrance animation
        view.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(400), view);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        return view;
    }

    private HBox createLiveIndicator() {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(4, 12, 4, 8));
        box.setStyle("-fx-background-color: rgba(16, 185, 129, 0.1); -fx-background-radius: 8px;");

        Circle dot = new Circle(4, Color.web("#10B981"));
        ScaleTransition pulse = new ScaleTransition(Duration.seconds(1), dot);
        pulse.setFromX(0.7); pulse.setFromY(0.7);
        pulse.setToX(1.3); pulse.setToY(1.3);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.play();

        Label liveLabel = new Label("LIVE");
        liveLabel.setFont(Font.font("Inter", FontWeight.BOLD, 10));
        liveLabel.setTextFill(Color.web("#10B981"));

        box.getChildren().addAll(dot, liveLabel);
        return box;
    }

    private HBox createTopPanel() {
        HBox panel = new HBox(20);
        panel.setAlignment(Pos.CENTER_LEFT);

        // ── Health Ring ──
        VBox healthBox = createHealthRingBox();

        // ── Stats Grid ──
        GridPane statsGrid = createStatsGrid();
        HBox.setHgrow(statsGrid, Priority.ALWAYS);

        panel.getChildren().addAll(healthBox, statsGrid);
        return panel;
    }

    private VBox createHealthRingBox() {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        box.setMinWidth(200);
        box.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.6);" +
                "-fx-background-radius: 18px;" +
                "-fx-border-color: rgba(255,255,255,0.04);" +
                "-fx-border-radius: 18px;" +
                "-fx-border-width: 1px;");

        // Canvas for the health ring
        healthRing = new Canvas(140, 140);
        drawHealthRing(100);

        healthPercentLabel = new Label("100%");
        healthPercentLabel.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        healthPercentLabel.setTextFill(Color.web("#10B981"));

        Label healthSubtitle = new Label("Cluster Health");
        healthSubtitle.setFont(Font.font("Inter", FontWeight.MEDIUM, 11));
        healthSubtitle.setTextFill(Color.web("#64748B"));

        StackPane ringStack = new StackPane();
        VBox ringOverlay = new VBox(2);
        ringOverlay.setAlignment(Pos.CENTER);
        ringOverlay.getChildren().addAll(healthPercentLabel, healthSubtitle);
        ringStack.getChildren().addAll(healthRing, ringOverlay);

        Label ringTitle = new Label("CLUSTER STATUS");
        ringTitle.setFont(Font.font("Inter", FontWeight.BOLD, 10));
        ringTitle.setTextFill(Color.web("#475569"));

        box.getChildren().addAll(ringTitle, ringStack);
        return box;
    }

    private void drawHealthRing(double healthPercent) {
        GraphicsContext gc = healthRing.getGraphicsContext2D();
        double w = healthRing.getWidth();
        double h = healthRing.getHeight();
        gc.clearRect(0, 0, w, h);

        double arcWidth = 10;
        double padding = arcWidth / 2 + 4;
        double diameter = Math.min(w, h) - padding * 2;
        double x = (w - diameter) / 2;
        double y = (h - diameter) / 2;

        // Background track
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(arcWidth);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.strokeArc(x, y, diameter, diameter, 90, -360, ArcType.OPEN);

        // Health arc
        double sweepAngle = -(healthPercent / 100.0) * 360;
        Color arcColor;
        if (healthPercent >= 80) {
            arcColor = Color.web("#10B981");
        } else if (healthPercent >= 50) {
            arcColor = Color.web("#F59E0B");
        } else {
            arcColor = Color.web("#EF4444");
        }

        gc.setStroke(arcColor);
        gc.setLineWidth(arcWidth);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.strokeArc(x, y, diameter, diameter, 90, sweepAngle, ArcType.OPEN);
    }

    private GridPane createStatsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);

        // Row 1
        VBox totalCard = createStatCard("Total Nodes", "0", "#3B82F6", "🖥️");
        totalNodesValue = (Label) ((VBox) totalCard.getChildren().get(1)).getChildren().get(0);

        VBox healthyCard = createStatCard("Healthy", "0", "#10B981", "✅");
        healthyNodesValue = (Label) ((VBox) healthyCard.getChildren().get(1)).getChildren().get(0);

        VBox warningCard = createStatCard("Warning", "0", "#F59E0B", "⚠️");
        warningNodesValue = (Label) ((VBox) warningCard.getChildren().get(1)).getChildren().get(0);

        // Row 2
        VBox cpuCard = createStatCard("Avg CPU", "0%", "#8B5CF6", "⚡");
        avgCpuValue = (Label) ((VBox) cpuCard.getChildren().get(1)).getChildren().get(0);

        VBox memCard = createStatCard("Avg Memory", "0%", "#EC4899", "💾");
        avgMemValue = (Label) ((VBox) memCard.getChildren().get(1)).getChildren().get(0);

        grid.add(totalCard, 0, 0);
        grid.add(healthyCard, 1, 0);
        grid.add(warningCard, 2, 0);
        grid.add(cpuCard, 0, 1);
        grid.add(memCard, 1, 1);

        // Make cards grow
        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setHgrow(Priority.ALWAYS);
            cc.setMinWidth(140);
            grid.getColumnConstraints().add(cc);
        }

        return grid;
    }

    private VBox createStatCard(String label, String value, String color, String icon) {
        VBox card = new VBox(4);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.5);" +
                "-fx-background-radius: 14px;" +
                "-fx-border-color: rgba(255,255,255,0.04);" +
                "-fx-border-radius: 14px;" +
                "-fx-border-width: 1px;");

        // Icon + Label header
        HBox headerRow = new HBox(6);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        Label iconLabel = new Label(icon);
        iconLabel.setFont(Font.font(12));
        Label labelLabel = new Label(label);
        labelLabel.setFont(Font.font("Inter", FontWeight.MEDIUM, 11));
        labelLabel.setTextFill(Color.web("#94A3B8"));
        headerRow.getChildren().addAll(iconLabel, labelLabel);

        // Value
        VBox valueBox = new VBox();
        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        valueLabel.setTextFill(Color.web(color));
        valueBox.getChildren().add(valueLabel);

        card.getChildren().addAll(headerRow, valueBox);

        // Hover effect
        card.setOnMouseEntered(e -> {
            card.setStyle(
                    "-fx-background-color: rgba(15, 23, 42, 0.7);" +
                    "-fx-background-radius: 14px;" +
                    "-fx-border-color: " + color + "33;" +
                    "-fx-border-radius: 14px;" +
                    "-fx-border-width: 1px;");
            card.setEffect(new DropShadow(12, Color.web(color, 0.15)));
        });
        card.setOnMouseExited(e -> {
            card.setStyle(
                    "-fx-background-color: rgba(15, 23, 42, 0.5);" +
                    "-fx-background-radius: 14px;" +
                    "-fx-border-color: rgba(255,255,255,0.04);" +
                    "-fx-border-radius: 14px;" +
                    "-fx-border-width: 1px;");
            card.setEffect(null);
        });

        return card;
    }

    private VBox createEmptyState() {
        VBox empty = new VBox(16);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(60));
        empty.setMaxWidth(500);

        // Animated waiting icon
        Label waitingIcon = new Label("📡");
        waitingIcon.setFont(Font.font(48));
        waitingIcon.setEffect(new DropShadow(20, Color.web("#3B82F6", 0.3)));

        // Pulse the icon
        ScaleTransition iconPulse = new ScaleTransition(Duration.seconds(2), waitingIcon);
        iconPulse.setFromX(0.9); iconPulse.setFromY(0.9);
        iconPulse.setToX(1.1); iconPulse.setToY(1.1);
        iconPulse.setCycleCount(Animation.INDEFINITE);
        iconPulse.setAutoReverse(true);
        iconPulse.play();

        Label emptyTitle = new Label("Waiting for Nodes");
        emptyTitle.setFont(Font.font("Inter", FontWeight.BOLD, 22));
        emptyTitle.setTextFill(Color.web("#F8FAFC"));

        Label emptyDesc = new Label(
                "No nodes connected yet. Share your Server ID with other\n" +
                "machines to have them join your cluster.");
        emptyDesc.setFont(Font.font("Inter", FontWeight.NORMAL, 13));
        emptyDesc.setTextFill(Color.web("#64748B"));
        emptyDesc.setTextAlignment(TextAlignment.CENTER);
        emptyDesc.setWrapText(true);

        // Animated dots
        Label dotsLabel = new Label("● ● ●");
        dotsLabel.setFont(Font.font(14));
        dotsLabel.setTextFill(Color.web("#3B82F6", 0.4));
        FadeTransition dotsFade = new FadeTransition(Duration.seconds(1.5), dotsLabel);
        dotsFade.setFromValue(0.3);
        dotsFade.setToValue(1.0);
        dotsFade.setCycleCount(Animation.INDEFINITE);
        dotsFade.setAutoReverse(true);
        dotsFade.play();

        empty.getChildren().addAll(waitingIcon, emptyTitle, emptyDesc, dotsLabel);
        return empty;
    }

    // ── Data Binding ──────────────────────────────

    private void bindData() {
        ClusterSummary summary = monitoringService.getClusterSummary();

        // Bind summary values
        summary.totalNodesProperty().addListener((obs, old, val) -> Platform.runLater(() -> {
            totalNodesValue.setText(String.valueOf(val));
            updateEmptyState(val.intValue());
        }));

        summary.healthyNodesProperty().addListener((obs, old, val) -> Platform.runLater(() -> {
            healthyNodesValue.setText(String.valueOf(val));
            updateHealthRing();
        }));

        summary.warningNodesProperty().addListener((obs, old, val) -> Platform.runLater(() -> {
            warningNodesValue.setText(String.valueOf(val));
            updateHealthRing();
        }));

        summary.avgCpuUsageProperty().addListener((obs, old, val) -> Platform.runLater(() ->
                avgCpuValue.setText(String.format("%.1f%%", val.doubleValue()))));

        summary.avgMemoryUsageProperty().addListener((obs, old, val) -> Platform.runLater(() ->
                avgMemValue.setText(String.format("%.1f%%", val.doubleValue()))));

        summary.lastUpdatedProperty().addListener((obs, old, val) -> Platform.runLater(() ->
                lastUpdateValue.setText("Updated: " + val)));

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

        // Initial empty state
        updateEmptyState(monitoringService.getNodes().size());
    }

    private void addNodeCard(NodeModel node) {
        if (nodeCards.containsKey(node.getId())) return;

        NodeCard card = new NodeCard(node);
        card.getRoot().setId("card-" + node.getId());
        nodeCards.put(node.getId(), card);

        // Fade in animation
        card.getRoot().setOpacity(0);
        card.getRoot().setScaleX(0.9);
        card.getRoot().setScaleY(0.9);
        cardsContainer.getChildren().add(card.getRoot());

        FadeTransition fadeIn = new FadeTransition(Duration.millis(400), card.getRoot());
        fadeIn.setToValue(1.0);
        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(400), card.getRoot());
        scaleIn.setToX(1.0);
        scaleIn.setToY(1.0);
        scaleIn.setInterpolator(Interpolator.SPLINE(0.25, 0.1, 0.25, 1));
        fadeIn.play();
        scaleIn.play();

        updateEmptyState(monitoringService.getNodes().size());
    }

    private void removeNodeCard(NodeModel node) {
        NodeCard card = nodeCards.remove(node.getId());
        if (card != null) {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), card.getRoot());
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> cardsContainer.getChildren().remove(card.getRoot()));
            fadeOut.play();
        }
        updateEmptyState(monitoringService.getNodes().size());
    }

    private void updateEmptyState(int nodeCount) {
        emptyState.setVisible(nodeCount == 0);
        emptyState.setManaged(nodeCount == 0);
    }

    private void updateHealthRing() {
        ClusterSummary summary = monitoringService.getClusterSummary();
        int total = summary.getTotalNodes();
        int healthy = summary.getHealthyNodes();
        double healthPercent = total > 0 ? (healthy * 100.0 / total) : 100;

        drawHealthRing(healthPercent);
        healthPercentLabel.setText(String.format("%.0f%%", healthPercent));

        if (healthPercent >= 80) {
            healthPercentLabel.setTextFill(Color.web("#10B981"));
        } else if (healthPercent >= 50) {
            healthPercentLabel.setTextFill(Color.web("#F59E0B"));
        } else {
            healthPercentLabel.setTextFill(Color.web("#EF4444"));
        }
    }
}
