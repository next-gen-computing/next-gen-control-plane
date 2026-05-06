package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.nextgen.desktop.ui.service.ThemeService;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Primary application window with sidebar navigation and dynamic content area.
 * Now role-aware: accepts "server" or "node" to adjust available navigation items.
 */
public class MainWindow {
    private final Stage primaryStage;
    private final GrpcConnectionManager connectionManager;
    private final ThemeService themeService;
    private final NodeMonitoringService monitoringService;
    private final TaskExecutionService taskExecutionService;
    private final String role;       // "server" or "node"
    private final String serverId;   // Server ID (for display)

    private final BorderPane root;
    private final StackPane contentArea;
    private final Sidebar sidebar;

    private DashboardView dashboardView;
    private NodeManagementView nodeManagementView;
    private TaskSubmissionView taskSubmissionView;
    private MonitoringView monitoringView;
    private SettingsView settingsView;

    public MainWindow(Stage primaryStage, GrpcConnectionManager connectionManager,
                      ThemeService themeService, String role, String serverId) {
        this.primaryStage = primaryStage;
        this.connectionManager = connectionManager;
        this.themeService = themeService;
        this.role = role != null ? role : "server";
        this.serverId = serverId;
        this.monitoringService = new NodeMonitoringService(connectionManager);
        this.taskExecutionService = new TaskExecutionService(connectionManager);

        this.root = new BorderPane();
        this.contentArea = new StackPane();
        this.contentArea.setPadding(new Insets(20));

        // Initialize views
        this.dashboardView = new DashboardView(monitoringService);
        this.monitoringView = new MonitoringView(monitoringService);
        this.settingsView = new SettingsView(connectionManager, themeService, monitoringService);

        // Server-only views
        if ("server".equals(this.role)) {
            this.nodeManagementView = new NodeManagementView(connectionManager, monitoringService);
            this.taskSubmissionView = new TaskSubmissionView(taskExecutionService, monitoringService);
        }

        // Sidebar (role-aware)
        this.sidebar = new Sidebar(this::onNavigate, this.role, this.serverId);

        // Layout
        root.setLeft(sidebar.getRoot());
        root.setCenter(contentArea);

        // Initial view
        onNavigate("dashboard");

        // Start monitoring
        monitoringService.startMonitoring();
    }

    /**
     * Show using an existing Scene (from DesktopApp flow).
     */
    public void show(Scene existingScene) {
        existingScene.setRoot(root);
        themeService.registerScene(existingScene);
    }

    /**
     * Show with a new Scene (backward compatibility).
     */
    public void show() {
        Scene scene = new Scene(root, 1400, 900);
        themeService.registerScene(scene);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void onNavigate(String viewId) {
        javafx.scene.Node newView = switch (viewId) {
            case "dashboard" -> dashboardView.getRoot();
            case "nodes" -> nodeManagementView != null ? nodeManagementView.getRoot() : dashboardView.getRoot();
            case "tasks" -> taskSubmissionView != null ? taskSubmissionView.getRoot() : dashboardView.getRoot();
            case "monitoring" -> monitoringView.getRoot();
            case "settings" -> settingsView.getRoot();
            default -> dashboardView.getRoot();
        };

        newView.setOpacity(0);
        contentArea.getChildren().setAll(newView);

        FadeTransition ft = new FadeTransition(Duration.millis(250), newView);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();
    }
}
