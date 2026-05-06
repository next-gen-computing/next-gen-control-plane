package com.nextgen.desktop.ui;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.service.ThemeService;
import com.nextgen.desktop.ui.util.ServerIdCodec;
import com.nextgen.desktop.ui.view.MainWindow;
import com.nextgen.desktop.ui.view.NodeJoinView;
import com.nextgen.desktop.ui.view.RoleSelectionView;
import com.nextgen.desktop.ui.view.ServerSetupView;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Next-Gen Control Plane Phase-2 Desktop Application.
 * 
 * Flow: Role Selection → Server Setup / Node Join → Main Dashboard
 * 
 * State Machine:
 *   ROLE_SELECTION → SERVER_SETUP or NODE_JOIN → MAIN_DASHBOARD
 */
public class DesktopApp extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopApp.class);

    private GrpcConnectionManager connectionManager;
    private ThemeService themeService;
    private Stage primaryStage;
    private StackPane rootContainer;
    private Scene scene;

    // Track the chosen role and server info
    private String chosenRole;
    private String serverId;

    @Override
    public void init() throws Exception {
        super.init();
        connectionManager = new GrpcConnectionManager();
        themeService = new ThemeService();
        LOG.info("Desktop UI initialized");
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            this.primaryStage = primaryStage;
            primaryStage.setTitle("Next-Gen Control Plane");
            primaryStage.setMinWidth(1280);
            primaryStage.setMinHeight(800);
            primaryStage.setMaximized(true);

            // Root container for view transitions
            rootContainer = new StackPane();
            rootContainer.setStyle("-fx-background-color: #0B0F19;");

            scene = new Scene(rootContainer, 1400, 900);
            themeService.registerScene(scene);
            primaryStage.setScene(scene);

            // Start with Role Selection
            showRoleSelection();

            primaryStage.show();

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down desktop UI...");
                if (connectionManager != null) {
                    connectionManager.shutdown();
                }
            }));
        } catch (Exception e) {
            LOG.error("Failed to start desktop UI", e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────
    //  SCREEN TRANSITIONS
    // ─────────────────────────────────────────────

    private void showRoleSelection() {
        LOG.info("Showing Role Selection screen");

        RoleSelectionView roleView = new RoleSelectionView(role -> {
            chosenRole = role;
            if ("server".equals(role)) {
                showServerSetup();
            } else {
                showNodeJoin();
            }
        });

        transitionTo(roleView.getRoot());
    }

    private void showServerSetup() {
        LOG.info("Showing Server Setup screen");

        ServerSetupView serverView = new ServerSetupView(
                // On launch server
                generatedServerId -> {
                    this.serverId = generatedServerId;
                    LOG.info("Server ID generated: {}", serverId);

                    // Start the gRPC server in background
                    startGrpcServerAndShowDashboard();
                },
                // On back
                this::showRoleSelection
        );

        transitionTo(serverView.getRoot());
    }

    private void showNodeJoin() {
        LOG.info("Showing Node Join screen");

        NodeJoinView nodeView = new NodeJoinView(
                // On connect
                (nodeServerId, address) -> {
                    this.serverId = nodeServerId;
                    LOG.info("Connecting to server {} at {}", nodeServerId, address);

                    // Connect to the server
                    connectionManager.connectTo(
                            address.getIp(),
                            address.getPort(),
                            connectionManager.getCurrentPredictorPort()
                    );

                    // Register this node with the server
                    registerNodeAndShowDashboard(address);
                },
                // On back
                this::showRoleSelection
        );

        transitionTo(nodeView.getRoot());
    }

    private void startGrpcServerAndShowDashboard() {
        // Start the ControlPlane gRPC server in a background daemon thread.
        // We use reflection to call ControlPlaneServer.start() from the java-control-plane module
        // so that desktop-ui doesn't need a compile-time dependency on it.
        // The start() method blocks forever (awaitTermination), so the daemon thread is expected.
        Thread serverThread = new Thread(() -> {
            try {
                LOG.info("Starting embedded ControlPlane server via reflection...");
                Class<?> serverClass = Class.forName("com.nextgen.controlplane.ControlPlaneServer");
                java.lang.reflect.Method startMethod = serverClass.getMethod("start");
                startMethod.invoke(null); // static method, blocks here
            } catch (ClassNotFoundException e) {
                LOG.warn("ControlPlaneServer class not found on classpath. " +
                        "Start the server separately via CLI: ROLE=server java -jar control-plane.jar");
            } catch (Exception e) {
                LOG.error("Failed to start embedded ControlPlane server", e);
            }
        }, "control-plane-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Give the server a moment to initialize, then transition to dashboard
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait for server to bind port
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Platform.runLater(() -> {
                LOG.info("Transitioning to dashboard (server mode)");
                connectionManager.connectTo("localhost",
                        ServerIdCodec.DEFAULT_PORT,
                        connectionManager.getCurrentPredictorPort());
                showMainDashboard();
            });
        }, "server-startup-wait").start();
    }

    private void registerNodeAndShowDashboard(ServerIdCodec.ServerAddress address) {
        new Thread(() -> {
            try {
                var client = connectionManager.getControlPlaneClient();
                if (client != null) {
                    String hostname = java.net.InetAddress.getLocalHost().getHostName();
                    String myIp = ServerIdCodec.detectLanIp();
                    var response = client.registerNode(hostname, myIp, 50051, hostname);
                    LOG.info("Node registered: {}", response.getStatus());
                }
            } catch (Exception e) {
                LOG.error("Failed to register node", e);
            }

            Platform.runLater(this::showMainDashboard);
        }, "node-registration").start();
    }

    private void showMainDashboard() {
        LOG.info("Showing Main Dashboard (role={}, serverId={})", chosenRole, serverId);

        MainWindow mainWindow = new MainWindow(
                primaryStage, connectionManager, themeService,
                chosenRole, serverId
        );
        mainWindow.show(scene);
    }

    /**
     * Smooth fade transition between screens.
     */
    private void transitionTo(javafx.scene.Node newContent) {
        // Fade out current content
        if (!rootContainer.getChildren().isEmpty()) {
            javafx.scene.Node current = rootContainer.getChildren().get(rootContainer.getChildren().size() - 1);

            FadeTransition fadeOut = new FadeTransition(Duration.millis(200), current);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> {
                rootContainer.getChildren().clear();
                addWithFadeIn(newContent);
            });
            fadeOut.play();
        } else {
            addWithFadeIn(newContent);
        }
    }

    private void addWithFadeIn(javafx.scene.Node content) {
        content.setOpacity(0);
        rootContainer.getChildren().add(content);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), content);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (connectionManager != null) {
            connectionManager.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
