package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.model.NodeModel;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

/**
 * Premium node card component with animated arc gauges for CPU/Memory,
 * pulsing status dot, live heartbeat, glassmorphism design.
 */
public class NodeCard {
    private final VBox root;
    private final NodeModel node;

    // Gauge canvases for smooth arc animations
    private Canvas cpuGauge;
    private Canvas memGauge;
    private Label cpuValueLabel;
    private Label memValueLabel;
    private Label statusLabel;
    private Circle statusDot;
    private Label heartbeatLabel;
    private Label hostnameLabel;
    private Label riskLabel;
    private Label recommendLabel;

    // Current animated values (for smooth transitions)
    private double animatedCpu = 0;
    private double animatedMem = 0;

    private static final int GAUGE_SIZE = 80;
    private static final double ARC_WIDTH = 7;

    public NodeCard(NodeModel node) {
        this.node = node;
        this.root = createCard();
        bindProperties();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(22, 22, 18, 22));
        card.setPrefWidth(320);
        card.setMinWidth(300);
        card.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.65);" +
                "-fx-background-radius: 18px;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 18px;" +
                "-fx-border-width: 1px;");

        // ── Top accent gradient bar ──
        Region accent = new Region();
        accent.setPrefHeight(3);
        accent.setMaxWidth(Double.MAX_VALUE);
        accent.setStyle("-fx-background-color: linear-gradient(to right, " +
                getStatusColor(node.getStatus()) + ", " +
                getStatusSecondaryColor(node.getStatus()) + ");" +
                "-fx-background-radius: 18px 18px 0 0;");
        VBox.setMargin(accent, new Insets(-22, -22, 0, -22));

        // ── Header Row: name + status ──
        HBox header = createHeader();

        // ── Host info ──
        hostnameLabel = new Label(formatHostInfo());
        hostnameLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        hostnameLabel.setTextFill(Color.web("#64748B"));

        // ── Gauges Row ──
        HBox gaugesRow = createGaugesRow();

        // ── Stats Footer ──
        HBox statsFooter = createStatsFooter();

        card.getChildren().addAll(accent, header, hostnameLabel, gaugesRow, statsFooter);

        // ── Hover effect ──
        card.setOnMouseEntered(e -> {
            card.setStyle(
                    "-fx-background-color: rgba(15, 23, 42, 0.80);" +
                    "-fx-background-radius: 18px;" +
                    "-fx-border-color: " + getStatusColor(node.getStatus()) + "44;" +
                    "-fx-border-radius: 18px;" +
                    "-fx-border-width: 1px;");
            card.setEffect(new DropShadow(20, Color.web(getStatusColor(node.getStatus()), 0.2)));
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.02); st.setToY(1.02); st.play();
        });
        card.setOnMouseExited(e -> {
            card.setStyle(
                    "-fx-background-color: rgba(15, 23, 42, 0.65);" +
                    "-fx-background-radius: 18px;" +
                    "-fx-border-color: rgba(255,255,255,0.06);" +
                    "-fx-border-radius: 18px;" +
                    "-fx-border-width: 1px;");
            card.setEffect(null);
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.0); st.setToY(1.0); st.play();
        });

        return card;
    }

    private HBox createHeader() {
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        // Node name
        Label nameLabel = new Label(node.getName().isEmpty() ? node.getId() : node.getName());
        nameLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        nameLabel.setTextFill(Color.web("#F8FAFC"));
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        // Status badge with pulsing dot
        HBox statusBadge = new HBox(6);
        statusBadge.setAlignment(Pos.CENTER);
        statusBadge.setPadding(new Insets(4, 10, 4, 8));
        String statusColorHex = getStatusColor(node.getStatus());
        statusBadge.setStyle("-fx-background-color: " + statusColorHex + "18;" +
                "-fx-background-radius: 8px;");

        statusDot = new Circle(4);
        statusDot.setFill(Color.web(statusColorHex));

        // Pulse animation on status dot
        ScaleTransition pulse = new ScaleTransition(Duration.seconds(1.2), statusDot);
        pulse.setFromX(0.7); pulse.setFromY(0.7);
        pulse.setToX(1.3); pulse.setToY(1.3);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.play();

        statusLabel = new Label(node.getStatus());
        statusLabel.setFont(Font.font("Inter", FontWeight.BOLD, 10));
        statusLabel.setTextFill(Color.web(statusColorHex));

        statusBadge.getChildren().addAll(statusDot, statusLabel);
        header.getChildren().addAll(nameLabel, statusBadge);
        return header;
    }

    private HBox createGaugesRow() {
        HBox row = new HBox(20);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(8, 0, 4, 0));

        // CPU Gauge
        VBox cpuBox = createGaugeWithLabel("CPU", node.getCpuUsage(), "#3B82F6", "#60A5FA");
        cpuGauge = (Canvas) cpuBox.getChildren().get(0);

        // Memory Gauge
        VBox memBox = createGaugeWithLabel("Memory", node.getMemoryUsage(), "#10B981", "#34D399");
        memGauge = (Canvas) memBox.getChildren().get(0);

        row.getChildren().addAll(cpuBox, memBox);
        return row;
    }

    private VBox createGaugeWithLabel(String label, double value, String color1, String color2) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);

        // Canvas for arc gauge
        Canvas canvas = new Canvas(GAUGE_SIZE, GAUGE_SIZE);
        drawArcGauge(canvas, value, color1, color2);

        // Value overlay centered on canvas
        StackPane gaugeStack = new StackPane();
        Label valLabel = new Label(String.format("%.0f%%", value));
        valLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        valLabel.setTextFill(Color.web(color1));
        valLabel.setTextAlignment(TextAlignment.CENTER);

        gaugeStack.getChildren().addAll(canvas, valLabel);

        // Store references
        if ("CPU".equals(label)) {
            cpuValueLabel = valLabel;
        } else {
            memValueLabel = valLabel;
        }

        Label nameLabel = new Label(label);
        nameLabel.setFont(Font.font("Inter", FontWeight.MEDIUM, 11));
        nameLabel.setTextFill(Color.web("#94A3B8"));
        nameLabel.setTextAlignment(TextAlignment.CENTER);

        box.getChildren().addAll(gaugeStack, nameLabel);
        return box;
    }

    private void drawArcGauge(Canvas canvas, double percent, String color1, String color2) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);

        double padding = ARC_WIDTH / 2 + 2;
        double diameter = Math.min(w, h) - padding * 2;
        double x = (w - diameter) / 2;
        double y = (h - diameter) / 2;

        // Background track
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(ARC_WIDTH);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.strokeArc(x, y, diameter, diameter, -225, 270, javafx.scene.shape.ArcType.OPEN);

        // Value arc
        double sweepAngle = (percent / 100.0) * 270;
        if (sweepAngle > 0) {
            // Color based on value: green → yellow → red
            Color arcColor;
            if (percent < 60) {
                arcColor = Color.web(color1);
            } else if (percent < 85) {
                arcColor = Color.web("#F59E0B"); // Warning yellow
            } else {
                arcColor = Color.web("#EF4444"); // Danger red
            }
            gc.setStroke(arcColor);
            gc.setLineWidth(ARC_WIDTH);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.strokeArc(x, y, diameter, diameter, -225, sweepAngle, javafx.scene.shape.ArcType.OPEN);
        }
    }

    private HBox createStatsFooter() {
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(8, 0, 0, 0));

        // Separator line
        Region sep = new Region();
        sep.setPrefHeight(1);
        sep.setMaxWidth(Double.MAX_VALUE);
        sep.setStyle("-fx-background-color: rgba(255,255,255,0.04);");

        VBox footerContent = new VBox(6);

        // Heartbeat row
        HBox hbRow = new HBox(6);
        hbRow.setAlignment(Pos.CENTER_LEFT);
        Label hbIcon = new Label("💓");
        hbIcon.setFont(Font.font(10));
        heartbeatLabel = new Label("Last: " + node.getLastHeartbeat());
        heartbeatLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 10));
        heartbeatLabel.setTextFill(Color.web("#64748B"));
        hbRow.getChildren().addAll(hbIcon, heartbeatLabel);

        // Risk/Recommendation row
        HBox riskRow = new HBox(8);
        riskRow.setAlignment(Pos.CENTER_LEFT);

        riskLabel = new Label("Risk: " + node.getFailureProbability());
        riskLabel.setFont(Font.font("Inter", FontWeight.MEDIUM, 10));
        riskLabel.setTextFill(Color.web("#F59E0B"));

        recommendLabel = new Label(node.getRecommendation());
        recommendLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 10));
        recommendLabel.setTextFill(Color.web("#64748B"));
        recommendLabel.setMaxWidth(150);
        recommendLabel.setWrapText(true);

        riskRow.getChildren().addAll(riskLabel, recommendLabel);

        footerContent.getChildren().addAll(sep, hbRow, riskRow);
        footer.getChildren().add(footerContent);
        HBox.setHgrow(footerContent, Priority.ALWAYS);
        return footer;
    }

    // ── Data Binding ──────────────────────────────

    private void bindProperties() {
        // CPU gauge smooth animation
        node.cpuUsageProperty().addListener((obs, oldVal, newVal) -> {
            animateGauge(cpuGauge, cpuValueLabel, animatedCpu, newVal.doubleValue(),
                    "#3B82F6", "#60A5FA", true);
            animatedCpu = newVal.doubleValue();
        });

        // Memory gauge smooth animation
        node.memoryUsageProperty().addListener((obs, oldVal, newVal) -> {
            animateGauge(memGauge, memValueLabel, animatedMem, newVal.doubleValue(),
                    "#10B981", "#34D399", false);
            animatedMem = newVal.doubleValue();
        });

        // Status
        node.statusProperty().addListener((obs, oldVal, newVal) -> Platform.runLater(() -> {
            String color = getStatusColor(newVal);
            statusLabel.setText(newVal);
            statusLabel.setTextFill(Color.web(color));
            statusDot.setFill(Color.web(color));
        }));

        // Heartbeat
        node.lastHeartbeatProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> heartbeatLabel.setText("Last: " + newVal)));

        // Name
        node.nameProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> hostnameLabel.setText(formatHostInfo())));

        // IP
        node.ipProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> hostnameLabel.setText(formatHostInfo())));

        // Risk
        node.failureProbabilityProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> riskLabel.setText("Risk: " + newVal)));

        // Recommendation
        node.recommendationProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> recommendLabel.setText(newVal)));
    }

    private void animateGauge(Canvas gauge, Label valueLabel, double from, double to,
                               String color1, String color2, boolean isCpu) {
        Timeline timeline = new Timeline();
        final int frames = 20;
        for (int i = 0; i <= frames; i++) {
            final double progress = (double) i / frames;
            final double interpolated = from + (to - from) * easeOutCubic(progress);
            KeyFrame kf = new KeyFrame(Duration.millis(i * 25), e -> {
                Platform.runLater(() -> {
                    drawArcGauge(gauge, interpolated, color1, color2);
                    valueLabel.setText(String.format("%.0f%%", interpolated));

                    // Dynamic color for value label
                    if (interpolated < 60) {
                        valueLabel.setTextFill(Color.web(color1));
                    } else if (interpolated < 85) {
                        valueLabel.setTextFill(Color.web("#F59E0B"));
                    } else {
                        valueLabel.setTextFill(Color.web("#EF4444"));
                    }
                });
            });
            timeline.getKeyFrames().add(kf);
        }
        timeline.play();
    }

    private double easeOutCubic(double t) {
        return 1 - Math.pow(1 - t, 3);
    }

    private String formatHostInfo() {
        String hostname = node.getHostname();
        String ip = node.getIp();
        int port = node.getPort();
        if (hostname != null && !hostname.isEmpty() && ip != null && !ip.isEmpty()) {
            return hostname + "  ·  " + ip + ":" + port;
        } else if (ip != null && !ip.isEmpty()) {
            return ip + ":" + port;
        }
        return "Connecting...";
    }

    // ── Color Helpers ─────────────────────────────

    private String getStatusColor(String status) {
        return switch (status) {
            case "HEALTHY" -> "#10B981";
            case "WARNING" -> "#F59E0B";
            case "OFFLINE", "CONNECTING..." -> "#EF4444";
            default -> "#94A3B8";
        };
    }

    private String getStatusSecondaryColor(String status) {
        return switch (status) {
            case "HEALTHY" -> "#34D399";
            case "WARNING" -> "#FBBF24";
            case "OFFLINE", "CONNECTING..." -> "#F87171";
            default -> "#CBD5E1";
        };
    }
}
