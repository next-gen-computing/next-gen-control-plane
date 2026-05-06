package com.nextgen.desktop.ui.view;

import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.BoxBlur;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.Glow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Stunning full-screen welcome screen for role selection.
 * Users choose between Server mode (host a cluster) or Node mode (join a server).
 * Features animated gradient background, floating particles, and glassmorphism cards.
 */
public class RoleSelectionView {

    private final StackPane root;
    private final Consumer<String> onRoleSelected;
    private final Random random = new Random();

    public RoleSelectionView(Consumer<String> onRoleSelected) {
        this.onRoleSelected = onRoleSelected;
        this.root = createView();
    }

    public StackPane getRoot() {
        return root;
    }

    private StackPane createView() {
        StackPane stack = new StackPane();
        stack.setStyle("-fx-background-color: #0B0F19;");

        // Layer 1: Animated gradient background
        Pane gradientLayer = createAnimatedGradientBackground();

        // Layer 2: Floating particles
        Pane particleLayer = createParticleLayer();

        // Layer 3: Ambient glow orbs
        Pane glowOrbLayer = createGlowOrbs();

        // Layer 4: Main content
        VBox content = createMainContent();

        stack.getChildren().addAll(gradientLayer, glowOrbLayer, particleLayer, content);
        return stack;
    }

    // ─────────────────────────────────────────────
    //  ANIMATED GRADIENT BACKGROUND
    // ─────────────────────────────────────────────

    private Pane createAnimatedGradientBackground() {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);

        Rectangle rect = new Rectangle();
        rect.widthProperty().bind(pane.widthProperty());
        rect.heightProperty().bind(pane.heightProperty());

        // Initial gradient
        LinearGradient gradient1 = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0B0F19")),
                new Stop(0.3, Color.web("#1a1040")),
                new Stop(0.6, Color.web("#0d2137")),
                new Stop(1, Color.web("#0B0F19"))
        );
        rect.setFill(gradient1);

        pane.getChildren().add(rect);

        // Animate the gradient by cycling colors
        Timeline gradientAnim = new Timeline();
        gradientAnim.setCycleCount(Animation.INDEFINITE);

        for (int i = 0; i <= 100; i += 2) {
            final double frac = i / 100.0;
            gradientAnim.getKeyFrames().add(new KeyFrame(
                    Duration.seconds(i * 0.12),
                    e -> {
                        double shift = (Math.sin(frac * Math.PI * 2) + 1) / 2;
                        double shift2 = (Math.cos(frac * Math.PI * 2) + 1) / 2;

                        LinearGradient g = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                                new Stop(0, Color.web("#0B0F19")),
                                new Stop(0.25, interpolateColor("#1a1040", "#0d3251", shift)),
                                new Stop(0.55, interpolateColor("#0d2137", "#1a0d3a", shift2)),
                                new Stop(0.8, interpolateColor("#0f1a2e", "#1f0d2b", shift)),
                                new Stop(1, Color.web("#0B0F19"))
                        );
                        rect.setFill(g);
                    }
            ));
        }
        gradientAnim.play();

        return pane;
    }

    private Color interpolateColor(String c1Hex, String c2Hex, double factor) {
        Color c1 = Color.web(c1Hex);
        Color c2 = Color.web(c2Hex);
        return c1.interpolate(c2, factor);
    }

    // ─────────────────────────────────────────────
    //  FLOATING PARTICLE EFFECT
    // ─────────────────────────────────────────────

    private Pane createParticleLayer() {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);

        // Create particles once the pane has a size
        pane.widthProperty().addListener((obs, old, w) -> {
            if (w.doubleValue() > 0 && pane.getChildren().isEmpty()) {
                spawnParticles(pane, 40);
            }
        });

        return pane;
    }

    private void spawnParticles(Pane pane, int count) {
        double w = pane.getWidth();
        double h = pane.getHeight() > 0 ? pane.getHeight() : 900;

        for (int i = 0; i < count; i++) {
            Circle particle = new Circle(random.nextDouble() * 2 + 0.5);
            particle.setFill(Color.web("#ffffff", 0.08 + random.nextDouble() * 0.12));

            double startX = random.nextDouble() * w;
            double startY = random.nextDouble() * h;
            particle.setCenterX(startX);
            particle.setCenterY(startY);

            pane.getChildren().add(particle);

            // Float upward with gentle horizontal sway
            double duration = 15 + random.nextDouble() * 25;
            double endX = startX + (random.nextDouble() - 0.5) * 200;

            TranslateTransition tt = new TranslateTransition(Duration.seconds(duration), particle);
            tt.setFromY(0);
            tt.setToY(-(h + 50));
            tt.setFromX(0);
            tt.setToX(endX - startX);
            tt.setCycleCount(Animation.INDEFINITE);
            tt.setInterpolator(Interpolator.LINEAR);
            tt.setDelay(Duration.seconds(random.nextDouble() * duration));

            FadeTransition ft = new FadeTransition(Duration.seconds(duration / 2), particle);
            ft.setFromValue(0);
            ft.setToValue(0.2 + random.nextDouble() * 0.15);
            ft.setAutoReverse(true);
            ft.setCycleCount(Animation.INDEFINITE);
            ft.setDelay(Duration.seconds(random.nextDouble() * 3));

            tt.play();
            ft.play();
        }
    }

    // ─────────────────────────────────────────────
    //  AMBIENT GLOW ORBS
    // ─────────────────────────────────────────────

    private Pane createGlowOrbs() {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);

        // Large blurred orbs for ambient lighting
        Circle orb1 = createGlowOrb(300, Color.web("#3B82F6", 0.08));
        Circle orb2 = createGlowOrb(250, Color.web("#8B5CF6", 0.06));
        Circle orb3 = createGlowOrb(200, Color.web("#06B6D4", 0.05));

        pane.getChildren().addAll(orb1, orb2, orb3);

        // Position orbs once layout is ready
        pane.widthProperty().addListener((obs, old, w) -> {
            orb1.setCenterX(w.doubleValue() * 0.25);
            orb1.setCenterY(300);
            orb2.setCenterX(w.doubleValue() * 0.75);
            orb2.setCenterY(400);
            orb3.setCenterX(w.doubleValue() * 0.5);
            orb3.setCenterY(600);
        });

        // Gentle floating animation for orbs
        animateOrb(orb1, 30, 40);
        animateOrb(orb2, 25, -35);
        animateOrb(orb3, 35, 30);

        return pane;
    }

    private Circle createGlowOrb(double radius, Color color) {
        Circle orb = new Circle(radius);
        orb.setFill(color);
        orb.setEffect(new GaussianBlur(80));
        return orb;
    }

    private void animateOrb(Circle orb, double rangeX, double rangeY) {
        TranslateTransition tt = new TranslateTransition(Duration.seconds(12 + random.nextDouble() * 8), orb);
        tt.setFromX(-rangeX);
        tt.setToX(rangeX);
        tt.setFromY(-rangeY);
        tt.setToY(rangeY);
        tt.setCycleCount(Animation.INDEFINITE);
        tt.setAutoReverse(true);
        tt.setInterpolator(Interpolator.EASE_BOTH);
        tt.play();
    }

    // ─────────────────────────────────────────────
    //  MAIN CONTENT
    // ─────────────────────────────────────────────

    private VBox createMainContent() {
        VBox content = new VBox(40);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(60));
        content.setMaxWidth(900);

        // ── Branding Section ──
        VBox branding = createBrandingSection();

        // ── Role Cards ──
        HBox roleCards = createRoleCards();

        // ── Footer Hint ──
        Label footerHint = new Label("Choose how this machine participates in the cluster");
        footerHint.setFont(Font.font("Inter", FontWeight.NORMAL, 13));
        footerHint.setTextFill(Color.web("#64748B"));

        content.getChildren().addAll(branding, roleCards, footerHint);

        // Entrance animation
        content.setOpacity(0);
        content.setTranslateY(30);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(800), content);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.setDelay(Duration.millis(300));

        TranslateTransition slideUp = new TranslateTransition(Duration.millis(800), content);
        slideUp.setFromY(30);
        slideUp.setToY(0);
        slideUp.setDelay(Duration.millis(300));
        slideUp.setInterpolator(Interpolator.SPLINE(0.25, 0.1, 0.25, 1));

        fadeIn.play();
        slideUp.play();

        return content;
    }

    private VBox createBrandingSection() {
        VBox branding = new VBox(12);
        branding.setAlignment(Pos.CENTER);

        // Lightning icon with glow
        Label icon = new Label("⚡");
        icon.setFont(Font.font(48));
        icon.setEffect(new DropShadow(30, Color.web("#3B82F6", 0.6)));

        // Pulsing glow on icon
        ScaleTransition iconPulse = new ScaleTransition(Duration.seconds(2), icon);
        iconPulse.setFromX(1.0);
        iconPulse.setFromY(1.0);
        iconPulse.setToX(1.1);
        iconPulse.setToY(1.1);
        iconPulse.setCycleCount(Animation.INDEFINITE);
        iconPulse.setAutoReverse(true);
        iconPulse.setInterpolator(Interpolator.EASE_BOTH);
        iconPulse.play();

        // App title
        Label title = new Label("Next-Gen Control Plane");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 38));
        title.setTextFill(Color.web("#F8FAFC"));
        title.setEffect(new DropShadow(20, Color.web("#3B82F6", 0.15)));

        // Subtitle
        Label subtitle = new Label("Distributed Computing Infrastructure");
        subtitle.setFont(Font.font("Inter", FontWeight.NORMAL, 16));
        subtitle.setTextFill(Color.web("#94A3B8"));

        // Version badge
        HBox versionBadge = new HBox();
        versionBadge.setAlignment(Pos.CENTER);
        Label version = new Label("v1.0.0");
        version.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 11));
        version.setTextFill(Color.web("#3B82F6"));
        version.setStyle("-fx-background-color: rgba(59, 130, 246, 0.12); -fx-background-radius: 20px; -fx-padding: 4px 14px;");
        versionBadge.getChildren().add(version);

        branding.getChildren().addAll(icon, title, subtitle, versionBadge);
        return branding;
    }

    private HBox createRoleCards() {
        HBox cards = new HBox(32);
        cards.setAlignment(Pos.CENTER);

        VBox serverCard = createRoleCard(
                "🖥️",
                "Server",
                "Host a Cluster",
                "Start a control plane server. Other machines can join your cluster using a unique Server ID.",
                "#3B82F6",
                "#2563EB",
                "server"
        );

        VBox nodeCard = createRoleCard(
                "💻",
                "Node",
                "Join a Server",
                "Connect this machine to an existing server. You'll need the server's unique ID to connect.",
                "#8B5CF6",
                "#7C3AED",
                "node"
        );

        cards.getChildren().addAll(serverCard, nodeCard);
        return cards;
    }

    private VBox createRoleCard(String emoji, String roleName, String action,
                                 String description, String accentColor, String accentDark, String roleId) {
        VBox card = new VBox(20);
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(340);
        card.setPrefHeight(380);
        card.setPadding(new Insets(40, 32, 40, 32));
        card.setStyle(getCardBaseStyle(accentColor));

        // Top accent line
        Region accentLine = new Region();
        accentLine.setPrefHeight(3);
        accentLine.setMaxWidth(60);
        accentLine.setStyle(String.format(
                "-fx-background-color: linear-gradient(to right, %s, %s); -fx-background-radius: 2px;",
                accentColor, accentDark));

        // Icon with glow
        Label iconLabel = new Label(emoji);
        iconLabel.setFont(Font.font(52));
        iconLabel.setEffect(new DropShadow(25, Color.web(accentColor, 0.4)));

        // Role name
        Label nameLabel = new Label(roleName);
        nameLabel.setFont(Font.font("Inter", FontWeight.BOLD, 26));
        nameLabel.setTextFill(Color.web("#F8FAFC"));

        // Action label
        Label actionLabel = new Label(action);
        actionLabel.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 14));
        actionLabel.setTextFill(Color.web(accentColor));

        // Description
        Label descLabel = new Label(description);
        descLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 13));
        descLabel.setTextFill(Color.web("#94A3B8"));
        descLabel.setWrapText(true);
        descLabel.setTextAlignment(TextAlignment.CENTER);
        descLabel.setMaxWidth(260);

        // Arrow indicator
        Label arrow = new Label("→");
        arrow.setFont(Font.font("Inter", FontWeight.BOLD, 22));
        arrow.setTextFill(Color.web(accentColor, 0.6));

        card.getChildren().addAll(accentLine, iconLabel, nameLabel, actionLabel, descLabel, arrow);

        // ── Hover Animations ──
        card.setOnMouseEntered(e -> {
            card.setStyle(getCardHoverStyle(accentColor));
            card.setEffect(new DropShadow(40, Color.web(accentColor, 0.25)));

            ScaleTransition st = new ScaleTransition(Duration.millis(250), card);
            st.setToX(1.04);
            st.setToY(1.04);
            st.setInterpolator(Interpolator.SPLINE(0.25, 0.1, 0.25, 1));
            st.play();

            // Arrow slide
            TranslateTransition arrowSlide = new TranslateTransition(Duration.millis(250), arrow);
            arrowSlide.setToX(8);
            arrowSlide.play();

            arrow.setTextFill(Color.web(accentColor));
        });

        card.setOnMouseExited(e -> {
            card.setStyle(getCardBaseStyle(accentColor));
            card.setEffect(null);

            ScaleTransition st = new ScaleTransition(Duration.millis(250), card);
            st.setToX(1.0);
            st.setToY(1.0);
            st.setInterpolator(Interpolator.SPLINE(0.25, 0.1, 0.25, 1));
            st.play();

            TranslateTransition arrowSlide = new TranslateTransition(Duration.millis(250), arrow);
            arrowSlide.setToX(0);
            arrowSlide.play();

            arrow.setTextFill(Color.web(accentColor, 0.6));
        });

        // Click handler
        card.setOnMouseClicked(e -> {
            // Click animation
            ScaleTransition clickAnim = new ScaleTransition(Duration.millis(150), card);
            clickAnim.setToX(0.96);
            clickAnim.setToY(0.96);
            clickAnim.setAutoReverse(true);
            clickAnim.setCycleCount(2);
            clickAnim.setOnFinished(ev -> onRoleSelected.accept(roleId));
            clickAnim.play();
        });

        card.setCursor(javafx.scene.Cursor.HAND);

        return card;
    }

    private String getCardBaseStyle(String accentColor) {
        return String.format(
                "-fx-background-color: rgba(15, 23, 42, 0.65);" +
                "-fx-background-radius: 24px;" +
                "-fx-border-color: rgba(255, 255, 255, 0.06);" +
                "-fx-border-radius: 24px;" +
                "-fx-border-width: 1px;");
    }

    private String getCardHoverStyle(String accentColor) {
        return String.format(
                "-fx-background-color: rgba(15, 23, 42, 0.8);" +
                "-fx-background-radius: 24px;" +
                "-fx-border-color: %s44;" +
                "-fx-border-radius: 24px;" +
                "-fx-border-width: 1.5px;", accentColor);
    }
}
