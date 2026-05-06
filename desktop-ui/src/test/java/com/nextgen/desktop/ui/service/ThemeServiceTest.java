package com.nextgen.desktop.ui.service;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ThemeService.
 */
class ThemeServiceTest {

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
    }

    @Test
    void testDefaultDarkMode() {
        ThemeService service = new ThemeService();
        assertTrue(service.isDarkMode());
    }

    @Test
    void testToggleTheme() {
        ThemeService service = new ThemeService();
        assertTrue(service.isDarkMode());

        service.toggleTheme();
        assertFalse(service.isDarkMode());

        service.toggleTheme();
        assertTrue(service.isDarkMode());
    }

    @Test
    void testSetDarkMode() {
        ThemeService service = new ThemeService();
        service.setDarkMode(false);
        assertFalse(service.isDarkMode());

        service.setDarkMode(true);
        assertTrue(service.isDarkMode());
    }

    @Test
    void testColorValuesInDarkMode() {
        ThemeService service = new ThemeService();
        assertEquals("#0f172a", service.getBackgroundColor());
        assertEquals("#1e293b", service.getCardColor());
        assertEquals("#f8fafc", service.getTextColor());
    }

    @Test
    void testColorValuesInLightMode() {
        ThemeService service = new ThemeService();
        service.toggleTheme();
        assertEquals("#f8fafc", service.getBackgroundColor());
        assertEquals("#ffffff", service.getCardColor());
        assertEquals("#1e293b", service.getTextColor());
    }

    @Test
    void testSceneRegistration() {
        ThemeService service = new ThemeService();
        VBox root = new VBox();
        Scene scene = new Scene(root, 100, 100);

        service.registerScene(scene);
        assertFalse(scene.getStylesheets().isEmpty());
    }
}
