package com.nextgen.desktop.v2.view.dashboard;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.ClusterMembershipEntity;
import com.nextgen.desktop.v2.db.entities.JoinRequestEntity;
import com.nextgen.desktop.v2.db.entities.ServerEntity;
import com.nextgen.desktop.v2.db.repositories.ClusterMembershipRepository;
import com.nextgen.desktop.v2.db.repositories.JoinRequestRepository;
import com.nextgen.desktop.v2.db.repositories.ServerRepository;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Server Dashboard - Glassmorphism design with real-time node monitoring.
 * Shows connected nodes, their metrics, and pending join requests.
 */
public class ServerDashboard {
    private static final Logger LOG = LoggerFactory.getLogger(ServerDashboard.class);
    
    // Glassmorphism colors
    private static final String BG_PRIMARY = "#0a0e17";
    private static final String BG_CARD = "rgba(30, 41, 59, 0.7)";
    private static final String BORDER_GLASS = "rgba(255, 255, 255, 0.1)";
    private static final String ACCENT_BLUE = "#3b82f6";
    private static final String ACCENT_GREEN = "#10b981";
    private static final String ACCENT_RED = "#ef4444";
    private static final String TEXT_PRIMARY = "#f8fafc";
    private static final String TEXT_SECONDARY = "#94a3b8";
    
    private final Stage primaryStage;
    private final DatabaseManager dbManager;
    private final ServerRepository serverRepository;
    private final ClusterMembershipRepository clusterMembershipRepository;
    private final JoinRequestRepository joinRequestRepository;
    
    private TableView<NodeRow> nodesTable;
    private TableView<JoinRequestRow> requestsTable;
    private Timeline refreshTimeline;
    
    private String serverId;
    
    public ServerDashboard(Stage primaryStage, String serverId) {
        this.primaryStage = primaryStage;
        this.serverId = serverId;
        this.dbManager = DatabaseManager.getInstance();
        var em = dbManager.createEntityManager();
        this.serverRepository = new ServerRepository(em);
        this.clusterMembershipRepository = new ClusterMembershipRepository(em);
        this.joinRequestRepository = new JoinRequestRepository(em);
    }
    
    public void show() {
        VBox root = createDashboard();
        Scene scene = new Scene(root, 1400, 900);
        scene.setFill(Color.web(BG_PRIMARY));
        primaryStage.setScene(scene);
        primaryStage.setTitle("Server Dashboard - Next-Gen Control Plane V2");
        primaryStage.show();
        
        // Start auto-refresh
        startAutoRefresh();
    }
    
    private VBox createDashboard() {
        VBox root = new VBox(20);
        root.setStyle(String.format("-fx-background-color: %s; -fx-padding: 30px;", BG_PRIMARY));
        
        // Header
        HBox header = createHeader();
        
        // Stats cards
        HBox statsCards = createStatsCards();
        
        // Main content area
        HBox mainContent = new HBox(20);
        mainContent.setAlignment(Pos.TOP_LEFT);
        
        // Nodes table (left side - larger)
        VBox nodesPanel = createNodesPanel();
        HBox.setHgrow(nodesPanel, Priority.ALWAYS);
        
        // Join requests panel (right side)
        VBox requestsPanel = createRequestsPanel();
        requestsPanel.setPrefWidth(400);
        
        mainContent.getChildren().addAll(nodesPanel, requestsPanel);
        
        root.getChildren().addAll(header, statsCards, mainContent);
        return root;
    }
    
    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 20, 0));

        Label title = new Label("Server Dashboard");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web(TEXT_PRIMARY));

        Label serverLabel = new Label("Server ID: " + serverId);
        serverLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        serverLabel.setTextFill(Color.web(TEXT_SECONDARY));
        serverLabel.setPadding(new Insets(0, 0, 0, 20));

        // Connection Token display
        Button showTokenButton = new Button("Show Connection Token");
        showTokenButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 8px 16px; -fx-cursor: hand;",
            ACCENT_BLUE));
        showTokenButton.setOnAction(e -> showConnectionToken());

        Button stopButton = new Button("Stop Server");
        stopButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 20px; -fx-cursor: hand;",
            ACCENT_RED));
        stopButton.setOnAction(e -> handleStopServer());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(title, serverLabel, showTokenButton, spacer, stopButton);
        return header;
    }
    
    private HBox createStatsCards() {
        HBox cards = new HBox(20);
        cards.setAlignment(Pos.CENTER);
        
        // Connected Nodes card
        VBox connectedCard = createStatCard("Connected Nodes", "0", ACCENT_BLUE);
        
        // Pending Requests card
        VBox pendingCard = createStatCard("Pending Requests", "0", ACCENT_GREEN);
        
        // Total CPU card
        VBox cpuCard = createStatCard("Total CPU Cores", "0", ACCENT_BLUE);
        
        // Total Memory card
        VBox memoryCard = createStatCard("Total Memory", "0 GB", ACCENT_GREEN);
        
        cards.getChildren().addAll(connectedCard, pendingCard, cpuCard, memoryCard);
        return cards;
    }
    
    private VBox createStatCard(String title, String value, String accentColor) {
        VBox card = new VBox(10);
        card.setPrefSize(200, 100);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(20));
        card.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 16px; " +
            "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
            BG_CARD, BORDER_GLASS));
        
        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        titleLabel.setTextFill(Color.web(TEXT_SECONDARY));
        
        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        valueLabel.setTextFill(Color.web(TEXT_PRIMARY));
        valueLabel.setStyle("-fx-font-size: 28px;");
        
        card.getChildren().addAll(titleLabel, valueLabel);
        return card;
    }
    
    private VBox createNodesPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(20));
        panel.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 16px; " +
            "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
            BG_CARD, BORDER_GLASS));
        
        Label titleLabel = new Label("Connected Nodes");
        titleLabel.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        titleLabel.setTextFill(Color.web(TEXT_PRIMARY));
        
        nodesTable = createNodesTable();
        
        panel.getChildren().addAll(titleLabel, nodesTable);
        return panel;
    }
    
    private TableView<NodeRow> createNodesTable() {
        TableView<NodeRow> table = new TableView<>();
        table.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        
        // Node ID column
        TableColumn<NodeRow, String> idCol = new TableColumn<>("Node ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("nodeId"));
        idCol.setPrefWidth(150);
        
        // Name column
        TableColumn<NodeRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(150);
        
        // CPU column
        TableColumn<NodeRow, String> cpuCol = new TableColumn<>("CPU %");
        cpuCol.setCellValueFactory(new PropertyValueFactory<>("cpuUsage"));
        cpuCol.setPrefWidth(100);
        
        // Memory column
        TableColumn<NodeRow, String> memoryCol = new TableColumn<>("Memory MB");
        memoryCol.setCellValueFactory(new PropertyValueFactory<>("memoryUsage"));
        memoryCol.setPrefWidth(120);
        
        // Status column
        TableColumn<NodeRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(100);
        
        // Last Heartbeat column
        TableColumn<NodeRow, String> heartbeatCol = new TableColumn<>("Last Heartbeat");
        heartbeatCol.setCellValueFactory(new PropertyValueFactory<>("lastHeartbeat"));
        heartbeatCol.setPrefWidth(150);
        
        table.getColumns().addAll(idCol, nameCol, cpuCol, memoryCol, statusCol, heartbeatCol);
        
        // Style the table
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        
        return table;
    }
    
    private VBox createRequestsPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(20));
        panel.setStyle(String.format(
            "-fx-background-color: %s; -fx-background-radius: 16px; " +
            "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
            BG_CARD, BORDER_GLASS));
        
        Label titleLabel = new Label("Pending Join Requests");
        titleLabel.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        titleLabel.setTextFill(Color.web(TEXT_PRIMARY));
        
        requestsTable = createRequestsTable();
        
        // Action buttons
        HBox actionButtons = new HBox(10);
        actionButtons.setAlignment(Pos.CENTER);
        
        Button approveButton = new Button("Approve Selected");
        approveButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 8px 16px; -fx-cursor: hand;",
            ACCENT_GREEN));
        approveButton.setOnAction(e -> handleApproveRequest());
        
        Button rejectButton = new Button("Reject Selected");
        rejectButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 8px 16px; -fx-cursor: hand;",
            ACCENT_RED));
        rejectButton.setOnAction(e -> handleRejectRequest());
        
        actionButtons.getChildren().addAll(approveButton, rejectButton);
        
        panel.getChildren().addAll(titleLabel, requestsTable, actionButtons);
        return panel;
    }
    
    private TableView<JoinRequestRow> createRequestsTable() {
        TableView<JoinRequestRow> table = new TableView<>();
        table.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        table.setPrefHeight(300);
        
        // Node ID column
        TableColumn<JoinRequestRow, String> nodeIdCol = new TableColumn<>("Node ID");
        nodeIdCol.setCellValueFactory(new PropertyValueFactory<>("nodeId"));
        nodeIdCol.setPrefWidth(120);
        
        // Message column
        TableColumn<JoinRequestRow, String> messageCol = new TableColumn<>("Message");
        messageCol.setCellValueFactory(new PropertyValueFactory<>("message"));
        messageCol.setPrefWidth(150);
        
        // Requested At column
        TableColumn<JoinRequestRow, String> requestedAtCol = new TableColumn<>("Requested");
        requestedAtCol.setCellValueFactory(new PropertyValueFactory<>("requestedAt"));
        requestedAtCol.setPrefWidth(100);
        
        table.getColumns().addAll(nodeIdCol, messageCol, requestedAtCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        
        return table;
    }
    
    private void startAutoRefresh() {
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(5), e -> refreshData()));
        refreshTimeline.setCycleCount(Animation.INDEFINITE);
        refreshTimeline.play();
        
        // Initial refresh
        refreshData();
    }
    
    private void refreshData() {
        Platform.runLater(() -> {
            try {
                // Load connected nodes
                List<ClusterMembershipEntity> memberships = clusterMembershipRepository.findByServerId(serverId);
                ObservableList<NodeRow> nodeRows = FXCollections.observableArrayList();
                
                for (var membership : memberships) {
                    NodeRow row = new NodeRow();
                    row.setNodeId(membership.getNodeId());
                    row.setName("Node-" + membership.getNodeId().substring(0, 8));
                    row.setCpuUsage(String.format("%.1f", membership.getCpuUsagePercent() != null ? membership.getCpuUsagePercent() : 0));
                    row.setMemoryUsage(String.format("%.1f", membership.getMemoryUsageMb() != null ? membership.getMemoryUsageMb() : 0));
                    row.setStatus(membership.getStatus().toString());
                    row.setLastHeartbeat(membership.getLastHeartbeat() != null ? 
                            membership.getLastHeartbeat().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "Never");
                    nodeRows.add(row);
                }
                nodesTable.setItems(nodeRows);
                
                // Load pending join requests
                List<JoinRequestEntity> requests = joinRequestRepository.findPendingByServerId(serverId);
                ObservableList<JoinRequestRow> requestRows = FXCollections.observableArrayList();
                
                for (var request : requests) {
                    JoinRequestRow row = new JoinRequestRow();
                    row.setNodeId(request.getNodeId());
                    row.setMessage(request.getMessage() != null ? request.getMessage() : "");
                    row.setRequestedAt(request.getRequestedAt().format(DateTimeFormatter.ofPattern("HH:mm")));
                    requestRows.add(row);
                }
                requestsTable.setItems(requestRows);
                
            } catch (Exception e) {
                LOG.error("Error refreshing dashboard data", e);
            }
        });
    }
    
    private void handleApproveRequest() {
        JoinRequestRow selected = requestsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a request to approve").showAndWait();
            return;
        }
        
        // TODO: Implement approval logic
        new Alert(Alert.AlertType.INFORMATION, "Request from " + selected.getNodeId() + " approved").showAndWait();
        refreshData();
    }
    
    private void handleRejectRequest() {
        JoinRequestRow selected = requestsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a request to reject").showAndWait();
            return;
        }
        
        // TODO: Implement rejection logic
        new Alert(Alert.AlertType.INFORMATION, "Request from " + selected.getNodeId() + " rejected").showAndWait();
        refreshData();
    }
    
    private void handleStopServer() {
        if (new Alert(Alert.AlertType.CONFIRMATION, "Stop the server?").showAndWait().get() == ButtonType.OK) {
            if (refreshTimeline != null) {
                refreshTimeline.stop();
            }
            primaryStage.close();
        }
    }

    private void showConnectionToken() {
        try {
            var server = serverRepository.findById(serverId);
            if (server.isPresent()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Connection Token");
                alert.setHeaderText("Server Connection Token");
                
                TextArea tokenArea = new TextArea(server.get().getConnectionToken());
                tokenArea.setEditable(false);
                tokenArea.setWrapText(true);
                tokenArea.setPrefRowCount(3);
                tokenArea.setPrefColumnCount(50);
                
                VBox content = new VBox(10);
                content.getChildren().addAll(
                    new Label("Share this token with nodes to allow them to join your server:"),
                    tokenArea
                );
                
                alert.getDialogPane().setContent(content);
                alert.showAndWait();
            } else {
                new Alert(Alert.AlertType.ERROR, "Server not found").showAndWait();
            }
        } catch (Exception e) {
            LOG.error("Error retrieving connection token", e);
            new Alert(Alert.AlertType.ERROR, "Failed to retrieve connection token: " + e.getMessage()).showAndWait();
        }
    }
    
    // Data classes for table rows
    public static class NodeRow {
        private String nodeId;
        private String name;
        private String cpuUsage;
        private String memoryUsage;
        private String status;
        private String lastHeartbeat;
        
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getCpuUsage() { return cpuUsage; }
        public void setCpuUsage(String cpuUsage) { this.cpuUsage = cpuUsage; }
        
        public String getMemoryUsage() { return memoryUsage; }
        public void setMemoryUsage(String memoryUsage) { this.memoryUsage = memoryUsage; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(String lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    }
    
    public static class JoinRequestRow {
        private String nodeId;
        private String message;
        private String requestedAt;
        
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public String getRequestedAt() { return requestedAt; }
        public void setRequestedAt(String requestedAt) { this.requestedAt = requestedAt; }
    }
}
