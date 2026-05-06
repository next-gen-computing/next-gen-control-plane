package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.ThemeService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Settings screen for theme toggle, connection settings, and refresh interval.
 */
public class SettingsView {
    private final GrpcConnectionManager connectionManager;
    private final ThemeService themeService;
    private final NodeMonitoringService monitoringService;
    private final VBox root;

    public SettingsView(GrpcConnectionManager connectionManager, ThemeService themeService, NodeMonitoringService monitoringService) {
        this.connectionManager = connectionManager;
        this.themeService = themeService;
        this.monitoringService = monitoringService;
        this.root = createView();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createView() {
        VBox view = new VBox(24);
        view.setPadding(new Insets(10));
        view.getStyleClass().add("settings-view");

        // Title
        Label title = new Label("Settings");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web("#f8fafc"));

        view.getChildren().addAll(
                title,
                createAppearanceSection(),
                createConnectionSection(),
                createMonitoringSection()
        );
        return view;
    }

    private VBox createAppearanceSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(20));
        section.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12px;");

        Label sectionTitle = new Label("Appearance");
        sectionTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        sectionTitle.setTextFill(Color.web("#f8fafc"));

        HBox themeRow = new HBox(16);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        Label themeLabel = new Label("Theme:");
        themeLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        themeLabel.setTextFill(Color.web("#94a3b8"));
        themeLabel.setPrefWidth(120);

        ToggleButton themeToggle = new ToggleButton("Dark Mode");
        themeToggle.setSelected(themeService.isDarkMode());
        themeToggle.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 8px 20px; -fx-cursor: hand;",
                themeService.isDarkMode() ? "#3b82f6" : "#334155"));
        themeToggle.setOnAction(e -> {
            themeService.toggleTheme();
            themeToggle.setText(themeService.isDarkMode() ? "Dark Mode" : "Light Mode");
            themeToggle.setStyle(String.format(
                    "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 8px 20px; -fx-cursor: hand;",
                    themeService.isDarkMode() ? "#3b82f6" : "#334155"));
        });

        themeRow.getChildren().addAll(themeLabel, themeToggle);
        section.getChildren().addAll(sectionTitle, themeRow);
        return section;
    }

    private VBox createConnectionSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(20));
        section.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12px;");

        Label sectionTitle = new Label("Connection");
        sectionTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        sectionTitle.setTextFill(Color.web("#f8fafc"));

        // Host
        HBox hostRow = createSettingRow("Host:", connectionManager.getCurrentHost());
        TextField hostField = (TextField) hostRow.getChildren().get(1);

        // Control Plane Port
        HBox cpPortRow = createSettingRow("CP Port:", String.valueOf(connectionManager.getCurrentControlPlanePort()));
        TextField cpPortField = (TextField) cpPortRow.getChildren().get(1);

        // Predictor Port
        HBox predPortRow = createSettingRow("Predictor Port:", String.valueOf(connectionManager.getCurrentPredictorPort()));
        TextField predPortField = (TextField) predPortRow.getChildren().get(1);

        // Status
        HBox statusRow = new HBox(12);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Label statusLabel = new Label("Status:");
        statusLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        statusLabel.setTextFill(Color.web("#94a3b8"));
        statusLabel.setPrefWidth(120);

        Label statusValue = new Label(
                connectionManager.isControlPlaneConnected() ? "Connected" : "Disconnected");
        statusValue.setFont(Font.font("Inter", FontWeight.BOLD, 14));
        statusValue.setTextFill(connectionManager.isControlPlaneConnected()
                ? Color.web("#10b981") : Color.web("#ef4444"));

        statusRow.getChildren().addAll(statusLabel, statusValue);

        // Apply button
        Button applyButton = new Button("Apply & Connect");
        applyButton.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 24px; -fx-cursor: hand; -fx-font-weight: bold;");
        applyButton.setOnAction(e -> {
            try {
                String host = hostField.getText().trim();
                int cpPort = Integer.parseInt(cpPortField.getText().trim());
                int predPort = Integer.parseInt(predPortField.getText().trim());
                connectionManager.connectTo(host, cpPort, predPort);
                statusValue.setText("Connected");
                statusValue.setTextFill(Color.web("#10b981"));
                new Alert(Alert.AlertType.INFORMATION, "Connected to " + host + ":" + cpPort).showAndWait();
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Invalid port number").showAndWait();
            }
        });

        section.getChildren().addAll(sectionTitle, hostRow, cpPortRow, predPortRow, statusRow, applyButton);
        return section;
    }

    private VBox createMonitoringSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(20));
        section.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12px;");

        Label sectionTitle = new Label("Monitoring");
        sectionTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        sectionTitle.setTextFill(Color.web("#f8fafc"));

        HBox refreshRow = new HBox(16);
        refreshRow.setAlignment(Pos.CENTER_LEFT);

        Label refreshLabel = new Label("Refresh Interval:");
        refreshLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        refreshLabel.setTextFill(Color.web("#94a3b8"));
        refreshLabel.setPrefWidth(120);

        Slider refreshSlider = new Slider(1000, 10000, 2000);
        refreshSlider.setPrefWidth(250);
        refreshSlider.setShowTickLabels(true);
        refreshSlider.setShowTickMarks(true);
        refreshSlider.setMajorTickUnit(2000);
        refreshSlider.setBlockIncrement(1000);
        refreshSlider.setSnapToTicks(true);

        Label sliderValue = new Label("2000 ms");
        sliderValue.setFont(Font.font("Inter", FontWeight.BOLD, 14));
        sliderValue.setTextFill(Color.web("#f8fafc"));
        sliderValue.setPrefWidth(80);

        refreshSlider.valueProperty().addListener((obs, old, val) -> {
            long interval = Math.round(val.doubleValue());
            sliderValue.setText(interval + " ms");
        });

        refreshSlider.setOnMouseReleased(e -> {
            long interval = Math.round(refreshSlider.getValue());
            monitoringService.setRefreshInterval(interval);
        });

        refreshRow.getChildren().addAll(refreshLabel, refreshSlider, sliderValue);
        section.getChildren().addAll(sectionTitle, refreshRow);
        return section;
    }

    private HBox createSettingRow(String label, String value) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label(label);
        lbl.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        lbl.setTextFill(Color.web("#94a3b8"));
        lbl.setPrefWidth(120);

        TextField field = new TextField(value);
        field.setStyle("-fx-background-color: #334155; -fx-text-fill: #f8fafc; -fx-background-radius: 8px;");
        field.setPrefWidth(200);

        row.getChildren().addAll(lbl, field);
        return row;
    }
}
