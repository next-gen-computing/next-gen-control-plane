package com.nextgen.desktop.ui.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ThemeService.
 */
class ThemeServiceTest {

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

}
