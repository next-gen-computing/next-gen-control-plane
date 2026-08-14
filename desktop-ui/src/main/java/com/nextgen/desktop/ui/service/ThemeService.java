package com.nextgen.desktop.ui.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages dark/light theme switching across the application.
 */
public final class ThemeService {
    private final BooleanProperty darkMode = new SimpleBooleanProperty(true);
    private final List<Scene> registeredScenes = new ArrayList<>();

    public ThemeService() {
        darkMode.addListener((obs, oldVal, newVal) -> applyThemeToAll());
    }

    public BooleanProperty darkModeProperty() {
        return darkMode;
    }

    public boolean isDarkMode() {
        return darkMode.get();
    }

    public void setDarkMode(boolean dark) {
        darkMode.set(dark);
    }

    public void toggleTheme() {
        darkMode.set(!darkMode.get());
    }

    /** Structural rules, shared by both themes and written entirely in terms of colour tokens. */
    static final String BASE_STYLESHEET = "/styles/base.css";
    static final String DARK_STYLESHEET = "/styles/dark.css";
    static final String LIGHT_STYLESHEET = "/styles/light.css";

    public void registerScene(Scene scene) {
        if (scene == null || registeredScenes.contains(scene)) {
            // The same Scene used to be registered twice (once by DesktopApp, once by MainWindow),
            // which applied the theme twice and leaked a duplicate entry on every switch.
            if (scene != null) {
                applyTheme(scene);
            }
            return;
        }
        registeredScenes.add(scene);
        applyTheme(scene);
    }

    private void applyThemeToAll() {
        for (Scene scene : registeredScenes) {
            applyTheme(scene);
        }
    }

    private void applyTheme(Scene scene) {
        scene.getStylesheets().clear();
        // Order matters only for readability — JavaFX resolves looked-up colours at apply time — but
        // the theme file must always accompany base.css, which defines no colours of its own.
        scene.getStylesheets().add(resolve(themeStylesheet()));
        scene.getStylesheets().add(resolve(BASE_STYLESHEET));
    }

    String themeStylesheet() {
        return darkMode.get() ? DARK_STYLESHEET : LIGHT_STYLESHEET;
    }

    private String resolve(String path) {
        var url = getClass().getResource(path);
        if (url == null) {
            throw new IllegalStateException("Stylesheet missing from the classpath: " + path);
        }
        return url.toExternalForm();
    }

    public String getBackgroundColor() {
        return darkMode.get() ? "#0f172a" : "#f8fafc";
    }

    public String getCardColor() {
        return darkMode.get() ? "#1e293b" : "#ffffff";
    }

    public String getTextColor() {
        return darkMode.get() ? "#f8fafc" : "#1e293b";
    }

    public String getTextSecondaryColor() {
        return darkMode.get() ? "#94a3b8" : "#64748b";
    }

    public String getBorderColor() {
        return darkMode.get() ? "#334155" : "#e2e8f0";
    }
}
