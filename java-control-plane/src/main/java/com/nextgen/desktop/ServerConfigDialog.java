package com.nextgen.desktop;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Optional;

/**
 * Configuration dialog for Server Mode
 */
public class ServerConfigDialog {
    
    private static final String DARK_BG = "#0d1117";
    private static final String CARD_BG = "#161b22";
    private static final String BORDER_COLOR = "#30363d";
    private static final String ACCENT_BLUE = "#3b82f6";
    private static final String TEXT_PRIMARY = "#f0f6fc";
    private static final String TEXT_SECONDARY = "#7d8590";
    
    private Stage dialogStage;
    private ServerConfig result = null;
    
    public Optional<ServerConfig> showAndWait() {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.initStyle(StageStyle.UNDECORATED);
        dialogStage.setTitle("Server Configuration");
        
        VBox root = new VBox(24);
        root.setStyle(String.format(
            "-fx-background-color: %s; -fx-padding: 40px;", CARD_BG));
        root.setPrefWidth(500);
        
        // Header
        Text title = new Text("Configure ControlPlane Server");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 24));
        title.setFill(Color.web(TEXT_PRIMARY));
        
        Text subtitle = new Text("Set up your server parameters");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        subtitle.setFill(Color.web(TEXT_SECONDARY));
        
        // Form fields
        TextField predictorField = createTextField("localhost", "Predictor Host");
        TextField grpcPortField = createTextField("50051", "gRPC Port");
        TextField dashboardPortField = createTextField("8085", "Dashboard Port");
        TextField metricsPortField = createTextField("9090", "Metrics Port");
        
        // Checkboxes
        CheckBox enablePredictor = createCheckbox("Enable Predictor Service");
        CheckBox enableDashboard = createCheckbox("Enable Dashboard");
        CheckBox enableMetrics = createCheckbox("Enable Prometheus Metrics");
        enableDashboard.setSelected(true);
        enableMetrics.setSelected(true);
        
        // Buttons
        HBox buttonBox = new HBox(12);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setStyle(String.format(
            "-fx-background-color: transparent; -fx-text-fill: %s; " +
            "-fx-border-color: %s; -fx-border-radius: 8px; " +
            "-fx-padding: 12px 24px;",
            TEXT_SECONDARY, BORDER_COLOR));
        cancelBtn.setOnAction(e -> dialogStage.close());
        
        Button startBtn = new Button("Start Server");
        startBtn.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-background-radius: 8px; -fx-padding: 12px 24px; " +
            "-fx-font-weight: bold;",
            ACCENT_BLUE));
        startBtn.setDefaultButton(true);
        startBtn.setOnAction(e -> {
            result = new ServerConfig();
            result.setPredictorHost(predictorField.getText());
            result.setGrpcPort(parseInt(grpcPortField.getText(), 50051));
            result.setDashboardPort(parseInt(dashboardPortField.getText(), 8085));
            result.setMetricsPort(parseInt(metricsPortField.getText(), 9090));
            result.setEnablePredictor(enablePredictor.isSelected());
            result.setEnableDashboard(enableDashboard.isSelected());
            result.setEnableMetrics(enableMetrics.isSelected());
            dialogStage.close();
        });
        
        buttonBox.getChildren().addAll(cancelBtn, startBtn);
        
        root.getChildren().addAll(
            title, subtitle,
            createFormRow("Predictor Host:", predictorField),
            createFormRow("gRPC Port:", grpcPortField),
            createFormRow("Dashboard Port:", dashboardPortField),
            createFormRow("Metrics Port:", metricsPortField),
            new Region() {{ setPrefHeight(10); }},
            enablePredictor, enableDashboard, enableMetrics,
            new Region() {{ setPrefHeight(20); }},
            buttonBox
        );
        
        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
        dialogStage.showAndWait();
        
        return Optional.ofNullable(result);
    }
    
    private TextField createTextField(String defaultValue, String prompt) {
        TextField field = new TextField(defaultValue);
        field.setPromptText(prompt);
        field.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; " +
            "-fx-prompt-text-fill: %s; -fx-background-radius: 8px; " +
            "-fx-padding: 12px; -fx-border-color: %s; -fx-border-radius: 8px;",
            DARK_BG, TEXT_PRIMARY, TEXT_SECONDARY, BORDER_COLOR));
        return field;
    }
    
    private CheckBox createCheckbox(String text) {
        CheckBox cb = new CheckBox(text);
        cb.setStyle(String.format(
            "-fx-text-fill: %s; -fx-font-size: 14px;",
            TEXT_PRIMARY));
        return cb;
    }
    
    private HBox createFormRow(String label, TextField field) {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        
        Text labelText = new Text(label);
        labelText.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        labelText.setFill(Color.web(TEXT_SECONDARY));
        labelText.setWrappingWidth(140);
        
        HBox.setHgrow(field, Priority.ALWAYS);
        row.getChildren().addAll(labelText, field);
        
        return row;
    }
    
    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
