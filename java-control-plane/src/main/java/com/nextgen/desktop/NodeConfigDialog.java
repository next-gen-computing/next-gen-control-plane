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
 * Configuration dialog for Node Agent Mode
 */
public class NodeConfigDialog {
    
    private static final String DARK_BG = "#0d1117";
    private static final String CARD_BG = "#161b22";
    private static final String BORDER_COLOR = "#30363d";
    private static final String ACCENT_GREEN = "#22c55e";
    private static final String TEXT_PRIMARY = "#f0f6fc";
    private static final String TEXT_SECONDARY = "#7d8590";
    
    private Stage dialogStage;
    private NodeConfig result = null;
    
    public Optional<NodeConfig> showAndWait() {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.initStyle(StageStyle.UNDECORATED);
        dialogStage.setTitle("Node Configuration");
        
        VBox root = new VBox(24);
        root.setStyle(String.format(
            "-fx-background-color: %s; -fx-padding: 40px;", CARD_BG));
        root.setPrefWidth(500);
        
        // Header
        Text title = new Text("Configure Node Agent");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 24));
        title.setFill(Color.web(TEXT_PRIMARY));
        
        Text subtitle = new Text("Connect to a ControlPlane Server");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        subtitle.setFill(Color.web(TEXT_SECONDARY));
        
        // Form fields
        String defaultNodeId = "node-" + System.currentTimeMillis() % 10000;
        TextField nodeIdField = createTextField(defaultNodeId, "Node ID");
        TextField serverHostField = createTextField("localhost", "Server Host");
        TextField serverPortField = createTextField("50051", "Server Port");
        TextField metricsPortField = createTextField("9090", "Metrics Port");
        TextField heartbeatField = createTextField("2", "Heartbeat (seconds)");
        
        // Checkboxes
        CheckBox autoReconnect = createCheckbox("Auto-reconnect on failure");
        autoReconnect.setSelected(true);
        
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
        
        Button startBtn = new Button("Connect to Server");
        startBtn.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-background-radius: 8px; -fx-padding: 12px 24px; " +
            "-fx-font-weight: bold;",
            ACCENT_GREEN));
        startBtn.setDefaultButton(true);
        startBtn.setOnAction(e -> {
            result = new NodeConfig();
            result.setNodeId(nodeIdField.getText());
            result.setServerHost(serverHostField.getText());
            result.setServerPort(parseInt(serverPortField.getText(), 50051));
            result.setMetricsPort(parseInt(metricsPortField.getText(), 9090));
            result.setHeartbeatInterval(parseInt(heartbeatField.getText(), 2));
            result.setAutoReconnect(autoReconnect.isSelected());
            dialogStage.close();
        });
        
        buttonBox.getChildren().addAll(cancelBtn, startBtn);
        
        root.getChildren().addAll(
            title, subtitle,
            createFormRow("Node ID:", nodeIdField),
            createFormRow("Server Host:", serverHostField),
            createFormRow("Server Port:", serverPortField),
            createFormRow("Metrics Port:", metricsPortField),
            createFormRow("Heartbeat (s):", heartbeatField),
            new Region() {{ setPrefHeight(10); }},
            autoReconnect,
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
