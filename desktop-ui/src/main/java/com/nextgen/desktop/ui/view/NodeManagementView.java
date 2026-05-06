package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.net.InetAddress;

/**
 * Node management screen for adding, viewing, and connecting to nodes.
 */
public class NodeManagementView {
    private final GrpcConnectionManager connectionManager;
    private final NodeMonitoringService monitoringService;
    private final VBox root;
    private final TableView<NodeModel> nodeTable;
    private Label statusLabel;

    public NodeManagementView(GrpcConnectionManager connectionManager, NodeMonitoringService monitoringService) {
        this.connectionManager = connectionManager;
        this.monitoringService = monitoringService;
        this.root = createView();
        this.nodeTable = (TableView<NodeModel>) root.lookup("#nodeTable");
        bindData();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createView() {
        VBox view = new VBox(24);
        view.setPadding(new Insets(24));
        view.getStyleClass().add("node-management-view");

        // Title
        Label title = new Label("Node Management");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 28));
        title.setTextFill(Color.web("#F8FAFC"));

        // Connection status bar
        HBox statusBar = createStatusBar();

        // Server Config (glassmorphism panel)
        VBox configPanel = createGlassPanel("Server Connection", createConnectionConfigForm());

        // Join Server (glassmorphism panel)
        VBox joinPanel = createGlassPanel("Join Server as Node", createJoinForm());

        // Node table section
        Label tableTitle = new Label("Connected Nodes");
        tableTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        tableTitle.setTextFill(Color.web("#F8FAFC"));

        TableView<NodeModel> table = createNodeTable();
        table.setId("nodeTable");

        view.getChildren().addAll(title, statusBar, configPanel, joinPanel, tableTitle, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return view;
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 16, 12, 16));
        bar.setStyle("-fx-background-color: rgba(30, 41, 59, 0.6); -fx-background-radius: 12px; -fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 12px; -fx-border-width: 1px;");

        Circle dot = new Circle(6);
        dot.setFill(Color.web("#EF4444"));

        Label status = new Label("Disconnected");
        status.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 13));
        status.setTextFill(Color.web("#EF4444"));

        statusLabel = status;

        // Pulsing animation for the dot
        ScaleTransition pulse = new ScaleTransition(Duration.seconds(1.5), dot);
        pulse.setFromX(1); pulse.setFromY(1);
        pulse.setToX(1.3); pulse.setToY(1.3);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(ScaleTransition.INDEFINITE);
        pulse.play();

        Label hint = new Label("Enter the server's LAN IP (e.g., 192.168.1.100) shown in server logs");
        hint.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        hint.setTextFill(Color.web("#64748B"));
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setAlignment(Pos.CENTER_RIGHT);

        bar.getChildren().addAll(dot, status, hint);
        return bar;
    }

    private VBox createGlassPanel(String titleText, javafx.scene.Node content) {
        VBox panel = new VBox(16);
        panel.setPadding(new Insets(20));
        panel.setStyle("-fx-background-color: rgba(30, 41, 59, 0.45); -fx-background-radius: 16px; -fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 16px; -fx-border-width: 1px;");

        Label title = new Label(titleText);
        title.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        title.setTextFill(Color.web("#F8FAFC"));

        panel.getChildren().addAll(title, content);
        return panel;
    }

    private GridPane createConnectionConfigForm() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);

        TextField hostField = new TextField(connectionManager.getCurrentHost());
        hostField.setPromptText("Server IP (e.g., 192.168.1.100)");
        hostField.setPrefWidth(200);

        TextField cpPortField = new TextField(String.valueOf(connectionManager.getCurrentControlPlanePort()));
        cpPortField.setPromptText("Port");
        cpPortField.setPrefWidth(90);

        Button connectBtn = new Button("Connect");
        connectBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-background-radius: 10px; -fx-padding: 10px 24px; -fx-font-weight: 600; -fx-cursor: hand;");
        connectBtn.setOnAction(e -> {
            try {
                String host = hostField.getText().trim();
                int cpPort = Integer.parseInt(cpPortField.getText().trim());
                connectionManager.connectTo(host, cpPort, connectionManager.getCurrentPredictorPort());
                updateStatus(true, "Connected to " + host + ":" + cpPort);
            } catch (NumberFormatException ex) {
                updateStatus(false, "Invalid port number");
            }
        });

        grid.add(new Label("Host:"), 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(new Label("Port:"), 2, 0);
        grid.add(cpPortField, 3, 0);
        grid.add(connectBtn, 4, 0);

        // Style labels
        grid.getChildren().stream().filter(n -> n instanceof Label).forEach(n -> ((Label)n).setTextFill(Color.web("#94A3B8")));

        return grid;
    }

    private GridPane createJoinForm() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);

        TextField nameField = new TextField();
        nameField.setPromptText("Node Name");
        nameField.setPrefWidth(160);

        TextField ipField = new TextField();
        ipField.setPromptText("Server IP");
        ipField.setPrefWidth(160);

        TextField tokenField = new TextField();
        tokenField.setPromptText("Connection Token");
        tokenField.setPrefWidth(140);

        Button joinBtn = new Button("Join Server");
        joinBtn.setStyle("-fx-background-color: #3B82F6; -fx-text-fill: white; -fx-background-radius: 10px; -fx-padding: 10px 24px; -fx-font-weight: 600; -fx-cursor: hand;");
        joinBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String ip = ipField.getText().trim();
            String token = tokenField.getText().trim();
            if (name.isEmpty() || ip.isEmpty()) {
                updateStatus(false, "Name and Server IP are required");
                return;
            }
            // Connect first, then register
            connectionManager.connectTo(ip, 50051, 50052);
            ControlPlaneClient client = connectionManager.getControlPlaneClient();
            try {
                String myIp = InetAddress.getLocalHost().getHostAddress();
                String myHostname = InetAddress.getLocalHost().getHostName();
                var response = client.registerNode(name, myIp, 50051, myHostname);
                if (response.getStatus().contains("ERROR")) {
                    updateStatus(false, "Join failed: " + response.getStatus());
                } else {
                    updateStatus(true, "Joined as " + response.getAssignedId());
                    NodeModel node = new NodeModel();
                    node.setId(response.getAssignedId());
                    node.setName(name);
                    node.setIp(myIp);
                    node.setPort(50051);
                    node.setHostname(myHostname);
                    node.setStatus("HEALTHY");
                    monitoringService.getNodes().add(node);
                }
            } catch (Exception ex) {
                updateStatus(false, "Join failed: " + ex.getMessage());
            }
        });

        grid.add(new Label("Node Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Server IP:"), 2, 0);
        grid.add(ipField, 3, 0);
        grid.add(new Label("Token:"), 0, 1);
        grid.add(tokenField, 1, 1);
        grid.add(joinBtn, 3, 1);

        grid.getChildren().stream().filter(n -> n instanceof Label).forEach(n -> ((Label)n).setTextFill(Color.web("#94A3B8")));

        return grid;
    }

    private void updateStatus(boolean connected, String message) {
        statusLabel.setText(message);
        statusLabel.setTextFill(Color.web(connected ? "#10B981" : "#EF4444"));
        FadeTransition ft = new FadeTransition(Duration.millis(300), statusLabel);
        ft.setFromValue(0.5); ft.setToValue(1.0);
        ft.play();
    }

    private TableView<NodeModel> createNodeTable() {
        TableView<NodeModel> table = new TableView<>();
        table.setStyle("-fx-background-color: transparent;");

        TableColumn<NodeModel, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cd -> cd.getValue().nameProperty());
        nameCol.setPrefWidth(150);

        TableColumn<NodeModel, String> hostCol = new TableColumn<>("Hostname");
        hostCol.setCellValueFactory(cd -> cd.getValue().hostnameProperty());
        hostCol.setPrefWidth(150);

        TableColumn<NodeModel, String> ipCol = new TableColumn<>("IP:Port");
        ipCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                cd.getValue().getIp() + ":" + cd.getValue().getPort()));
        ipCol.setPrefWidth(120);

        TableColumn<NodeModel, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cd -> cd.getValue().statusProperty());
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    HBox box = new HBox(6);
                    box.setAlignment(Pos.CENTER_LEFT);
                    Circle dot = new Circle(5);
                    dot.setFill(getStatusColor(item));
                    Label lbl = new Label(item);
                    lbl.setTextFill(getStatusColor(item));
                    box.getChildren().addAll(dot, lbl);
                    setGraphic(box);
                    setText(null);
                }
            }
        });

        TableColumn<NodeModel, Number> cpuCol = new TableColumn<>("CPU %");
        cpuCol.setCellValueFactory(cd -> cd.getValue().cpuUsageProperty());
        cpuCol.setPrefWidth(100);

        TableColumn<NodeModel, Number> memCol = new TableColumn<>("Memory %");
        memCol.setCellValueFactory(cd -> cd.getValue().memoryUsageProperty());
        memCol.setPrefWidth(100);

        TableColumn<NodeModel, String> heartbeatCol = new TableColumn<>("Last Heartbeat");
        heartbeatCol.setCellValueFactory(cd -> cd.getValue().lastHeartbeatProperty());
        heartbeatCol.setPrefWidth(120);

        table.getColumns().addAll(nameCol, hostCol, ipCol, statusCol, cpuCol, memCol, heartbeatCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private void bindData() {
        nodeTable.setItems(monitoringService.getNodes());
    }

    private Color getStatusColor(String status) {
        return switch (status) {
            case "HEALTHY" -> Color.web("#10b981");
            case "WARNING" -> Color.web("#f59e0b");
            case "OFFLINE", "CONNECTING..." -> Color.web("#ef4444");
            default -> Color.web("#94a3b8");
        };
    }
}
