package com.nextgen.desktop;

import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.agent.NodeAgent;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Next-Gen Control Plane Desktop Application
 * Professional Docker Desktop-style UI for managing servers and nodes
 */
public class DesktopApp extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopApp.class);
    
    private Stage primaryStage;
    private Scene modeSelectionScene;
    private Scene mainDashboardScene;
    private ProcessService processService;
    private boolean isServerMode = false;
    
    // Modern dark theme colors
    private static final String DARK_BG = "#0d1117";
    private static final String CARD_BG = "#161b22";
    private static final String BORDER_COLOR = "#30363d";
    private static final String ACCENT_BLUE = "#3b82f6";
    private static final String ACCENT_GREEN = "#22c55e";
    private static final String TEXT_PRIMARY = "#f0f6fc";
    private static final String TEXT_SECONDARY = "#7d8590";

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.processService = new ProcessService();
        
        primaryStage.setTitle("Next-Gen Control Plane");
        primaryStage.setMinWidth(1200);
        primaryStage.setMinHeight(800);
        
        // Create mode selection screen
        modeSelectionScene = createModeSelectionScene();
        
        primaryStage.setScene(modeSelectionScene);
        primaryStage.show();
        
        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down desktop app...");
            processService.stopAll();
        }));
    }
    
    private Scene createModeSelectionScene() {
        VBox root = new VBox(30);
        root.setStyle(String.format(
            "-fx-background-color: %s; -fx-padding: 60px;", DARK_BG));
        root.setAlignment(Pos.CENTER);
        
        // Title
        Text title = new Text("Next-Gen Control Plane");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 42));
        title.setFill(Color.web(TEXT_PRIMARY));
        
        Text subtitle = new Text("Choose how you want to run the application");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 16));
        subtitle.setFill(Color.web(TEXT_SECONDARY));
        
        // Mode cards container
        HBox cardsContainer = new HBox(40);
        cardsContainer.setAlignment(Pos.CENTER);
        
        // Server Mode Card
        VBox serverCard = createModeCard(
            "Server Mode",
            "Run as ControlPlane Server\n" +
            "• Accept node registrations\n" +
            "• Monitor cluster health\n" +
            "• Schedule tasks to nodes",
            "🖥️",
            ACCENT_BLUE,
            () -> startServerMode()
        );
        
        // Node Mode Card
        VBox nodeCard = createModeCard(
            "Node Mode",
            "Run as Node Agent\n" +
            "• Connect to ControlPlane\n" +
            "• Report CPU/Memory metrics\n" +
            "• Execute assigned tasks",
            "⚡",
            ACCENT_GREEN,
            () -> startNodeMode()
        );
        
        cardsContainer.getChildren().addAll(serverCard, nodeCard);
        
        root.getChildren().addAll(title, subtitle, cardsContainer);
        
        return new Scene(root, 1200, 800);
    }
    
    private VBox createModeCard(String title, String description, String icon, 
                                 String accentColor, Runnable onSelect) {
        VBox card = new VBox(20);
        card.setPrefWidth(400);
        card.setPrefHeight(350);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(40));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 16px; " +
            "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
            CARD_BG, BORDER_COLOR));
        
        // Hover effect
        card.setOnMouseEntered(e -> {
            card.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 2px; " +
                "-fx-cursor: hand;",
                CARD_BG, accentColor));
        });
        card.setOnMouseExited(e -> {
            card.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
                CARD_BG, BORDER_COLOR));
        });
        
        // Icon
        Text iconText = new Text(icon);
        iconText.setFont(Font.font(64));
        
        // Title
        Text titleText = new Text(title);
        titleText.setFont(Font.font("Inter", FontWeight.BOLD, 24));
        titleText.setFill(Color.web(TEXT_PRIMARY));
        
        // Description
        Text descText = new Text(description);
        descText.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        descText.setFill(Color.web(TEXT_SECONDARY));
        descText.setWrappingWidth(320);
        
        // Select button
        Button selectBtn = new Button("Select " + title.split(" ")[0]);
        selectBtn.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-background-radius: 8px; -fx-padding: 12px 32px; " +
            "-fx-font-size: 14px; -fx-font-weight: bold;",
            accentColor));
        selectBtn.setOnAction(e -> onSelect.run());
        
        card.setOnMouseClicked(e -> onSelect.run());
        card.getChildren().addAll(iconText, titleText, descText, selectBtn);
        
        // Add shadow effect
        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.web("#000000", 0.3));
        shadow.setRadius(20);
        shadow.setOffsetY(5);
        card.setEffect(shadow);
        
        return card;
    }
    
    private void startServerMode() {
        isServerMode = true;
        LOG.info("Starting in Server Mode...");
        
        // Show server configuration dialog
        ServerConfigDialog dialog = new ServerConfigDialog();
        dialog.showAndWait().ifPresent(config -> {
            // Switch to dashboard
            mainDashboardScene = createServerDashboardScene(config);
            primaryStage.setScene(mainDashboardScene);
            
            // Start server in background
            CompletableFuture.runAsync(() -> {
                try {
                    System.setProperty("PREDICTOR_HOST", config.getPredictorHost());
                    ControlPlaneServer.start();
                } catch (Exception e) {
                    LOG.error("Failed to start server", e);
                    Platform.runLater(() -> showError("Server Start Failed", e.getMessage()));
                }
            });
        });
    }
    
    private void startNodeMode() {
        isServerMode = false;
        LOG.info("Starting in Node Mode...");
        
        NodeConfigDialog dialog = new NodeConfigDialog();
        dialog.showAndWait().ifPresent(config -> {
            mainDashboardScene = createNodeDashboardScene(config);
            primaryStage.setScene(mainDashboardScene);
            
            CompletableFuture.runAsync(() -> {
                try {
                    System.setProperty("NODE_ID", config.getNodeId());
                    System.setProperty("CONTROL_PLANE_HOST", config.getServerHost());
                    NodeAgent.start();
                } catch (Exception e) {
                    LOG.error("Failed to start node", e);
                    Platform.runLater(() -> showError("Node Start Failed", e.getMessage()));
                }
            });
        });
    }
    
    private Scene createServerDashboardScene(ServerConfig config) {
        BorderPane root = new BorderPane();
        root.setStyle(String.format("-fx-background-color: %s;", DARK_BG));
        
        // Sidebar
        VBox sidebar = createSidebar("server");
        root.setLeft(sidebar);
        
        // Main content
        StackPane contentArea = new StackPane();
        contentArea.setStyle(String.format("-fx-background-color: %s;", DARK_BG));
        
        // Overview view (default)
        OverviewView overviewView = new OverviewView();
        contentArea.getChildren().add(overviewView);
        
        root.setCenter(contentArea);
        
        return new Scene(root, 1400, 900);
    }
    
    private Scene createNodeDashboardScene(NodeConfig config) {
        BorderPane root = new BorderPane();
        root.setStyle(String.format("-fx-background-color: %s;", DARK_BG));
        
        VBox sidebar = createSidebar("node");
        root.setLeft(sidebar);
        
        StackPane contentArea = new StackPane();
        contentArea.setStyle(String.format("-fx-background-color: %s;", DARK_BG));
        
        NodeStatusView nodeView = new NodeStatusView(config);
        contentArea.getChildren().add(nodeView);
        
        root.setCenter(contentArea);
        
        return new Scene(root, 1200, 800);
    }
    
    private VBox createSidebar(String mode) {
        VBox sidebar = new VBox(10);
        sidebar.setPrefWidth(260);
        sidebar.setStyle(String.format(
            "-fx-background-color: %s; -fx-padding: 24px 16px;", CARD_BG));
        
        // Logo/Title
        Text logo = new Text("Control Plane");
        logo.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        logo.setFill(Color.web(TEXT_PRIMARY));
        
        Text modeLabel = new Text(mode.toUpperCase() + " MODE");
        modeLabel.setFont(Font.font("Inter", FontWeight.BOLD, 10));
        modeLabel.setFill(Color.web(isServerMode ? ACCENT_BLUE : ACCENT_GREEN));
        
        sidebar.getChildren().addAll(logo, modeLabel, new Region() {{ setPrefHeight(30); }});
        
        // Navigation items
        String[] items = isServerMode 
            ? new String[]{"Overview", "Nodes", "Performance", "Tasks", "Settings"}
            : new String[]{"Status", "Metrics", "Logs", "Settings"};
        
        for (String item : items) {
            Button navBtn = createNavButton(item);
            sidebar.getChildren().add(navBtn);
        }
        
        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);
        
        // Back button
        Button backBtn = new Button("← Change Mode");
        backBtn.setStyle(String.format(
            "-fx-background-color: transparent; -fx-text-fill: %s; " +
            "-fx-font-size: 14px; -fx-cursor: hand;",
            TEXT_SECONDARY));
        backBtn.setOnAction(e -> {
            processService.stopAll();
            primaryStage.setScene(modeSelectionScene);
        });
        sidebar.getChildren().add(backBtn);
        
        return sidebar;
    }
    
    private Button createNavButton(String text) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setStyle(String.format(
            "-fx-background-color: transparent; -fx-text-fill: %s; " +
            "-fx-font-size: 14px; -fx-padding: 12px 16px; " +
            "-fx-background-radius: 8px;",
            TEXT_SECONDARY));
        btn.setOnMouseEntered(e -> btn.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; " +
            "-fx-font-size: 14px; -fx-padding: 12px 16px; " +
            "-fx-background-radius: 8px;",
            DARK_BG, TEXT_PRIMARY)));
        btn.setOnMouseExited(e -> btn.setStyle(String.format(
            "-fx-background-color: transparent; -fx-text-fill: %s; " +
            "-fx-font-size: 14px; -fx-padding: 12px 16px; " +
            "-fx-background-radius: 8px;",
            TEXT_SECONDARY)));
        return btn;
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    @Override
    public void stop() {
        processService.stopAll();
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
