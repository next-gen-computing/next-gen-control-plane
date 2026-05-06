package com.nextgen.desktop.ui.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages dark/light theme switching across the application.
 */
public class ThemeService {
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

    public void registerScene(Scene scene) {
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
        if (darkMode.get()) {
            scene.getStylesheets().add(getClass().getResource("/styles/dark.css").toExternalForm());
        } else {
            scene.getStylesheets().add(getClass().getResource("/styles/light.css").toExternalForm());
        }
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
