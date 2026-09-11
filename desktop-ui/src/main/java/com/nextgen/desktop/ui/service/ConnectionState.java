package com.nextgen.desktop.ui.service;

/**
 * How the desktop app's link to the control plane currently stands.
 *
 * <p>This exists so no screen has to infer connectivity from the shape of the data. Previously an
 * RPC failure returned an empty node list, which is indistinguishable from a healthy cluster that
 * happens to have zero nodes — so a dead control plane rendered as "0 nodes, 100% healthy".
 */
public enum ConnectionState {

    /** Never attempted, or deliberately stopped. */
    IDLE("Disconnected", "#94A3B8"),

    /** An attempt is in flight, or a dropped connection is being retried. */
    RECONNECTING("Reconnecting…", "#F59E0B"),

    /** The most recent poll succeeded. Displayed data is current. */
    CONNECTED("Connected", "#10B981"),

    /** Repeated failures. Any data still on screen is stale and must be labelled as such. */
    DISCONNECTED("Disconnected", "#EF4444");

    private final String label;
    private final String colorHex;

    ConnectionState(String label, String colorHex) {
        this.label = label;
        this.colorHex = colorHex;
    }

    public String label() {
        return label;
    }

    public String colorHex() {
        return colorHex;
    }

    /** True when data on screen can be trusted as current. */
    public boolean isLive() {
        return this == CONNECTED;
    }
}
