package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.util.ServerIdCodec;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Server setup screen shown after choosing Server role.
 * Generates a unique Server ID (encoding the LAN IP), displays it prominently,
 * and provides a one-click "Launch Server" experience.
 */
public class ServerSetupView {

    private final StackPane root;
    private final Consumer<String> onLaunchServer;
    private final Runnable onBack;
    private final String serverId;
    private final String lanIp;
    private final Random random = new Random();

    public ServerSetupView(Consumer<String> onLaunchServer, Runnable onBack) {
        this.onLaunchServer = onLaunchServer;
        this.onBack = onBack;
        this.lanIp = ServerIdCodec.detectLanIp();
        this.serverId = ServerIdCodec.encode(lanIp);
        this.root = createView();
    }

    public StackPane getRoot() {
        return root;
    }

    public String getServerId() {
        return serverId;
    }

    public String getLanIp() {
        return lanIp;
    }

    private StackPane createView() {
        StackPane stack = new StackPane();
        stack.setStyle("-fx-background-color: #0B0F19;");

        // Background
        Pane bgLayer = createAnimatedBackground();
        Pane glowLayer = createGlowOrbs();

        // Content
        VBox content = createMainContent();

        stack.getChildren().addAll(bgLayer, glowLayer, content);

        // Entrance animation
        content.setOpacity(0);
        content.setTranslateY(40);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(700), content);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.setDelay(Duration.millis(200));
        TranslateTransition slideUp = new TranslateTransition(Duration.millis(700), content);
        slideUp.setFromY(40);
        slideUp.setToY(0);
        slideUp.setDelay(Duration.millis(200));
        slideUp.setInterpolator(Interpolator.SPLINE(0.25, 0.1, 0.25, 1));
        fadeIn.play();
        slideUp.play();

        return stack;
    }

    private Pane createAnimatedBackground() {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        Rectangle rect = new Rectangle();
        rect.widthProperty().bind(pane.widthProperty());
        rect.heightProperty().bind(pane.heightProperty());
        rect.setFill(Color.web("#0B0F19"));
        pane.getChildren().add(rect);

        // Floating particles
        pane.widthProperty().addListener((obs, old, w) -> {
            if (w.doubleValue() > 0 && pane.getChildren().size() < 3) {
                for (int i = 0; i < 25; i++) {
                    Circle p = new Circle(random.nextDouble() * 1.5 + 0.5);
                    p.setFill(Color.web("#3B82F6", 0.06 + random.nextDouble() * 0.08));
                    p.setCenterX(random.nextDouble() * w.doubleValue());
                    p.setCenterY(random.nextDouble() * 900);
                    pane.getChildren().add(p);

                    TranslateTransition tt = new TranslateTransition(
                            Duration.seconds(18 + random.nextDouble() * 20), p);
                    tt.setFromY(0);
                    tt.setToY(-950);
                    tt.setCycleCount(Animation.INDEFINITE);
                    tt.setInterpolator(Interpolator.LINEAR);
                    tt.setDelay(Duration.seconds(random.nextDouble() * 18));
                    tt.play();
                }
            }
        });

        return pane;
    }

    private Pane createGlowOrbs() {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);

        Circle orb1 = new Circle(250, Color.web("#3B82F6", 0.06));
        orb1.setEffect(new GaussianBlur(90));
        Circle orb2 = new Circle(180, Color.web("#06B6D4", 0.05));
        orb2.setEffect(new GaussianBlur(70));

        pane.getChildren().addAll(orb1, orb2);

        pane.widthProperty().addListener((obs, old, w) -> {
            orb1.setCenterX(w.doubleValue() * 0.3);
            orb1.setCenterY(350);
            orb2.setCenterX(w.doubleValue() * 0.7);
            orb2.setCenterY(500);
        });

        TranslateTransition t1 = new TranslateTransition(Duration.seconds(14), orb1);
        t1.setFromX(-30); t1.setToX(30);
        t1.setFromY(-20); t1.setToY(20);
        t1.setCycleCount(Animation.INDEFINITE);
        t1.setAutoReverse(true);
        t1.setInterpolator(Interpolator.EASE_BOTH);
        t1.play();

        TranslateTransition t2 = new TranslateTransition(Duration.seconds(18), orb2);
        t2.setFromX(20); t2.setToX(-25);
        t2.setFromY(15); t2.setToY(-20);
        t2.setCycleCount(Animation.INDEFINITE);
        t2.setAutoReverse(true);
        t2.setInterpolator(Interpolator.EASE_BOTH);
        t2.play();

        return pane;
    }

    private VBox createMainContent() {
        VBox content = new VBox(36);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(60));
        content.setMaxWidth(700);

        // ── Back button ──
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_LEFT);
        Label backBtn = new Label("← Back");
        backBtn.setFont(Font.font("Inter", FontWeight.MEDIUM, 14));
        backBtn.setTextFill(Color.web("#94A3B8"));
        backBtn.setCursor(javafx.scene.Cursor.HAND);
        backBtn.setOnMouseClicked(e -> onBack.run());
        backBtn.setOnMouseEntered(e -> backBtn.setTextFill(Color.web("#F8FAFC")));
        backBtn.setOnMouseExited(e -> backBtn.setTextFill(Color.web("#94A3B8")));
        topBar.getChildren().add(backBtn);

        // ── Header ──
        VBox header = new VBox(12);
        header.setAlignment(Pos.CENTER);

        Label icon = new Label("🖥️");
        icon.setFont(Font.font(42));
        icon.setEffect(new DropShadow(20, Color.web("#3B82F6", 0.4)));

        Label title = new Label("Server Mode");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 34));
        title.setTextFill(Color.web("#F8FAFC"));

        Label subtitle = new Label("Your machine will host the control plane cluster");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 15));
        subtitle.setTextFill(Color.web("#94A3B8"));

        header.getChildren().addAll(icon, title, subtitle);

        // ── Server ID Card ──
        VBox idCard = createServerIdCard();

        // ── Connection Info ──
        VBox connectionInfo = createConnectionInfoCard();

        // ── Launch Button ──
        StackPane launchBtn = createLaunchButton();

        content.getChildren().addAll(topBar, header, idCard, connectionInfo, launchBtn);
        return content;
    }

    private VBox createServerIdCard() {
        VBox card = new VBox(16);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(32, 40, 32, 40));
        card.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.7);" +
                "-fx-background-radius: 20px;" +
                "-fx-border-color: rgba(59, 130, 246, 0.15);" +
                "-fx-border-radius: 20px;" +
                "-fx-border-width: 1px;");

        // Accent bar
        Region accent = new Region();
        accent.setPrefHeight(3);
        accent.setMaxWidth(80);
        accent.setStyle("-fx-background-color: linear-gradient(to right, #3B82F6, #06B6D4); -fx-background-radius: 2px;");

        Label cardTitle = new Label("YOUR SERVER ID");
        cardTitle.setFont(Font.font("Inter", FontWeight.BOLD, 11));
        cardTitle.setTextFill(Color.web("#3B82F6"));
        cardTitle.setStyle("-fx-letter-spacing: 2px;");

        // The big ID display
        Label idDisplay = new Label(serverId);
        idDisplay.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 42));
        idDisplay.setTextFill(Color.web("#F8FAFC"));
        idDisplay.setEffect(new DropShadow(15, Color.web("#3B82F6", 0.3)));

        // Pulsing glow on the ID
        Timeline idGlow = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(idDisplay.effectProperty(),
                                new DropShadow(15, Color.web("#3B82F6", 0.3)))),
                new KeyFrame(Duration.seconds(1.5),
                        new KeyValue(idDisplay.effectProperty(),
                                new DropShadow(25, Color.web("#3B82F6", 0.5))))
        );
        idGlow.setCycleCount(Animation.INDEFINITE);
        idGlow.setAutoReverse(true);
        idGlow.play();

        Label shareHint = new Label("Share this ID with nodes that want to join your cluster");
        shareHint.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        shareHint.setTextFill(Color.web("#64748B"));
        shareHint.setTextAlignment(TextAlignment.CENTER);

        // Copy button
        HBox copyBox = new HBox(8);
        copyBox.setAlignment(Pos.CENTER);

        Label copyBtn = new Label("📋  Copy to Clipboard");
        copyBtn.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 13));
        copyBtn.setTextFill(Color.web("#3B82F6"));
        copyBtn.setStyle("-fx-background-color: rgba(59, 130, 246, 0.1); -fx-background-radius: 12px; -fx-padding: 10px 24px;");
        copyBtn.setCursor(javafx.scene.Cursor.HAND);

        Label copiedLabel = new Label("✓ Copied!");
        copiedLabel.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 13));
        copiedLabel.setTextFill(Color.web("#10B981"));
        copiedLabel.setOpacity(0);

        copyBtn.setOnMouseEntered(e ->
                copyBtn.setStyle("-fx-background-color: rgba(59, 130, 246, 0.2); -fx-background-radius: 12px; -fx-padding: 10px 24px;"));
        copyBtn.setOnMouseExited(e ->
                copyBtn.setStyle("-fx-background-color: rgba(59, 130, 246, 0.1); -fx-background-radius: 12px; -fx-padding: 10px 24px;"));

        copyBtn.setOnMouseClicked(e -> {
            ClipboardContent clipContent = new ClipboardContent();
            clipContent.putString(serverId);
            Clipboard.getSystemClipboard().setContent(clipContent);

            FadeTransition showCopied = new FadeTransition(Duration.millis(200), copiedLabel);
            showCopied.setToValue(1);
            showCopied.play();

            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(ev -> {
                FadeTransition hideCopied = new FadeTransition(Duration.millis(300), copiedLabel);
                hideCopied.setToValue(0);
                hideCopied.play();
            });
            pause.play();
        });

        copyBox.getChildren().addAll(copyBtn, copiedLabel);

        card.getChildren().addAll(accent, cardTitle, idDisplay, shareHint, copyBox);
        return card;
    }

    private VBox createConnectionInfoCard() {
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(20, 28, 20, 28));
        card.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.5);" +
                "-fx-background-radius: 16px;" +
                "-fx-border-color: rgba(255, 255, 255, 0.04);" +
                "-fx-border-radius: 16px;" +
                "-fx-border-width: 1px;");
        card.setMaxWidth(500);

        Label infoTitle = new Label("Connection Details");
        infoTitle.setFont(Font.font("Inter", FontWeight.BOLD, 14));
        infoTitle.setTextFill(Color.web("#94A3B8"));

        HBox ipRow = createInfoRow("LAN IP", lanIp);
        HBox portRow = createInfoRow("gRPC Port", String.valueOf(ServerIdCodec.DEFAULT_PORT));
        HBox hostnameRow;
        try {
            hostnameRow = createInfoRow("Hostname", java.net.InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            hostnameRow = createInfoRow("Hostname", "Unknown");
        }

        card.getChildren().addAll(infoTitle, ipRow, portRow, hostnameRow);
        return card;
    }

    private HBox createInfoRow(String label, String value) {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label(label);
        lbl.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        lbl.setTextFill(Color.web("#64748B"));
        lbl.setPrefWidth(100);

        Label val = new Label(value);
        val.setFont(Font.font("JetBrains Mono", FontWeight.MEDIUM, 13));
        val.setTextFill(Color.web("#E2E8F0"));

        row.getChildren().addAll(lbl, val);
        return row;
    }

    private StackPane createLaunchButton() {
        StackPane btnContainer = new StackPane();
        btnContainer.setAlignment(Pos.CENTER);

        // Pulsing ring behind the button
        Circle pulseRing = new Circle(80);
        pulseRing.setFill(Color.TRANSPARENT);
        pulseRing.setStroke(Color.web("#3B82F6", 0.15));
        pulseRing.setStrokeWidth(2);

        ScaleTransition ringPulse = new ScaleTransition(Duration.seconds(2), pulseRing);
        ringPulse.setFromX(0.8);
        ringPulse.setFromY(0.8);
        ringPulse.setToX(1.2);
        ringPulse.setToY(1.2);
        ringPulse.setCycleCount(Animation.INDEFINITE);
        ringPulse.setAutoReverse(true);
        ringPulse.setInterpolator(Interpolator.EASE_BOTH);
        ringPulse.play();

        FadeTransition ringFade = new FadeTransition(Duration.seconds(2), pulseRing);
        ringFade.setFromValue(0.4);
        ringFade.setToValue(0.1);
        ringFade.setCycleCount(Animation.INDEFINITE);
        ringFade.setAutoReverse(true);
        ringFade.play();

        // Button
        VBox btn = new VBox(6);
        btn.setAlignment(Pos.CENTER);
        btn.setPadding(new Insets(18, 48, 18, 48));
        btn.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #3B82F6, #2563EB);" +
                "-fx-background-radius: 16px;");
        btn.setCursor(javafx.scene.Cursor.HAND);
        btn.setEffect(new DropShadow(20, Color.web("#3B82F6", 0.35)));

        Label btnLabel = new Label("Launch Server");
        btnLabel.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        btnLabel.setTextFill(Color.WHITE);

        Label btnSub = new Label("Start accepting node connections");
        btnSub.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        btnSub.setTextFill(Color.web("#ffffff", 0.7));

        btn.getChildren().addAll(btnLabel, btnSub);

        btn.setOnMouseEntered(e -> {
            btn.setStyle(
                    "-fx-background-color: linear-gradient(to bottom right, #60A5FA, #3B82F6);" +
                    "-fx-background-radius: 16px;");
            btn.setEffect(new DropShadow(30, Color.web("#3B82F6", 0.5)));
            ScaleTransition st = new ScaleTransition(Duration.millis(200), btn);
            st.setToX(1.05);
            st.setToY(1.05);
            st.play();
        });

        btn.setOnMouseExited(e -> {
            btn.setStyle(
                    "-fx-background-color: linear-gradient(to bottom right, #3B82F6, #2563EB);" +
                    "-fx-background-radius: 16px;");
            btn.setEffect(new DropShadow(20, Color.web("#3B82F6", 0.35)));
            ScaleTransition st = new ScaleTransition(Duration.millis(200), btn);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });

        btn.setOnMouseClicked(e -> {
            // Disable button
            btn.setDisable(true);
            btnLabel.setText("Starting...");
            btnSub.setText("Initializing control plane");

            // Show loading spinner animation
            RotateTransition loadingAnim = new RotateTransition(Duration.seconds(1), btn);
            loadingAnim.setByAngle(0); // No rotation, just visual feedback via opacity

            FadeTransition blink = new FadeTransition(Duration.millis(500), btn);
            blink.setFromValue(1.0);
            blink.setToValue(0.7);
            blink.setCycleCount(6);
            blink.setAutoReverse(true);
            blink.setOnFinished(ev -> onLaunchServer.accept(serverId));
            blink.play();
        });

        btnContainer.getChildren().addAll(pulseRing, btn);
        return btnContainer;
    }
}
