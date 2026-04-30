package com.nextgen.desktop.v2.view.registration;

import com.nextgen.desktop.v2.service.RegistrationService;
import com.nextgen.desktop.v2.util.SystemSpecDetector;
import com.nextgen.desktop.v2.util.TlsCertificateGenerator;
import com.nextgen.desktop.v2.view.dashboard.ServerDashboard;
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
 * Dialog for registering a new server.
 * Glassmorphism design with auto-detected system specs.
 */
public class ServerRegistrationDialog {
    private static final Logger LOG = LoggerFactory.getLogger(ServerRegistrationDialog.class);
    
    // Glassmorphism colors
    private static final String BG_PRIMARY = "#0a0e17";
    private static final String BG_CARD = "rgba(30, 41, 59, 0.7)";
    private static final String BORDER_GLASS = "rgba(255, 255, 255, 0.1)";
    private static final String ACCENT_BLUE = "#3b82f6";
    private static final String TEXT_PRIMARY = "#f8fafc";
    private static final String TEXT_SECONDARY = "#94a3b8";
    
    private final RegistrationService registrationService;
    private final Stage dialogStage;
    private final Consumer<String> onRegistrationSuccess;
    private TextField nameField;
    private TextField serverIdField;
    private TextField grpcPortField;
    private TextArea specArea;
    private Button generateCertButton;
    private Label certStatusLabel;
    private String generatedCertificate;
    private String connectionToken;
    
    public ServerRegistrationDialog(Stage parent, RegistrationService registrationService, Consumer<String> onRegistrationSuccess) {
        this.registrationService = registrationService;
        this.onRegistrationSuccess = onRegistrationSuccess;
        this.dialogStage = new Stage();
        this.dialogStage.initOwner(parent);
        this.dialogStage.initModality(Modality.APPLICATION_MODAL);
        this.dialogStage.setTitle("Register as Server");
    }
    
    public void show() {
        VBox root = createDialog();
        Scene scene = new Scene(root, 600, 700);
        scene.setFill(Color.TRANSPARENT);
        dialogStage.setScene(scene);
        dialogStage.showAndWait();
    }
    
    private VBox createDialog() {
        VBox root = new VBox(20);
        root.setStyle(String.format("-fx-background-color: %s; -fx-padding: 30px;", BG_PRIMARY));
        root.setAlignment(Pos.CENTER);
        
        // Title
        Label title = new Label("Register as Server");
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
        
        // Server Name
        Label nameLabel = new Label("Server Name");
        nameLabel.setTextFill(Color.web(TEXT_SECONDARY));
        nameLabel.setFont(Font.font(14));
        
        nameField = new TextField(SystemSpecDetector.getSuggestedName() + "-server");
        nameField.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + TEXT_PRIMARY + "; -fx-background-radius: 8px;");
        nameField.setPrefWidth(400);
        
        // Server ID
        Label serverIdLabel = new Label("Server ID (auto-generated)");
        serverIdLabel.setTextFill(Color.web(TEXT_SECONDARY));
        serverIdLabel.setFont(Font.font(14));
        
        serverIdField = new TextField(java.util.UUID.randomUUID().toString());
        serverIdField.setEditable(false);
        serverIdField.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-text-fill: " + TEXT_SECONDARY + "; -fx-background-radius: 8px;");
        serverIdField.setPrefWidth(400);
        
        // gRPC Port
        Label portLabel = new Label("gRPC Port");
        portLabel.setTextFill(Color.web(TEXT_SECONDARY));
        portLabel.setFont(Font.font(14));
        
        grpcPortField = new TextField("50051");
        grpcPortField.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + TEXT_PRIMARY + "; -fx-background-radius: 8px;");
        grpcPortField.setPrefWidth(400);
        
        // System Specs (auto-detected)
        Label specLabel = new Label("System Specifications (auto-detected)");
        specLabel.setTextFill(Color.web(TEXT_SECONDARY));
        specLabel.setFont(Font.font(14));
        
        specArea = new TextArea();
        specArea.setEditable(false);
        specArea.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-text-fill: " + TEXT_SECONDARY + "; -fx-background-radius: 8px;");
        specArea.setPrefWidth(400);
        specArea.setPrefHeight(100);
        
        // Load specs
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        specArea.setText(String.format(
            "CPU Cores: %d\n" +
            "Memory: %.1f GB\n" +
            "Disk: %.1f GB\n" +
            "OS: %s\n" +
            "Hostname: %s",
            specs.get("cpuCores"),
            specs.get("memoryGb"),
            specs.get("diskGb"),
            specs.get("osInfo"),
            specs.get("hostname")
        ));
        
        // TLS Certificate
        Label certLabel = new Label("TLS Certificate");
        certLabel.setTextFill(Color.web(TEXT_SECONDARY));
        certLabel.setFont(Font.font(14));
        
        generateCertButton = new Button("Generate Certificate");
        generateCertButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-cursor: hand;",
            ACCENT_BLUE));
        generateCertButton.setOnAction(e -> generateCertificate());
        
        certStatusLabel = new Label("Not generated");
        certStatusLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        // Connection Token
        Label tokenLabel = new Label("Connection Token (for nodes to join)");
        tokenLabel.setTextFill(Color.web(TEXT_SECONDARY));
        tokenLabel.setFont(Font.font(14));
        
        TextField tokenField = new TextField("Generated on registration");
        tokenField.setEditable(false);
        tokenField.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-text-fill: " + TEXT_SECONDARY + "; -fx-background-radius: 8px;");
        tokenField.setPrefWidth(400);
        
        // Buttons
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        
        Button registerButton = new Button("Register Server");
        registerButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 30px; -fx-cursor: hand;",
            ACCENT_BLUE));
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
            serverIdLabel, serverIdField,
            portLabel, grpcPortField,
            specLabel, specArea,
            certLabel, generateCertButton, certStatusLabel,
            tokenLabel, tokenField,
            buttonBox
        );
        
        root.getChildren().addAll(title, form);
        return root;
    }
    
    private void generateCertificate() {
        try {
            generatedCertificate = TlsCertificateGenerator.generateCertificate(
                nameField.getText(), "NextGenControlPlane");
            connectionToken = TlsCertificateGenerator.generateConnectionToken();
            certStatusLabel.setText("✓ Generated");
            certStatusLabel.setTextFill(Color.web("#10b981"));
            LOG.info("Generated certificate for server: {}", nameField.getText());
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
            int port = Integer.parseInt(grpcPortField.getText());
            if (!SystemSpecDetector.isPortAvailable(port)) {
                new Alert(Alert.AlertType.ERROR, "Port " + port + " is already in use").showAndWait();
                return;
            }
            
            var server = registrationService.registerServer(nameField.getText(), port);
            LOG.info("Server registered: {}", server.getId());
            
            new Alert(Alert.AlertType.INFORMATION, 
                "Server registered successfully!\n\n" +
                "Server ID: " + server.getId() + "\n" +
                "Connection Token: " + server.getConnectionToken() + "\n\n" +
                "Share the Connection Token with nodes to join.").showAndWait();
            
            dialogStage.close();
            
            // Launch server dashboard
            if (onRegistrationSuccess != null) {
                onRegistrationSuccess.accept(server.getId());
            }
            
        } catch (NumberFormatException e) {
            new Alert(Alert.AlertType.ERROR, "Invalid port number").showAndWait();
        } catch (Exception e) {
            LOG.error("Failed to register server", e);
            new Alert(Alert.AlertType.ERROR, "Registration failed: " + e.getMessage()).showAndWait();
        }
    }
}
