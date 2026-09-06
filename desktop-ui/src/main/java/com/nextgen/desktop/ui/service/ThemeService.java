package com.nextgen.desktop.ui.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Tracks dark/light theme state for the WebView-hosted frontend. The frontend itself (HTML/CSS/JS,
 * styled via {@code web/css/tokens.css}/{@code palette.js}) owns all actual rendering — this class is
 * just the source of truth {@link com.nextgen.desktop.ui.server.ThemeRouteHandler}/
 * {@code StateRouteHandler} expose over HTTP so the WebView can read and change it.
 */
public final class ThemeService {
    private final BooleanProperty darkMode = new SimpleBooleanProperty(true);

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
}
