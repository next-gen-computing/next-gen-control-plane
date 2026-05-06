package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.model.NodeModel;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * Reusable card component for displaying a single node's real-time metrics.
 * Glassmorphism design with hover lift, pulsing status, and neon accents.
 */
public class NodeCard {
    private final VBox root;
    private final NodeModel node;

    public NodeCard(NodeModel node) {
        this.node = node;
        this.root = createCard();
        bindProperties();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(20));
        card.setPrefWidth(300);
        String statusColor = getStatusColorHex(node.getStatus());
        card.setStyle(String.format(
                "-fx-background-color: rgba(30, 41, 59, 0.45); -fx-background-radius: 16px; " +
                        "-fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 16px; -fx-border-width: 1px;"));

        // Top accent bar
        Region accent = new Region();
        accent.setPrefHeight(3);
        accent.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 2px;", statusColor));
        VBox.setMargin(accent, new Insets(-20, -20, 8, -20));

        // Header: Name + Status with pulsing dot
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Circle statusDot = new Circle(5);
        statusDot.setFill(Color.web(statusColor));

        Label nameLabel = new Label(node.getName());
        nameLabel.setFont(Font.font("Inter", FontWeight.BOLD, 15));
        nameLabel.setTextFill(Color.web("#F8FAFC"));

        Label statusLabel = new Label(node.getStatus());
        statusLabel.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 10));
        statusLabel.setTextFill(Color.web(statusColor));
        statusLabel.setStyle(String.format("-fx-background-color: %s22; -fx-background-radius: 6px; -fx-padding: 2px 8px;", statusColor));

        HBox statusBox = new HBox(6, statusDot, statusLabel);
        statusBox.setAlignment(Pos.CENTER_LEFT);

        header.getChildren().addAll(nameLabel, statusBox);
        HBox.setHgrow(nameLabel, javafx.scene.layout.Priority.ALWAYS);

        // Hostname
        Label hostnameLabel = new Label(node.getHostname() + "  ·  " + node.getIp() + ":" + node.getPort());
        hostnameLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        hostnameLabel.setTextFill(Color.web("#64748B"));

        // Metrics
        HBox cpuRow = createMetricRow("CPU", node.getCpuUsage(), "#3B82F6");
        HBox memoryRow = createMetricRow("Memory", node.getMemoryUsage(), "#10B981");

        // Footer info
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_LEFT);
        Label heartbeatLabel = new Label("HB: " + node.getLastHeartbeat());
        heartbeatLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 10));
        heartbeatLabel.setTextFill(Color.web("#64748B"));
        Label predLabel = new Label("Risk: " + node.getFailureProbability());
        predLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 10));
        predLabel.setTextFill(Color.web("#F59E0B"));
        footer.getChildren().addAll(heartbeatLabel, predLabel);

        card.getChildren().addAll(accent, header, hostnameLabel, cpuRow, memoryRow, footer);

        // Hover lift effect
        card.setOnMouseEntered(e -> {
            card.setStyle(String.format(
                    "-fx-background-color: rgba(30, 41, 59, 0.65); -fx-background-radius: 16px; " +
                            "-fx-border-color: %s33; -fx-border-radius: 16px; -fx-border-width: 1px;", statusColor));
            card.setEffect(new DropShadow(16, Color.web(statusColor + "33")));
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.02); st.setToY(1.02); st.play();
        });
        card.setOnMouseExited(e -> {
            card.setStyle(String.format(
                    "-fx-background-color: rgba(30, 41, 59, 0.45); -fx-background-radius: 16px; " +
                            "-fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 16px; -fx-border-width: 1px;"));
            card.setEffect(null);
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.0); st.setToY(1.0); st.play();
        });

        return card;
    }

    private HBox createMetricRow(String label, double value, String color) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(label);
        nameLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        nameLabel.setTextFill(Color.web("#94A3B8"));
        nameLabel.setPrefWidth(55);

        ProgressBar bar = new ProgressBar(value / 100.0);
        bar.setPrefWidth(140);
        bar.setStyle(String.format("-fx-accent: %s;", color));

        Label valueLabel = new Label(String.format("%.0f%%", value));
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 12));
        valueLabel.setTextFill(Color.web(color));
        valueLabel.setPrefWidth(45);
        valueLabel.setAlignment(Pos.CENTER_RIGHT);

        row.getChildren().addAll(nameLabel, bar, valueLabel);
        return row;
    }

    private void bindProperties() {
        node.statusProperty().addListener((obs, old, val) -> {
            javafx.application.Platform.runLater(() -> {
                String color = getStatusColorHex(val);
                for (javafx.scene.Node child : root.getChildren()) {
                    if (child instanceof HBox header) {
                        for (javafx.scene.Node hChild : header.getChildren()) {
                            if (hChild instanceof HBox statusBox) {
                                for (javafx.scene.Node sChild : statusBox.getChildren()) {
                                    if (sChild instanceof Circle dot) dot.setFill(Color.web(color));
                                    if (sChild instanceof Label lbl) {
                                        lbl.setText(val);
                                        lbl.setTextFill(Color.web(color));
                                        lbl.setStyle(String.format("-fx-background-color: %s22; -fx-background-radius: 6px; -fx-padding: 2px 8px;", color));
                                    }
                                }
                            }
                        }
                    }
                }
                root.setStyle(String.format(
                        "-fx-background-color: rgba(30, 41, 59, 0.45); -fx-background-radius: 16px; " +
                                "-fx-border-color: %s33; -fx-border-radius: 16px; -fx-border-width: 1px;", color));
            });
        });

        node.cpuUsageProperty().addListener((obs, old, val) ->
                updateProgressBar("CPU", val.doubleValue()));

        node.memoryUsageProperty().addListener((obs, old, val) ->
                updateProgressBar("Memory", val.doubleValue()));

        node.lastHeartbeatProperty().addListener((obs, old, val) ->
                updateLabel("HB: ", val));

        node.failureProbabilityProperty().addListener((obs, old, val) ->
                updateLabel("Risk: ", val));
    }

    private void updateProgressBar(String metricName, double value) {
        javafx.application.Platform.runLater(() -> {
            for (javafx.scene.Node child : root.getChildren()) {
                if (child instanceof HBox row) {
                    for (javafx.scene.Node rowChild : row.getChildren()) {
                        if (rowChild instanceof Label lbl && lbl.getText().equals(metricName)) {
                            for (javafx.scene.Node rChild : row.getChildren()) {
                                if (rChild instanceof ProgressBar bar) bar.setProgress(value / 100.0);
                                if (rChild instanceof Label valLbl && valLbl != lbl) {
                                    valLbl.setText(String.format("%.0f%%", value));
                                }
                            }
                            return;
                        }
                    }
                }
            }
        });
    }

    private void updateLabel(String prefix, String value) {
        javafx.application.Platform.runLater(() -> {
            for (javafx.scene.Node child : root.getChildren()) {
                if (child instanceof HBox footer) {
                    for (javafx.scene.Node fChild : footer.getChildren()) {
                        if (fChild instanceof Label lbl && lbl.getText().startsWith(prefix)) {
                            lbl.setText(prefix + value);
                        }
                    }
                }
            }
        });
    }

    private String getStatusColorHex(String status) {
        return switch (status) {
            case "HEALTHY" -> "#10B981";
            case "WARNING" -> "#F59E0B";
            case "OFFLINE", "CONNECTING..." -> "#EF4444";
            default -> "#94A3B8";
        };
    }

    private Color getStatusColor(String status) {
        return Color.web(getStatusColorHex(status));
    }
}
