package com.nextgen.desktop.ui.view;

import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Left sidebar navigation with glassmorphism styling.
 * Role-aware: shows different nav items for Server vs Node mode.
 * Displays role badge and Server ID when applicable.
 */
public class Sidebar {
    private final VBox root;
    private final Consumer<String> onNavigate;
    private final String role;
    private final String serverId;
    private NavButton activeButton;

    public Sidebar(Consumer<String> onNavigate, String role, String serverId) {
        this.onNavigate = onNavigate;
        this.role = role != null ? role : "server";
        this.serverId = serverId;
        this.root = createSidebar();
    }

    /**
     * Backward-compatible constructor (defaults to server, no ID).
     */
    public Sidebar(Consumer<String> onNavigate) {
        this(onNavigate, "server", null);
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(6);
        sidebar.setPrefWidth(260);
        sidebar.setPadding(new Insets(24, 16, 24, 16));
        sidebar.getStyleClass().add("sidebar");

        // ── App title with subtle accent ──
        VBox titleBox = new VBox(4);
        Label title = new Label("Next-Gen");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 22));
        title.setTextFill(Color.web("#F8FAFC"));
        Label subtitle = new Label("Control Plane");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 13));
        subtitle.setTextFill(Color.web("#64748B"));
        titleBox.getChildren().addAll(title, subtitle);
        titleBox.setPadding(new Insets(0, 0, 16, 8));

        // ── Role Badge ──
        HBox roleBadge = createRoleBadge();

        // ── Server ID display (server mode only) ──
        VBox serverIdBox = new VBox(4);
        serverIdBox.setPadding(new Insets(0, 0, 8, 8));
        if (serverId != null && !serverId.isEmpty() && "server".equals(role)) {
            Label idLabel = new Label("SERVER ID");
            idLabel.setFont(Font.font("Inter", FontWeight.BOLD, 9));
            idLabel.setTextFill(Color.web("#475569"));

            Label idValue = new Label(serverId);
            idValue.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 13));
            idValue.setTextFill(Color.web("#3B82F6"));
            idValue.setStyle("-fx-background-color: rgba(59, 130, 246, 0.08); -fx-background-radius: 8px; -fx-padding: 6px 12px;");

            // Copy on click
            idValue.setCursor(javafx.scene.Cursor.HAND);
            idValue.setOnMouseClicked(e -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(serverId);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                idValue.setText("Copied! ✓");
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.seconds(1.5));
                pause.setOnFinished(ev -> idValue.setText(serverId));
                pause.play();
            });
            idValue.setOnMouseEntered(e ->
                    idValue.setStyle("-fx-background-color: rgba(59, 130, 246, 0.15); -fx-background-radius: 8px; -fx-padding: 6px 12px;"));
            idValue.setOnMouseExited(e ->
                    idValue.setStyle("-fx-background-color: rgba(59, 130, 246, 0.08); -fx-background-radius: 8px; -fx-padding: 6px 12px;"));

            serverIdBox.getChildren().addAll(idLabel, idValue);
        }

        // ── Separator ──
        Region separator = new Region();
        separator.setPrefHeight(1);
        separator.setStyle("-fx-background-color: rgba(255, 255, 255, 0.04);");
        separator.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(separator, new Insets(8, 0, 12, 0));

        // ── Section label ──
        Label navLabel = new Label("NAVIGATION");
        navLabel.setFont(Font.font("Inter", FontWeight.BOLD, 10));
        navLabel.setTextFill(Color.web("#475569"));
        navLabel.setPadding(new Insets(0, 0, 8, 8));

        // ── Navigation items (role-aware) ──
        NavButton dashboardBtn = new NavButton("📊", "Dashboard", "dashboard", "#3B82F6");

        sidebar.getChildren().addAll(titleBox, roleBadge, serverIdBox, separator, navLabel, dashboardBtn);

        if ("server".equals(role)) {
            // Server gets full nav
            NavButton nodesBtn = new NavButton("🖥️", "Nodes", "nodes", "#10B981");
            NavButton tasksBtn = new NavButton("⚡", "Tasks", "tasks", "#F59E0B");
            sidebar.getChildren().addAll(nodesBtn, tasksBtn);
        }

        NavButton monitoringBtn = new NavButton("📈", "Monitoring", "monitoring", "#8B5CF6");
        NavButton settingsBtn = new NavButton("⚙️", "Settings", "settings", "#64748B");

        sidebar.getChildren().addAll(monitoringBtn, settingsBtn);

        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // ── Footer ──
        VBox footer = createFooter();
        sidebar.getChildren().add(footer);

        setActive(dashboardBtn);

        return sidebar;
    }

    private HBox createRoleBadge() {
        HBox badge = new HBox(8);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(10, 14, 10, 14));

        boolean isServer = "server".equals(role);
        String accentColor = isServer ? "#3B82F6" : "#8B5CF6";
        String emoji = isServer ? "🖥️" : "💻";
        String modeText = isServer ? "SERVER MODE" : "NODE MODE";

        badge.setStyle(String.format(
                "-fx-background-color: %s12;" +
                "-fx-background-radius: 12px;" +
                "-fx-border-color: %s22;" +
                "-fx-border-radius: 12px;" +
                "-fx-border-width: 1px;",
                accentColor, accentColor));

        // Pulsing status dot
        Circle statusDot = new Circle(4);
        statusDot.setFill(Color.web("#10B981"));

        ScaleTransition dotPulse = new ScaleTransition(Duration.seconds(1.5), statusDot);
        dotPulse.setFromX(0.8);
        dotPulse.setFromY(0.8);
        dotPulse.setToX(1.2);
        dotPulse.setToY(1.2);
        dotPulse.setCycleCount(ScaleTransition.INDEFINITE);
        dotPulse.setAutoReverse(true);
        dotPulse.play();

        Label emojiLabel = new Label(emoji);
        emojiLabel.setFont(Font.font(14));

        Label modeLabel = new Label(modeText);
        modeLabel.setFont(Font.font("Inter", FontWeight.BOLD, 10));
        modeLabel.setTextFill(Color.web(accentColor));

        badge.getChildren().addAll(statusDot, emojiLabel, modeLabel);
        VBox.setMargin(badge, new Insets(0, 0, 4, 0));

        return badge;
    }

    private VBox createFooter() {
        VBox footer = new VBox(4);
        footer.setPadding(new Insets(12, 8, 0, 8));

        Region sep = new Region();
        sep.setPrefHeight(1);
        sep.setStyle("-fx-background-color: rgba(255, 255, 255, 0.04);");
        sep.setMaxWidth(Double.MAX_VALUE);

        Label versionLabel = new Label("v1.0.0  ·  Phase 2");
        versionLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 10));
        versionLabel.setTextFill(Color.web("#475569"));
        versionLabel.setPadding(new Insets(8, 0, 0, 0));

        footer.getChildren().addAll(sep, versionLabel);
        return footer;
    }

    private void setActive(NavButton button) {
        if (activeButton != null) activeButton.setInactive();
        activeButton = button;
        activeButton.setActive();
        onNavigate.accept(button.getViewId());
    }

    private class NavButton extends HBox {
        private final String viewId;
        private final String accentColor;
        private final Label textLabel;
        private final Region indicator;
        private final Label emojiLabel;

        NavButton(String emoji, String text, String viewId, String accentColor) {
            super(10);
            this.viewId = viewId;
            this.accentColor = accentColor;
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(11, 16, 11, 12));
            setPrefWidth(228);
            setStyle("-fx-background-radius: 12px; -fx-cursor: hand;");

            indicator = new Region();
            indicator.setPrefSize(3, 18);
            indicator.setStyle("-fx-background-radius: 2px; -fx-background-color: transparent;");

            emojiLabel = new Label(emoji);
            emojiLabel.setFont(Font.font(14));
            emojiLabel.setOpacity(0.7);

            textLabel = new Label(text);
            textLabel.setFont(Font.font("Inter", FontWeight.MEDIUM, 13));
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            getChildren().addAll(indicator, emojiLabel, textLabel);
            setInactive();

            setOnMouseClicked(e -> Sidebar.this.setActive(this));
            setOnMouseEntered(e -> {
                if (this != activeButton) {
                    setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 12px; -fx-cursor: hand;");
                    textLabel.setTextFill(Color.web("#E2E8F0"));
                    emojiLabel.setOpacity(1.0);
                }
            });
            setOnMouseExited(e -> {
                if (this != activeButton) setInactive();
            });
        }

        void setActive() {
            setStyle(String.format("-fx-background-color: %s14; -fx-background-radius: 12px; -fx-cursor: hand;", accentColor));
            textLabel.setTextFill(Color.web("#F8FAFC"));
            textLabel.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 13));
            indicator.setStyle(String.format("-fx-background-radius: 2px; -fx-background-color: %s;", accentColor));
            emojiLabel.setOpacity(1.0);
        }

        void setInactive() {
            setStyle("-fx-background-radius: 12px; -fx-cursor: hand;");
            textLabel.setTextFill(Color.web("#94A3B8"));
            textLabel.setFont(Font.font("Inter", FontWeight.MEDIUM, 13));
            indicator.setStyle("-fx-background-radius: 2px; -fx-background-color: transparent;");
            emojiLabel.setOpacity(0.5);
        }

        String getViewId() { return viewId; }
    }
}
