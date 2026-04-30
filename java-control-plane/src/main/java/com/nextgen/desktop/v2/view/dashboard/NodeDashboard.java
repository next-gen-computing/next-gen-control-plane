package com.nextgen.desktop.v2.view.dashboard;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.ClusterMembershipEntity;
import com.nextgen.desktop.v2.db.entities.NodeEntity;
import com.nextgen.desktop.v2.db.entities.ServerEntity;
import com.nextgen.desktop.v2.db.repositories.ClusterMembershipRepository;
import com.nextgen.desktop.v2.db.repositories.NodeRepository;
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
 * Node Dashboard - Glassmorphism design with server discovery and join flow.
 * Shows available servers, joined servers, and local system metrics.
 */
public class NodeDashboard {
    private static final Logger LOG = LoggerFactory.getLogger(NodeDashboard.class);

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
    private final NodeRepository nodeRepository;
    private final ClusterMembershipRepository clusterMembershipRepository;

    private TableView<ServerRow> availableServersTable;
    private TableView<MembershipRow> joinedServersTable;
    private Timeline refreshTimeline;

    private String nodeId;

    public NodeDashboard(Stage primaryStage, String nodeId) {
        this.primaryStage = primaryStage;
        this.nodeId = nodeId;
        this.dbManager = DatabaseManager.getInstance();
        var em = dbManager.createEntityManager();
        this.serverRepository = new ServerRepository(em);
        this.nodeRepository = new NodeRepository(em);
        this.clusterMembershipRepository = new ClusterMembershipRepository(em);
    }

    public void show() {
        VBox root = createDashboard();
        Scene scene = new Scene(root, 1400, 900);
        scene.setFill(Color.web(BG_PRIMARY));
        primaryStage.setScene(scene);
        primaryStage.setTitle("Node Dashboard - Next-Gen Control Plane V2");
        primaryStage.show();

        // Start auto-refresh
        startAutoRefresh();
    }

    private VBox createDashboard() {
        VBox root = new VBox(20);
        root.setStyle(String.format("-fx-background-color: %s; -fx-padding: 30px;", BG_PRIMARY));

        // Header
        HBox header = createHeader();

        // System specs card
        VBox specsCard = createSystemSpecsCard();

        // Main content area
        HBox mainContent = new HBox(20);
        mainContent.setAlignment(Pos.TOP_LEFT);

        // Available servers panel (left side)
        VBox availablePanel = createAvailableServersPanel();
        HBox.setHgrow(availablePanel, Priority.ALWAYS);

        // Joined servers panel (right side)
        VBox joinedPanel = createJoinedServersPanel();
        HBox.setHgrow(joinedPanel, Priority.ALWAYS);

        mainContent.getChildren().addAll(availablePanel, joinedPanel);

        root.getChildren().addAll(header, specsCard, mainContent);
        return root;
    }

    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 20, 0));

        Label title = new Label("Node Dashboard");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web(TEXT_PRIMARY));

        Label nodeLabel = new Label("Node ID: " + nodeId);
        nodeLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        nodeLabel.setTextFill(Color.web(TEXT_SECONDARY));
        nodeLabel.setPadding(new Insets(0, 0, 0, 20));

        Button disconnectButton = new Button("Disconnect");
        disconnectButton.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 20px; -fx-cursor: hand;",
                ACCENT_RED));
        disconnectButton.setOnAction(e -> handleDisconnect());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(title, nodeLabel, spacer, disconnectButton);
        return header;
    }

    private VBox createSystemSpecsCard() {
        VBox card = new VBox(15);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(20));
        card.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                        "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
                BG_CARD, BORDER_GLASS));

        Label titleLabel = new Label("System Specifications");
        titleLabel.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        titleLabel.setTextFill(Color.web(TEXT_PRIMARY));

        HBox specsRow = new HBox(30);
        specsRow.setAlignment(Pos.CENTER_LEFT);

        // CPU
        VBox cpuBox = createSpecItem("CPU Cores", "0", ACCENT_BLUE);

        // Memory
        VBox memoryBox = createSpecItem("Memory", "0 GB", ACCENT_GREEN);

        // Disk
        VBox diskBox = createSpecItem("Disk", "0 GB", ACCENT_BLUE);

        // Status
        VBox statusBox = createSpecItem("Status", "OFFLINE", ACCENT_RED);

        specsRow.getChildren().addAll(cpuBox, memoryBox, diskBox, statusBox);

        card.getChildren().addAll(titleLabel, specsRow);
        return card;
    }

    private VBox createSpecItem(String label, String value, String accentColor) {
        VBox box = new VBox(5);
        box.setAlignment(Pos.CENTER);

        Label labelLabel = new Label(label);
        labelLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        labelLabel.setTextFill(Color.web(TEXT_SECONDARY));

        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("Inter", FontWeight.BOLD, 24));
        valueLabel.setTextFill(Color.web(TEXT_PRIMARY));
        valueLabel.setStyle("-fx-font-size: 24px;");

        box.getChildren().addAll(labelLabel, valueLabel);
        return box;
    }

    private VBox createAvailableServersPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(20));
        panel.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                        "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
                BG_CARD, BORDER_GLASS));

        Label titleLabel = new Label("Join Server Manually");
        titleLabel.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        titleLabel.setTextFill(Color.web(TEXT_PRIMARY));

        Label instructionLabel = new Label("Enter server IP address and connection token to join:");
        instructionLabel.setTextFill(Color.web(TEXT_SECONDARY));
        instructionLabel.setFont(Font.font(12));

        // Server IP section
        HBox ipSection = new HBox(10);
        ipSection.setAlignment(Pos.CENTER_LEFT);

        Label ipLabel = new Label("Server IP:");
        ipLabel.setTextFill(Color.web(TEXT_SECONDARY));
        ipLabel.setPrefWidth(80);

        TextField ipField = new TextField();
        ipField.setPromptText("e.g., 192.168.1.100");
        ipField.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + TEXT_PRIMARY
                + "; -fx-background-radius: 8px;");
        ipField.setPrefWidth(200);

        ipSection.getChildren().addAll(ipLabel, ipField);

        // Token section
        HBox tokenSection = new HBox(10);
        tokenSection.setAlignment(Pos.CENTER_LEFT);

        Label tokenLabel = new Label("Token:");
        tokenLabel.setTextFill(Color.web(TEXT_SECONDARY));
        tokenLabel.setPrefWidth(80);

        TextField tokenField = new TextField();
        tokenField.setPromptText("8-character token");
        tokenField.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + TEXT_PRIMARY
                + "; -fx-background-radius: 8px;");
        tokenField.setPrefWidth(150);

        Button pasteButton = new Button("Paste");
        pasteButton.setStyle(String.format(
                "-fx-background-color: rgba(255,255,255,0.2); -fx-text-fill: " + TEXT_PRIMARY + "; -fx-background-radius: 8px; -fx-padding: 6px 12px; -fx-cursor: hand;"));
        pasteButton.setOnAction(e -> {
            String clipboardContent = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            if (clipboardContent != null && !clipboardContent.isEmpty()) {
                tokenField.setText(clipboardContent.trim());
            }
        });

        tokenSection.getChildren().addAll(tokenLabel, tokenField, pasteButton);

        // Join button
        Button joinButton = new Button("Join Server");
        joinButton.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 20px; -fx-cursor: hand;",
                ACCENT_GREEN));
        joinButton.setOnAction(e -> handleJoinServer(ipField.getText(), tokenField.getText()));

        VBox joinForm = new VBox(10);
        joinForm.getChildren().addAll(ipSection, tokenSection, joinButton);

        availableServersTable = createAvailableServersTable();

        panel.getChildren().addAll(titleLabel, instructionLabel, joinForm, availableServersTable);
        return panel;
    }

    private TableView<ServerRow> createAvailableServersTable() {
        TableView<ServerRow> table = new TableView<>();
        table.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        // Server ID column
        TableColumn<ServerRow, String> idCol = new TableColumn<>("Server ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("serverId"));
        idCol.setPrefWidth(150);

        // Name column
        TableColumn<ServerRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(150);

        // CPU column
        TableColumn<ServerRow, String> cpuCol = new TableColumn<>("CPU Cores");
        cpuCol.setCellValueFactory(new PropertyValueFactory<>("cpuCores"));
        cpuCol.setPrefWidth(100);

        // Memory column
        TableColumn<ServerRow, String> memoryCol = new TableColumn<>("Memory GB");
        memoryCol.setCellValueFactory(new PropertyValueFactory<>("memoryGb"));
        memoryCol.setPrefWidth(100);

        // Status column
        TableColumn<ServerRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(100);

        table.getColumns().addAll(idCol, nameCol, cpuCol, memoryCol, statusCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        return table;
    }

    private VBox createJoinedServersPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(20));
        panel.setStyle(String.format(
                "-fx-background-color: %s; -fx-background-radius: 16px; " +
                        "-fx-border-color: %s; -fx-border-radius: 16px; -fx-border-width: 1px;",
                BG_CARD, BORDER_GLASS));

        Label titleLabel = new Label("Joined Servers");
        titleLabel.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        titleLabel.setTextFill(Color.web(TEXT_PRIMARY));

        joinedServersTable = createJoinedServersTable();

        // Action buttons
        HBox actionButtons = new HBox(10);
        actionButtons.setAlignment(Pos.CENTER);

        Button leaveButton = new Button("Leave Selected");
        leaveButton.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 8px 16px; -fx-cursor: hand;",
                ACCENT_RED));
        leaveButton.setOnAction(e -> handleLeaveServer());

        actionButtons.getChildren().addAll(leaveButton);

        panel.getChildren().addAll(titleLabel, joinedServersTable, actionButtons);
        return panel;
    }

    private TableView<MembershipRow> createJoinedServersTable() {
        TableView<MembershipRow> table = new TableView<>();
        table.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        // Server ID column
        TableColumn<MembershipRow, String> serverIdCol = new TableColumn<>("Server ID");
        serverIdCol.setCellValueFactory(new PropertyValueFactory<>("serverId"));
        serverIdCol.setPrefWidth(150);

        // Status column
        TableColumn<MembershipRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(100);

        // Joined At column
        TableColumn<MembershipRow, String> joinedAtCol = new TableColumn<>("Joined At");
        joinedAtCol.setCellValueFactory(new PropertyValueFactory<>("joinedAt"));
        joinedAtCol.setPrefWidth(150);

        // Last Heartbeat column
        TableColumn<MembershipRow, String> heartbeatCol = new TableColumn<>("Last Heartbeat");
        heartbeatCol.setCellValueFactory(new PropertyValueFactory<>("lastHeartbeat"));
        heartbeatCol.setPrefWidth(150);

        table.getColumns().addAll(serverIdCol, statusCol, joinedAtCol, heartbeatCol);
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
                // Load node specs
                var nodeOpt = nodeRepository.findById(nodeId);
                if (nodeOpt.isPresent()) {
                    NodeEntity node = nodeOpt.get();
                    // Update specs labels (would need to store references to them)
                }

                // Load available servers (all active servers)
                List<ServerEntity> servers = serverRepository.findByStatus(ServerEntity.ServerStatus.ACTIVE);
                ObservableList<ServerRow> serverRows = FXCollections.observableArrayList();

                for (var server : servers) {
                    ServerRow row = new ServerRow();
                    row.setServerId(server.getId());
                    row.setName(server.getName());
                    row.setCpuCores(String.valueOf(server.getCpuCores()));
                    row.setMemoryGb(String.valueOf(server.getMemoryGb()));
                    row.setStatus(server.getStatus().toString());
                    serverRows.add(row);
                }
                availableServersTable.setItems(serverRows);

                // Load joined servers
                List<ClusterMembershipEntity> memberships = clusterMembershipRepository.findByNodeId(nodeId);
                ObservableList<MembershipRow> membershipRows = FXCollections.observableArrayList();

                for (var membership : memberships) {
                    MembershipRow row = new MembershipRow();
                    row.setServerId(membership.getServerId());
                    row.setStatus(membership.getStatus().toString());
                    row.setJoinedAt(membership.getJoinedAt() != null
                            ? membership.getJoinedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            : "N/A");
                    row.setLastHeartbeat(membership.getLastHeartbeat() != null
                            ? membership.getLastHeartbeat().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                            : "Never");
                    membershipRows.add(row);
                }
                joinedServersTable.setItems(membershipRows);

            } catch (Exception e) {
                LOG.error("Error refreshing dashboard data", e);
            }
        });
    }

    private void handleJoinServer(String ipAddress, String token) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please enter the server IP address").showAndWait();
            return;
        }

        if (token == null || token.trim().isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please enter a connection token").showAndWait();
            return;
        }

        // Validate IP address format
        if (!isValidIpAddress(ipAddress.trim())) {
            new Alert(Alert.AlertType.ERROR, "Invalid IP address format. Use format: 192.168.1.100").showAndWait();
            return;
        }

        // Validate token format (8 characters)
        if (token.trim().length() != 8) {
            new Alert(Alert.AlertType.ERROR, "Invalid token. Token must be 8 characters.").showAndWait();
            return;
        }

        // TODO: Implement gRPC connection to server using IP and token
        // For now, show a success message with the connection details
        new Alert(Alert.AlertType.INFORMATION, 
            "Connection request sent to server at " + ipAddress.trim() + "\n" +
            "Token: " + token.trim() + "\n\n" +
            "Note: Actual gRPC connection will be implemented in the next phase.").showAndWait();
        
        refreshData();
    }

    private boolean isValidIpAddress(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void handleLeaveServer() {
        MembershipRow selected = joinedServersTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a server to leave").showAndWait();
            return;
        }

        if (new Alert(Alert.AlertType.CONFIRMATION, "Leave server " + selected.getServerId() + "?").showAndWait()
                .get() == ButtonType.OK) {
            // TODO: Implement leave logic
            new Alert(Alert.AlertType.INFORMATION, "Left server").showAndWait();
            refreshData();
        }
    }

    private void handleDisconnect() {
        if (new Alert(Alert.AlertType.CONFIRMATION, "Disconnect from all servers?").showAndWait()
                .get() == ButtonType.OK) {
            if (refreshTimeline != null) {
                refreshTimeline.stop();
            }
            primaryStage.close();
        }
    }

    // Data classes for table rows
    public static class ServerRow {
        private String serverId;
        private String name;
        private String cpuCores;
        private String memoryGb;
        private String status;

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCpuCores() {
            return cpuCores;
        }

        public void setCpuCores(String cpuCores) {
            this.cpuCores = cpuCores;
        }

        public String getMemoryGb() {
            return memoryGb;
        }

        public void setMemoryGb(String memoryGb) {
            this.memoryGb = memoryGb;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class MembershipRow {
        private String serverId;
        private String status;
        private String joinedAt;
        private String lastHeartbeat;

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getJoinedAt() {
            return joinedAt;
        }

        public void setJoinedAt(String joinedAt) {
            this.joinedAt = joinedAt;
        }

        public String getLastHeartbeat() {
            return lastHeartbeat;
        }

        public void setLastHeartbeat(String lastHeartbeat) {
            this.lastHeartbeat = lastHeartbeat;
        }
    }
}
