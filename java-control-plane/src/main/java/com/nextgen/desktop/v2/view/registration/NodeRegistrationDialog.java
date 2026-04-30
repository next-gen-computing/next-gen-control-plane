package com.nextgen.desktop.v2.view.registration;

import com.nextgen.desktop.v2.service.RegistrationService;
import com.nextgen.desktop.v2.util.SystemSpecDetector;
import com.nextgen.desktop.v2.util.TlsCertificateGenerator;
import com.nextgen.desktop.v2.view.dashboard.NodeDashboard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Dialog for registering a new node.
 * Glassmorphism design with auto-detected system specs.
 */
public class NodeRegistrationDialog {
    private static final Logger LOG = LoggerFactory.getLogger(NodeRegistrationDialog.class);
    
    // Glassmorphism colors
    private static final String BG_PRIMARY = "#0a0e17";
    private static final String BG_CARD = "rgba(30, 41, 59, 0.7)";
    private static final String BORDER_GLASS = "rgba(255, 255, 255, 0.1)";
    private static final String ACCENT_GREEN = "#10b981";
    private static final String TEXT_PRIMARY = "#f8fafc";
    private static final String TEXT_SECONDARY = "#94a3b8";
    
    private final RegistrationService registrationService;
    private final Stage dialogStage;
    private final Consumer<String> onRegistrationSuccess;
    private TextField nameField;
    private TextField nodeIdField;
    private TextArea specArea;
    private Button generateCertButton;
    private Label certStatusLabel;
    private String generatedCertificate;
    
    public NodeRegistrationDialog(Stage parent, RegistrationService registrationService, Consumer<String> onRegistrationSuccess) {
        this.registrationService = registrationService;
        this.onRegistrationSuccess = onRegistrationSuccess;
        this.dialogStage = new Stage();
        this.dialogStage.initOwner(parent);
        this.dialogStage.initModality(Modality.APPLICATION_MODAL);
        this.dialogStage.setTitle("Register as Node");
    }
    
    public void show() {
        VBox root = createDialog();
        Scene scene = new Scene(root, 600, 650);
        scene.setFill(Color.TRANSPARENT);
        dialogStage.setScene(scene);
        dialogStage.showAndWait();
    }
    
    private VBox createDialog() {
        VBox root = new VBox(20);
        root.setStyle(String.format("-fx-background-color: %s; -fx-padding: 30px;", BG_PRIMARY));
        root.setAlignment(Pos.CENTER);
        
        // Title
        Label title = new Label("Register as Node");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        title.setTextFill(Color.web(TEXT_PRIMARY));
        
        // Form container
        VBox form = new VBox(15);
        form.setAlignment(Pos.CENTER_LEFT);
        form.setPadding(new Insets(20));
        form.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 16px; " +
            "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
            BG_CARD, BORDER_GLASS));
        
        // Node Name
        Label nameLabel = new Label("Node Name");
        nameLabel.setTextFill(Color.web(TEXT_SECONDARY));
        nameLabel.setFont(Font.font(14));
        
        nameField = new TextField(SystemSpecDetector.getSuggestedName());
        nameField.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + TEXT_PRIMARY + "; -fx-background-radius: 8px;");
        nameField.setPrefWidth(400);
        
        // Node ID
        Label nodeIdLabel = new Label("Node ID (auto-generated)");
        nodeIdLabel.setTextFill(Color.web(TEXT_SECONDARY));
        nodeIdLabel.setFont(Font.font(14));
        
        nodeIdField = new TextField(java.util.UUID.randomUUID().toString());
        nodeIdField.setEditable(false);
        nodeIdField.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-text-fill: " + TEXT_SECONDARY + "; -fx-background-radius: 8px;");
        nodeIdField.setPrefWidth(400);
        
        // System Specs (auto-detected)
        Label specLabel = new Label("System Specifications (auto-detected)");
        specLabel.setTextFill(Color.web(TEXT_SECONDARY));
        specLabel.setFont(Font.font(14));
        
        specArea = new TextArea();
        specArea.setEditable(false);
        specArea.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-text-fill: " + TEXT_SECONDARY + "; -fx-background-radius: 8px;");
        specArea.setPrefWidth(400);
        specArea.setPrefHeight(120);
        
        // Load specs
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        specArea.setText(String.format(
            "CPU Cores: %d\n" +
            "Memory: %.1f GB\n" +
            "Disk: %.1f GB\n" +
            "OS: %s\n" +
            "Hostname: %s\n" +
            "IP Address: %s",
            specs.get("cpuCores"),
            specs.get("memoryGb"),
            specs.get("diskGb"),
            specs.get("osInfo"),
            specs.get("hostname"),
            specs.get("ipAddress")
        ));
        
        // TLS Certificate
        Label certLabel = new Label("TLS Certificate");
        certLabel.setTextFill(Color.web(TEXT_SECONDARY));
        certLabel.setFont(Font.font(14));
        
        generateCertButton = new Button("Generate Certificate");
        generateCertButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-cursor: hand;",
            ACCENT_GREEN));
        generateCertButton.setOnAction(e -> generateCertificate());
        
        certStatusLabel = new Label("Not generated");
        certStatusLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        // Buttons
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        
        Button registerButton = new Button("Register Node");
        registerButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 30px; -fx-cursor: hand;",
            ACCENT_GREEN));
        registerButton.setPrefWidth(150);
        registerButton.setOnAction(e -> handleRegister());
        
        Button cancelButton = new Button("Cancel");
        cancelButton.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + TEXT_PRIMARY + "; -fx-background-radius: 8px; -fx-padding: 10px 30px; -fx-cursor: hand;");
        cancelButton.setPrefWidth(150);
        cancelButton.setOnAction(e -> dialogStage.close());
        
        buttonBox.getChildren().addAll(registerButton, cancelButton);
        
        // Add all to form
        form.getChildren().addAll(
            nameLabel, nameField,
            nodeIdLabel, nodeIdField,
            specLabel, specArea,
            certLabel, generateCertButton, certStatusLabel,
            buttonBox
        );
        
        root.getChildren().addAll(title, form);
        return root;
    }
    
    private void generateCertificate() {
        try {
            generatedCertificate = TlsCertificateGenerator.generateCertificate(
                nameField.getText(), "NextGenControlPlane");
            certStatusLabel.setText("✓ Generated");
            certStatusLabel.setTextFill(Color.web("#10b981"));
            LOG.info("Generated certificate for node: {}", nameField.getText());
        } catch (Exception e) {
            certStatusLabel.setText("✗ Failed");
            certStatusLabel.setTextFill(Color.web("#ef4444"));
            LOG.error("Failed to generate certificate", e);
        }
    }
    
    private void handleRegister() {
        if (generatedCertificate == null) {
            certStatusLabel.setText("✗ Required");
            certStatusLabel.setTextFill(Color.web("#ef4444"));
            return;
        }
        
        try {
            var node = registrationService.registerNode(nameField.getText());
            LOG.info("Node registered: {}", node.getId());
            
            new Alert(Alert.AlertType.INFORMATION, 
                "Node registered successfully!\n\n" +
                "Node ID: " + node.getId() + "\n" +
                "You can now join servers using the Server's Connection Token.").showAndWait();
            
            dialogStage.close();
            
            // Launch node dashboard
            if (onRegistrationSuccess != null) {
                onRegistrationSuccess.accept(node.getId());
            }
            
        } catch (Exception e) {
            LOG.error("Failed to register node", e);
            new Alert(Alert.AlertType.ERROR, "Registration failed: " + e.getMessage()).showAndWait();
        }
    }
}
