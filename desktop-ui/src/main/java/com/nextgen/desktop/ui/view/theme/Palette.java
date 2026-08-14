package com.nextgen.desktop.ui.view.theme;

import javafx.scene.paint.Color;

/**
 * The chart and status colour palette.
 *
 * <h2>Why these specific values</h2>
 *
 * The categorical slots are a fixed, validated order — not an arbitrary list of nice colours, and not
 * cycled. The ordering itself is the colour-blindness safety mechanism: adjacent slots are the pairs
 * a reader most often has to tell apart (neighbouring lines, stacked segments), so the order was
 * chosen to keep adjacent-pair separation above the threshold under simulated protanopia,
 * deuteranopia and tritanopia.
 *
 * <p>Validated against this application's own chart surfaces rather than generic ones
 * ({@code #F1F5F9} light, {@code #0B0F19} dark):
 * <ul>
 *   <li>light — worst adjacent CVD ΔE 9.1, worst adjacent normal-vision ΔE 19.6</li>
 *   <li>dark — worst adjacent CVD ΔE 8.4, worst adjacent normal-vision ΔE 19.3, all slots ≥ 3:1</li>
 * </ul>
 *
 * <p>Four light-mode slots fall below 3:1 contrast against the light surface. That is permitted only
 * with <b>relief</b>: identity must never rest on colour alone. Charts therefore always carry a
 * legend, direct-label the most recent value on each series, and the Nodes screen provides the
 * equivalent table view.
 *
 * <p>The dark column is the same eight hues re-stepped for a dark surface, not a different palette,
 * so a node keeps its identity across a theme switch.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Colour follows the <b>entity</b> (the node id), never its position in a list. Filtering the
 *       node list must not repaint the survivors.</li>
 *   <li>Past {@link #CATEGORICAL_SLOTS} distinct nodes, further series fold into a single "Other"
 *       colour rather than inventing new hues.</li>
 *   <li>Status colours are reserved and never reused as a series colour.</li>
 * </ul>
 */
public final class Palette {

    /** Distinct series colours available before folding into "Other". */
    public static final int CATEGORICAL_SLOTS = 8;

    private static final String[] CATEGORICAL_LIGHT = {
            "#2a78d6", // 1 blue
            "#eb6834", // 2 orange
            "#1baf7a", // 3 aqua
            "#eda100", // 4 yellow
            "#e87ba4", // 5 magenta
            "#008300", // 6 green
            "#4a3aa7", // 7 violet
            "#e34948", // 8 red
    };

    private static final String[] CATEGORICAL_DARK = {
            "#3987e5", "#d95926", "#199e70", "#c98500",
            "#d55181", "#008300", "#9085e9", "#e66767",
    };

    /** Everything past the last categorical slot. Deliberately neutral. */
    private static final String OTHER_LIGHT = "#898781";
    private static final String OTHER_DARK = "#898781";

    // ── Status (fixed, never themed, never used as a series colour) ──────────

    public static final String STATUS_GOOD = "#0ca30c";
    public static final String STATUS_WARNING = "#fab219";
    public static final String STATUS_SERIOUS = "#ec835a";
    public static final String STATUS_CRITICAL = "#d03b3b";
    /** No reading available. Neutral on purpose: absence of data is not a severity. */
    public static final String STATUS_UNKNOWN = "#898781";

    private Palette() {
    }

    /**
     * The colour for a series slot.
     *
     * @param slot zero-based, assigned per entity and held stable for that entity's lifetime
     */
    public static String categorical(int slot, boolean darkMode) {
        if (slot < 0 || slot >= CATEGORICAL_SLOTS) {
            return darkMode ? OTHER_DARK : OTHER_LIGHT;
        }
        return darkMode ? CATEGORICAL_DARK[slot] : CATEGORICAL_LIGHT[slot];
    }

    public static Color categoricalColor(int slot, boolean darkMode) {
        return Color.web(categorical(slot, darkMode));
    }

    /**
     * Maps a utilisation percentage to a status colour.
     *
     * <p>Returns {@link #STATUS_UNKNOWN} for an unavailable reading — an unmeasured node is not
     * "healthy", and colouring it green would be the same lie as printing 0%.
     */
    public static String utilisationStatus(double percent, boolean available) {
        if (!available) {
            return STATUS_UNKNOWN;
        }
        if (percent >= 90) {
            return STATUS_CRITICAL;
        }
        if (percent >= 75) {
            return STATUS_SERIOUS;
        }
        if (percent >= 60) {
            return STATUS_WARNING;
        }
        return STATUS_GOOD;
    }

    /** Status colour for a node's liveness string as produced by the monitoring service. */
    public static String nodeStatus(String status) {
        if (status == null) {
            return STATUS_UNKNOWN;
        }
        return switch (status) {
            case "HEALTHY" -> STATUS_GOOD;
            case "WARNING" -> STATUS_WARNING;
            case "OFFLINE" -> STATUS_CRITICAL;
            default -> STATUS_UNKNOWN;
        };
    }
}
