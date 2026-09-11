package com.nextgen.controlplane.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;

/**
 * A native OS notification balloon/toast via {@code java.awt.SystemTray} — the third concrete {@link
 * AlertNotifier} channel, requiring zero new dependency since {@code SystemTray}/{@code TrayIcon} are
 * part of the JDK itself.
 *
 * <p>Only meaningful when the control plane process is actually running on a machine with a display a
 * human is looking at — a real, common deployment mode for this project specifically (it targets
 * operator-owned physical machines, including the operator's own desktop), but never true for a
 * headless server/container. {@link SystemTray#isSupported()} is checked once at construction: when
 * unsupported, every call becomes a logged-once no-op rather than throwing — the same "never crash the
 * caller, an unavailable channel is not a fatal error" discipline {@link WebhookAlertNotifier}/{@link
 * EmailAlertNotifier} apply to network failures, applied here to platform unavailability instead. This
 * is also why this channel needs an explicit opt-in flag at the wiring layer (see {@code
 * ControlPlaneServer}) rather than auto-enabling whenever a display happens to be present — silently
 * popping up a tray icon nobody asked for would be surprising, not helpful.
 */
public final class DesktopNotificationAlertNotifier implements AlertNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopNotificationAlertNotifier.class);

    private final boolean supported;
    private volatile TrayIcon trayIcon;

    public DesktopNotificationAlertNotifier() {
        this.supported = !java.awt.GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        if (!supported) {
            LOG.warn("Desktop notifications requested (ALERT_DESKTOP_NOTIFICATIONS_ENABLED=true) but no "
                    + "display/system tray is available on this host — every alert on this channel will "
                    + "be a logged no-op, not a crash.");
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            // A 1x1 transparent image: this process has no real application icon of its own to show, and
            // an invisible one is honest about that rather than presenting a fabricated logo.
            Image blank = Toolkit.getDefaultToolkit().createImage(new byte[]{
                    (byte) 0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0,
                    0, 0, 0, 0x21, (byte) 0xf9, 4, 1, 0, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2,
                    2, 0x44, 1, 0, 0x3b});
            trayIcon = new TrayIcon(blank, "NextGen Control Plane");
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
        } catch (AWTException e) {
            LOG.warn("Could not register a system tray icon for desktop notifications: {}", e.getMessage());
            trayIcon = null;
        }
    }

    @Override
    public void notifyNodeDown(String nodeId, String reason) {
        display("Node down: " + nodeId, reason, TrayIcon.MessageType.ERROR);
    }

    @Override
    public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
        display("Node at risk: " + nodeId, String.format("risk %.0f%% — %s", riskScore * 100, reason),
                TrayIcon.MessageType.WARNING);
    }

    private void display(String caption, String text, TrayIcon.MessageType type) {
        if (!supported || trayIcon == null) {
            return;
        }
        try {
            trayIcon.displayMessage(caption, text, type);
        } catch (RuntimeException e) {
            // Never let a platform-specific notification-toolkit failure propagate into a monitor's
            // sweep thread — same best-effort guarantee every other AlertNotifier implementation gives.
            LOG.warn("Could not display a desktop notification: {}", e.getMessage());
        }
    }
}
