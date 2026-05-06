package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.util.ServerIdCodec;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Node join screen shown after choosing Node role.
 * Provides a simple Server ID input field that decodes to IP:Port,
 * plus an expandable "Advanced" section for manual IP input.
 * Connects to the server and transitions to the main dashboard.
 */
public class NodeJoinView {

    private final StackPane root;
    private final BiConsumer<String, ServerIdCodec.ServerAddress> onConnect; // (serverId, address)
    private final Runnable onBack;
    private final Random random = new Random();

    private Label statusLabel;
    private VBox connectBtn;
    private Label connectBtnLabel;
    private Label connectBtnSub;

    public NodeJoinView(BiConsumer<String, ServerIdCodec.ServerAddress> onConnect, Runnable onBack) {
        this.onConnect = onConnect;
        this.onBack = onBack;
        this.root = createView();
    }

    public StackPane getRoot() {
        return root;
    }

    private StackPane createView() {
        StackPane stack = new StackPane();
        stack.setStyle("-fx-background-color: #0B0F19;");

        Pane bgLayer = createAnimatedBackground();
        Pane glowLayer = createGlowOrbs();
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

        pane.widthProperty().addListener((obs, old, w) -> {
            if (w.doubleValue() > 0 && pane.getChildren().size() < 3) {
                for (int i = 0; i < 20; i++) {
                    Circle p = new Circle(random.nextDouble() * 1.5 + 0.5);
                    p.setFill(Color.web("#8B5CF6", 0.06 + random.nextDouble() * 0.08));
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

        Circle orb1 = new Circle(220, Color.web("#8B5CF6", 0.06));
        orb1.setEffect(new GaussianBlur(80));
        Circle orb2 = new Circle(160, Color.web("#EC4899", 0.04));
        orb2.setEffect(new GaussianBlur(70));

        pane.getChildren().addAll(orb1, orb2);

        pane.widthProperty().addListener((obs, old, w) -> {
            orb1.setCenterX(w.doubleValue() * 0.35);
            orb1.setCenterY(350);
            orb2.setCenterX(w.doubleValue() * 0.65);
            orb2.setCenterY(500);
        });

        TranslateTransition t1 = new TranslateTransition(Duration.seconds(16), orb1);
        t1.setFromX(-25); t1.setToX(25);
        t1.setFromY(-18); t1.setToY(18);
        t1.setCycleCount(Animation.INDEFINITE);
        t1.setAutoReverse(true);
        t1.setInterpolator(Interpolator.EASE_BOTH);
        t1.play();

        return pane;
    }

    private VBox createMainContent() {
        VBox content = new VBox(36);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(60));
        content.setMaxWidth(650);

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
        VBox header = createHeader();

        // ── ID Input Card ──
        VBox idInputCard = createIdInputCard();

        // ── Advanced Section ──
        VBox advancedSection = createAdvancedSection();

        content.getChildren().addAll(topBar, header, idInputCard, advancedSection);
        return content;
    }

    private VBox createHeader() {
        VBox header = new VBox(12);
        header.setAlignment(Pos.CENTER);

        Label icon = new Label("💻");
        icon.setFont(Font.font(42));
        icon.setEffect(new DropShadow(20, Color.web("#8B5CF6", 0.4)));

        Label title = new Label("Join a Server");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 34));
        title.setTextFill(Color.web("#F8FAFC"));

        Label subtitle = new Label("Enter the Server ID to connect this machine as a compute node");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 15));
        subtitle.setTextFill(Color.web("#94A3B8"));
        subtitle.setTextAlignment(TextAlignment.CENTER);
        subtitle.setWrapText(true);

        header.getChildren().addAll(icon, title, subtitle);
        return header;
    }

    private VBox createIdInputCard() {
        VBox card = new VBox(24);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(36, 40, 36, 40));
        card.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.7);" +
                "-fx-background-radius: 20px;" +
                "-fx-border-color: rgba(139, 92, 246, 0.15);" +
                "-fx-border-radius: 20px;" +
                "-fx-border-width: 1px;");

        // Accent
        Region accent = new Region();
        accent.setPrefHeight(3);
        accent.setMaxWidth(80);
        accent.setStyle("-fx-background-color: linear-gradient(to right, #8B5CF6, #EC4899); -fx-background-radius: 2px;");

        Label cardTitle = new Label("SERVER ID");
        cardTitle.setFont(Font.font("Inter", FontWeight.BOLD, 11));
        cardTitle.setTextFill(Color.web("#8B5CF6"));

        // Big input field
        TextField idField = new TextField();
        idField.setPromptText("NGX-XXXX-XXXX");
        idField.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 32));
        idField.setStyle(
                "-fx-background-color: rgba(30, 41, 59, 0.6);" +
                "-fx-text-fill: #F8FAFC;" +
                "-fx-background-radius: 14px;" +
                "-fx-border-color: rgba(139, 92, 246, 0.2);" +
                "-fx-border-radius: 14px;" +
                "-fx-border-width: 1px;" +
                "-fx-padding: 16px 24px;" +
                "-fx-alignment: center;" +
                "-fx-prompt-text-fill: #475569;");
        idField.setMaxWidth(400);
        idField.setAlignment(Pos.CENTER);

        // Auto-format as user types
        idField.textProperty().addListener((obs, oldText, newText) -> {
            if (newText == null || newText.isEmpty()) return;

            // Strip non-hex except dashes and NGX prefix
            String stripped = newText.toUpperCase().replaceAll("[^A-F0-9NGXGX-]", "");

            // Remove any leading "NGX-" that the user might type
            if (stripped.startsWith("NGX-")) {
                stripped = stripped.substring(4).replaceAll("-", "");
            } else if (stripped.startsWith("NGX")) {
                stripped = stripped.substring(3).replaceAll("-", "");
            } else {
                stripped = stripped.replaceAll("-", "");
            }

            // Remove non-hex
            stripped = stripped.replaceAll("[^A-F0-9]", "");
            if (stripped.length() > 8) stripped = stripped.substring(0, 8);

            String formatted = ServerIdCodec.autoFormat(stripped);

            if (!formatted.equals(newText)) {
                javafx.application.Platform.runLater(() -> {
                    idField.setText(formatted);
                    idField.positionCaret(formatted.length());
                });
            }

            // Update validation status
            boolean valid = ServerIdCodec.isValid(formatted);
            if (valid) {
                idField.setStyle(
                        "-fx-background-color: rgba(30, 41, 59, 0.6);" +
                        "-fx-text-fill: #F8FAFC;" +
                        "-fx-background-radius: 14px;" +
                        "-fx-border-color: rgba(16, 185, 129, 0.5);" +
                        "-fx-border-radius: 14px;" +
                        "-fx-border-width: 1.5px;" +
                        "-fx-padding: 16px 24px;" +
                        "-fx-alignment: center;" +
                        "-fx-prompt-text-fill: #475569;");
                statusLabel.setText("✓ Valid Server ID — Ready to connect");
                statusLabel.setTextFill(Color.web("#10B981"));
                connectBtn.setDisable(false);
                connectBtn.setOpacity(1.0);
            } else {
                idField.setStyle(
                        "-fx-background-color: rgba(30, 41, 59, 0.6);" +
                        "-fx-text-fill: #F8FAFC;" +
                        "-fx-background-radius: 14px;" +
                        "-fx-border-color: rgba(139, 92, 246, 0.2);" +
                        "-fx-border-radius: 14px;" +
                        "-fx-border-width: 1px;" +
                        "-fx-padding: 16px 24px;" +
                        "-fx-alignment: center;" +
                        "-fx-prompt-text-fill: #475569;");
                statusLabel.setText("Enter 8 hex characters (e.g., NGX-C0A8-0164)");
                statusLabel.setTextFill(Color.web("#64748B"));
                connectBtn.setDisable(true);
                connectBtn.setOpacity(0.5);
            }
        });

        // Status label
        statusLabel = new Label("Enter the Server ID shown on the server machine");
        statusLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        statusLabel.setTextFill(Color.web("#64748B"));

        // Connect button
        StackPane connectBtnContainer = createConnectButton(idField);

        card.getChildren().addAll(accent, cardTitle, idField, statusLabel, connectBtnContainer);
        return card;
    }

    private StackPane createConnectButton(TextField idField) {
        StackPane container = new StackPane();
        container.setAlignment(Pos.CENTER);

        // Pulsing ring
        Circle pulseRing = new Circle(65);
        pulseRing.setFill(Color.TRANSPARENT);
        pulseRing.setStroke(Color.web("#8B5CF6", 0.12));
        pulseRing.setStrokeWidth(2);
        pulseRing.setOpacity(0);

        connectBtn = new VBox(4);
        connectBtn.setAlignment(Pos.CENTER);
        connectBtn.setPadding(new Insets(16, 44, 16, 44));
        connectBtn.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #8B5CF6, #7C3AED);" +
                "-fx-background-radius: 14px;");
        connectBtn.setCursor(javafx.scene.Cursor.HAND);
        connectBtn.setEffect(new DropShadow(18, Color.web("#8B5CF6", 0.3)));
        connectBtn.setDisable(true);
        connectBtn.setOpacity(0.5);

        connectBtnLabel = new Label("Connect");
        connectBtnLabel.setFont(Font.font("Inter", FontWeight.BOLD, 16));
        connectBtnLabel.setTextFill(Color.WHITE);

        connectBtnSub = new Label("Join the cluster");
        connectBtnSub.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        connectBtnSub.setTextFill(Color.web("#ffffff", 0.7));

        connectBtn.getChildren().addAll(connectBtnLabel, connectBtnSub);

        connectBtn.setOnMouseEntered(e -> {
            if (!connectBtn.isDisabled()) {
                connectBtn.setStyle(
                        "-fx-background-color: linear-gradient(to bottom right, #A78BFA, #8B5CF6);" +
                        "-fx-background-radius: 14px;");
                connectBtn.setEffect(new DropShadow(25, Color.web("#8B5CF6", 0.45)));
                ScaleTransition st = new ScaleTransition(Duration.millis(200), connectBtn);
                st.setToX(1.05);
                st.setToY(1.05);
                st.play();

                pulseRing.setOpacity(1);
                ScaleTransition ringPulse = new ScaleTransition(Duration.seconds(1.5), pulseRing);
                ringPulse.setFromX(0.9); ringPulse.setFromY(0.9);
                ringPulse.setToX(1.3); ringPulse.setToY(1.3);
                ringPulse.setCycleCount(Animation.INDEFINITE);
                ringPulse.setAutoReverse(true);
                ringPulse.play();
            }
        });

        connectBtn.setOnMouseExited(e -> {
            if (!connectBtn.isDisabled()) {
                connectBtn.setStyle(
                        "-fx-background-color: linear-gradient(to bottom right, #8B5CF6, #7C3AED);" +
                        "-fx-background-radius: 14px;");
                connectBtn.setEffect(new DropShadow(18, Color.web("#8B5CF6", 0.3)));
                ScaleTransition st = new ScaleTransition(Duration.millis(200), connectBtn);
                st.setToX(1.0);
                st.setToY(1.0);
                st.play();
                pulseRing.setOpacity(0);
            }
        });

        connectBtn.setOnMouseClicked(e -> {
            String inputId = idField.getText().trim();
            if (!ServerIdCodec.isValid(inputId)) return;

            // Show connecting state
            connectBtn.setDisable(true);
            connectBtnLabel.setText("Connecting...");
            connectBtnSub.setText("Resolving server address");
            statusLabel.setText("⟳ Connecting to " + inputId + "...");
            statusLabel.setTextFill(Color.web("#F59E0B"));

            // Blinking animation during connection
            FadeTransition blink = new FadeTransition(Duration.millis(400), connectBtn);
            blink.setFromValue(1.0);
            blink.setToValue(0.6);
            blink.setCycleCount(Animation.INDEFINITE);
            blink.setAutoReverse(true);
            blink.play();

            // Decode and connect on background thread
            new Thread(() -> {
                try {
                    ServerIdCodec.ServerAddress addr = ServerIdCodec.decode(inputId);

                    // Small delay for visual feedback
                    Thread.sleep(1200);

                    javafx.application.Platform.runLater(() -> {
                        blink.stop();
                        connectBtn.setOpacity(1.0);
                        statusLabel.setText("✓ Connected to " + addr.getIp() + ":" + addr.getPort());
                        statusLabel.setTextFill(Color.web("#10B981"));
                        connectBtnLabel.setText("Connected ✓");
                        connectBtnSub.setText("Joining cluster...");

                        PauseTransition pause = new PauseTransition(Duration.millis(800));
                        pause.setOnFinished(ev -> onConnect.accept(inputId, addr));
                        pause.play();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        blink.stop();
                        connectBtn.setOpacity(1.0);
                        connectBtn.setDisable(false);
                        statusLabel.setText("✗ Invalid Server ID: " + ex.getMessage());
                        statusLabel.setTextFill(Color.web("#EF4444"));
                        connectBtnLabel.setText("Connect");
                        connectBtnSub.setText("Join the cluster");
                    });
                }
            }).start();
        });

        container.getChildren().addAll(pulseRing, connectBtn);
        return container;
    }

    private VBox createAdvancedSection() {
        VBox section = new VBox(12);
        section.setAlignment(Pos.CENTER);

        // Toggle label
        Label toggleLabel = new Label("▸ Advanced: Direct IP Connection");
        toggleLabel.setFont(Font.font("Inter", FontWeight.MEDIUM, 12));
        toggleLabel.setTextFill(Color.web("#475569"));
        toggleLabel.setCursor(javafx.scene.Cursor.HAND);

        // Expandable content
        VBox advancedContent = new VBox(12);
        advancedContent.setAlignment(Pos.CENTER);
        advancedContent.setPadding(new Insets(16, 28, 16, 28));
        advancedContent.setStyle(
                "-fx-background-color: rgba(15, 23, 42, 0.4);" +
                "-fx-background-radius: 14px;" +
                "-fx-border-color: rgba(255, 255, 255, 0.04);" +
                "-fx-border-radius: 14px;");
        advancedContent.setVisible(false);
        advancedContent.setManaged(false);

        HBox ipRow = new HBox(12);
        ipRow.setAlignment(Pos.CENTER);

        Label ipLabel = new Label("IP Address");
        ipLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        ipLabel.setTextFill(Color.web("#94A3B8"));
        ipLabel.setPrefWidth(80);

        TextField ipField = new TextField();
        ipField.setPromptText("192.168.1.100");
        ipField.setStyle(
                "-fx-background-color: rgba(30, 41, 59, 0.6);" +
                "-fx-text-fill: #F8FAFC;" +
                "-fx-background-radius: 10px;" +
                "-fx-border-color: rgba(255, 255, 255, 0.08);" +
                "-fx-border-radius: 10px;" +
                "-fx-padding: 10px 14px;");
        ipField.setPrefWidth(200);

        Label portLabel = new Label("Port");
        portLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        portLabel.setTextFill(Color.web("#94A3B8"));

        TextField portField = new TextField("50051");
        portField.setStyle(
                "-fx-background-color: rgba(30, 41, 59, 0.6);" +
                "-fx-text-fill: #F8FAFC;" +
                "-fx-background-radius: 10px;" +
                "-fx-border-color: rgba(255, 255, 255, 0.08);" +
                "-fx-border-radius: 10px;" +
                "-fx-padding: 10px 14px;");
        portField.setPrefWidth(90);

        Label directConnectBtn = new Label("Connect →");
        directConnectBtn.setFont(Font.font("Inter", FontWeight.BOLD, 13));
        directConnectBtn.setTextFill(Color.web("#8B5CF6"));
        directConnectBtn.setStyle("-fx-background-color: rgba(139, 92, 246, 0.1); -fx-background-radius: 10px; -fx-padding: 10px 20px;");
        directConnectBtn.setCursor(javafx.scene.Cursor.HAND);
        directConnectBtn.setOnMouseEntered(e ->
                directConnectBtn.setStyle("-fx-background-color: rgba(139, 92, 246, 0.2); -fx-background-radius: 10px; -fx-padding: 10px 20px;"));
        directConnectBtn.setOnMouseExited(e ->
                directConnectBtn.setStyle("-fx-background-color: rgba(139, 92, 246, 0.1); -fx-background-radius: 10px; -fx-padding: 10px 20px;"));

        directConnectBtn.setOnMouseClicked(e -> {
            String ip = ipField.getText().trim();
            String portStr = portField.getText().trim();
            if (ip.isEmpty()) return;
            try {
                int port = Integer.parseInt(portStr);
                ServerIdCodec.ServerAddress addr = new ServerIdCodec.ServerAddress(ip, port);
                String id = "DIRECT-" + ip;
                onConnect.accept(id, addr);
            } catch (NumberFormatException ex) {
                statusLabel.setText("✗ Invalid port number");
                statusLabel.setTextFill(Color.web("#EF4444"));
            }
        });

        ipRow.getChildren().addAll(ipLabel, ipField, portLabel, portField, directConnectBtn);
        advancedContent.getChildren().add(ipRow);

        // Toggle visibility
        toggleLabel.setOnMouseClicked(e -> {
            boolean show = !advancedContent.isVisible();
            advancedContent.setVisible(show);
            advancedContent.setManaged(show);
            toggleLabel.setText(show ? "▾ Advanced: Direct IP Connection" : "▸ Advanced: Direct IP Connection");

            if (show) {
                FadeTransition ft = new FadeTransition(Duration.millis(300), advancedContent);
                ft.setFromValue(0);
                ft.setToValue(1);
                ft.play();
            }
        });

        toggleLabel.setOnMouseEntered(e -> toggleLabel.setTextFill(Color.web("#94A3B8")));
        toggleLabel.setOnMouseExited(e -> toggleLabel.setTextFill(Color.web("#475569")));

        section.getChildren().addAll(toggleLabel, advancedContent);
        return section;
    }
}
