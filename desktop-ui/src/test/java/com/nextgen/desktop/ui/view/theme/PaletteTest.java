package com.nextgen.desktop.ui.view.theme;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the palette's structural invariants.
 *
 * <p>The colour values themselves were validated separately for lightness band, chroma floor,
 * colour-blindness separation and surface contrast against this application's own chart surfaces.
 * What is asserted here is the part that code can break: slot stability, the reserved status range,
 * and the folding behaviour past the last slot.
 */
class PaletteTest {

    @Test
    void everySlotIsADistinctHexColour() {
        for (boolean dark : new boolean[]{true, false}) {
            Set<String> seen = new HashSet<>();
            for (int slot = 0; slot < Palette.CATEGORICAL_SLOTS; slot++) {
                String color = Palette.categorical(slot, dark);
                assertTrue(color.matches("#[0-9a-fA-F]{6}"), "malformed colour: " + color);
                assertTrue(seen.add(color), "duplicate slot colour in "
                        + (dark ? "dark" : "light") + ": " + color);
            }
        }
    }

    @Test
    void slotsPastTheLastFoldToASingleNeutralRatherThanInventingHues() {
        String first = Palette.categorical(Palette.CATEGORICAL_SLOTS, true);
        String second = Palette.categorical(Palette.CATEGORICAL_SLOTS + 50, true);

        // A ninth series is never a generated hue; past the palette everything shares one neutral.
        assertEquals(first, second);
    }

    @Test
    void negativeSlotIsHandledRatherThanThrowing() {
        assertDoesNotThrow(() -> Palette.categorical(-1, true));
        assertEquals(Palette.categorical(Palette.CATEGORICAL_SLOTS, true),
                Palette.categorical(-1, true));
    }

    @Test
    void bothThemesDefineTheSameNumberOfSlots() {
        for (int slot = 0; slot < Palette.CATEGORICAL_SLOTS; slot++) {
            assertNotNull(Palette.categorical(slot, true));
            assertNotNull(Palette.categorical(slot, false));
        }
    }

    @Test
    void statusColoursAreNeverReusedAsSeriesColours() {
        Set<String> status = Set.of(Palette.STATUS_GOOD, Palette.STATUS_WARNING,
                Palette.STATUS_SERIOUS, Palette.STATUS_CRITICAL);

        for (boolean dark : new boolean[]{true, false}) {
            for (int slot = 0; slot < Palette.CATEGORICAL_SLOTS; slot++) {
                assertFalse(status.contains(Palette.categorical(slot, dark)),
                        "a status colour must never impersonate a series: "
                                + Palette.categorical(slot, dark));
            }
        }
    }

    // ── Utilisation status ───────────────────────────────────────────────────

    @Test
    void utilisationEscalatesThroughTheStatusRange() {
        assertEquals(Palette.STATUS_GOOD, Palette.utilisationStatus(10, true));
        assertEquals(Palette.STATUS_WARNING, Palette.utilisationStatus(65, true));
        assertEquals(Palette.STATUS_SERIOUS, Palette.utilisationStatus(80, true));
        assertEquals(Palette.STATUS_CRITICAL, Palette.utilisationStatus(95, true));
    }

    @Test
    void anUnavailableReadingIsNeutralNotHealthy() {
        // Colouring an unmeasured node green is the same lie as printing 0% for it.
        assertEquals(Palette.STATUS_UNKNOWN, Palette.utilisationStatus(0, false));
        assertEquals(Palette.STATUS_UNKNOWN, Palette.utilisationStatus(99, false));
    }

    @Test
    void nodeStatusMapsEveryUiState() {
        assertEquals(Palette.STATUS_GOOD, Palette.nodeStatus("HEALTHY"));
        assertEquals(Palette.STATUS_WARNING, Palette.nodeStatus("WARNING"));
        assertEquals(Palette.STATUS_CRITICAL, Palette.nodeStatus("OFFLINE"));
        assertEquals(Palette.STATUS_UNKNOWN, Palette.nodeStatus("UNKNOWN"));
    }

    @Test
    void unrecognisedOrNullStatusIsNeutralNotHealthy() {
        assertEquals(Palette.STATUS_UNKNOWN, Palette.nodeStatus(null));
        assertEquals(Palette.STATUS_UNKNOWN, Palette.nodeStatus("something-new"));
    }

    @Test
    void colourAccessorReturnsAUsableColour() {
        assertNotNull(Palette.categoricalColor(0, true));
        assertNotNull(Palette.categoricalColor(99, false));
    }
}
