package com.nextgen.desktop.v2.view.registration;

import com.nextgen.desktop.v2.service.RegistrationService;
import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.view.dashboard.ServerDashboard;
import com.nextgen.desktop.v2.view.dashboard.NodeDashboard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registration view for choosing between Server or Node registration.
 * Glassmorphism design with modern UI.
 */
public class RegistrationView {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationView.class);
    
    // Glassmorphism colors
    private static final String BG_PRIMARY = "#0a0e17";
    private static final String BG_CARD = "rgba(30, 41, 59, 0.7)";
    private static final String BORDER_GLASS = "rgba(255, 255, 255, 0.1)";
    private static final String ACCENT_BLUE = "#3b82f6";
    private static final String ACCENT_GREEN = "#10b981";
    private static final String TEXT_PRIMARY = "#f8fafc";
    private static final String TEXT_SECONDARY = "#94a3b8";
    
    private final Stage primaryStage;
    private final DatabaseManager dbManager;
    private final RegistrationService registrationService;
    
    public RegistrationView(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.dbManager = DatabaseManager.getInstance();
        this.registrationService = new RegistrationService(dbManager);
    }
    
    public void show() {
        VBox root = createView();
        Scene scene = new Scene(root, 1200, 800);
        scene.setFill(Color.web(BG_PRIMARY));
        primaryStage.setScene(scene);
        primaryStage.setTitle("Next-Gen Control Plane V2");
        primaryStage.show();
    }
    
    private VBox createView() {
        VBox root = new VBox(40);
        root.setStyle(String.format("-fx-background-color: %s; -fx-padding: 60px;", BG_PRIMARY));
        root.setAlignment(Pos.CENTER);
        
        // Title
        Label title = new Label("Next-Gen Control Plane V2");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 42));
        title.setTextFill(Color.web(TEXT_PRIMARY));
        
        Label subtitle = new Label("Register your system as a Server or Node");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 16));
        subtitle.setTextFill(Color.web(TEXT_SECONDARY));
        
        // Cards container
        HBox cardsContainer = new HBox(40);
        cardsContainer.setAlignment(Pos.CENTER);
        
        // Server Card
        VBox serverCard = createModeCard(
            "Register as Server",
            "Run as ControlPlane Server\n" +
            "• Accept node registrations\n" +
            "• Monitor cluster health\n" +
            "• Schedule tasks to nodes\n" +
            "• Generate connection tokens",
            "🖥️",
            ACCENT_BLUE,
            () -> {
                new ServerRegistrationDialog(primaryStage, registrationService, serverId -> {
                    new ServerDashboard(primaryStage, serverId).show();
                }).show();
            }
        );
        
        // Node Card
        VBox nodeCard = createModeCard(
            "Register as Node",
            "Run as Node Agent\n" +
            "• Connect to ControlPlane servers\n" +
            "• Report CPU/Memory metrics\n" +
            "• Execute assigned tasks\n" +
            "• Join multiple servers",
            "⚡",
            ACCENT_GREEN,
            () -> {
                new NodeRegistrationDialog(primaryStage, registrationService, nodeId -> {
                    new NodeDashboard(primaryStage, nodeId).show();
                }).show();
            }
        );
        
        cardsContainer.getChildren().addAll(serverCard, nodeCard);
        
        root.getChildren().addAll(title, subtitle, cardsContainer);
        return root;
    }
    
    private VBox createModeCard(String title, String description, String icon, 
                                 String accentColor, Runnable onSelect) {
        VBox card = new VBox(20);
        card.setPrefWidth(450);
        card.setPrefHeight(400);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(40));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 16px; " +
            "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
            BG_CARD, BORDER_GLASS));
        
        // Hover effect
        card.setOnMouseEntered(e -> {
            card.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 2px; " +
                "-fx-cursor: hand;",
                BG_CARD, accentColor));
        });
        card.setOnMouseExited(e -> {
            card.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
                BG_CARD, BORDER_GLASS));
        });
        
        // Icon
        Label iconLabel = new Label(icon);
        iconLabel.setFont(Font.font(64));
        iconLabel.setStyle("-fx-font-size: 64px;");
        
        // Title
        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("Inter", FontWeight.BOLD, 24));
        titleLabel.setTextFill(Color.web(TEXT_PRIMARY));
        
        // Description
        Label descLabel = new Label(description);
        descLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        descLabel.setTextFill(Color.web(TEXT_SECONDARY));
        descLabel.setWrapText(true);
        
        // Button
        Button button = new Button("Select");
        button.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-background-radius: 8px; -fx-padding: 10px 40px; -fx-cursor: hand;",
            accentColor));
        button.setOnAction(e -> onSelect.run());
        
        card.getChildren().addAll(iconLabel, titleLabel, descLabel, button);
        return card;
    }
}
