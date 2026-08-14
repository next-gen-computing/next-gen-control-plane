package com.nextgen.desktop.ui.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the design system's core invariant: the dark and light themes define the same set of colour
 * tokens, and {@code base.css} only ever refers to tokens that exist.
 *
 * <p>Without this, adding a token to one theme and forgetting the other ships a screen that looks
 * finished in dark mode and unstyled in light mode — the exact failure the previous UI had, where
 * hundreds of hardcoded dark hex values made the light theme unusable.
 */
class ThemeTokenParityTest {

    /** Matches a token definition, e.g. {@code -ng-surface: #131C2E;} */
    private static final Pattern DEFINITION = Pattern.compile("(-ng-[a-z0-9-]+)\\s*:");

    /** Matches a token reference, e.g. {@code -fx-background-color: -ng-surface;} */
    private static final Pattern REFERENCE = Pattern.compile(":\\s*(-ng-[a-z0-9-]+)\\s*;");

    private static String read(String resource) throws IOException {
        try (InputStream in = ThemeTokenParityTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "stylesheet missing from the classpath: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> match(Pattern pattern, String css) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(css);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    void bothThemesDefineTheSameTokens() throws IOException {
        Set<String> dark = match(DEFINITION, read(ThemeService.DARK_STYLESHEET));
        Set<String> light = match(DEFINITION, read(ThemeService.LIGHT_STYLESHEET));

        assertFalse(dark.isEmpty(), "dark theme defines no tokens at all");

        Set<String> missingFromLight = new TreeSet<>(dark);
        missingFromLight.removeAll(light);
        Set<String> missingFromDark = new TreeSet<>(light);
        missingFromDark.removeAll(dark);

        assertTrue(missingFromLight.isEmpty(), "tokens missing from light.css: " + missingFromLight);
        assertTrue(missingFromDark.isEmpty(), "tokens missing from dark.css: " + missingFromDark);
    }

    @Test
    void baseStylesheetOnlyReferencesTokensThatExist() throws IOException {
        Set<String> defined = match(DEFINITION, read(ThemeService.DARK_STYLESHEET));
        Set<String> referenced = match(REFERENCE, read(ThemeService.BASE_STYLESHEET));

        assertFalse(referenced.isEmpty(), "base.css references no tokens; is it still token-driven?");

        Set<String> undefined = new TreeSet<>(referenced);
        undefined.removeAll(defined);
        assertTrue(undefined.isEmpty(), "base.css uses undefined tokens: " + undefined);
    }

    @Test
    void baseStylesheetContainsNoLiteralColours() throws IOException {
        String base = read(ThemeService.BASE_STYLESHEET);

        // Strip comments before scanning, so explanatory prose mentioning a hex value does not fail
        // the check.
        String withoutComments = base.replaceAll("(?s)/\\*.*?\\*/", "");

        Matcher hex = Pattern.compile("#[0-9A-Fa-f]{3,8}\\b").matcher(withoutComments);
        assertFalse(hex.find(),
                "base.css must stay colour-free so both themes style it identically; found "
                        + (hex.reset().find() ? hex.group() : ""));

        Matcher rgb = Pattern.compile("\\brgba?\\s*\\(").matcher(withoutComments);
        assertFalse(rgb.find(), "base.css must not contain literal rgb()/rgba() colours");
    }

    @Test
    void everyStylesheetIsPresentOnTheClasspath() throws IOException {
        assertFalse(read(ThemeService.BASE_STYLESHEET).isBlank());
        assertFalse(read(ThemeService.DARK_STYLESHEET).isBlank());
        assertFalse(read(ThemeService.LIGHT_STYLESHEET).isBlank());
    }

    @Test
    void themeSelectionFollowsDarkModeFlag() {
        ThemeService service = new ThemeService();

        assertTrue(service.isDarkMode(), "dark is the documented default");
        assertEquals(ThemeService.DARK_STYLESHEET, service.themeStylesheet());

        service.setDarkMode(false);
        assertEquals(ThemeService.LIGHT_STYLESHEET, service.themeStylesheet());
    }
}
