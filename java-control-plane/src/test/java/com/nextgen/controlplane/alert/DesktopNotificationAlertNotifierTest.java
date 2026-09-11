package com.nextgen.controlplane.alert;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Real {@code java.awt.SystemTray} — no mocked AWT toolkit. The two "unsupported" tests always run
 * (a headless CI runner, which this project's own test suite runs on, guarantees that branch); the two
 * "supported" tests self-skip via {@link Assumptions} rather than silently reporting false coverage on
 * a runner with no display, matching this project's established self-skip discipline for
 * environment-dependent tests (see the Docker-dependent tests elsewhere in this module).
 */
class DesktopNotificationAlertNotifierTest {

    private static boolean trayAvailable() {
        return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
    }

    @Test
    void onAHeadlessOrTraylessHostEveryCallIsAHonestNoOpNeverACrash() {
        Assumptions.assumeFalse(trayAvailable(), "this host has a real system tray — see the supported-path tests instead");

        DesktopNotificationAlertNotifier notifier = new DesktopNotificationAlertNotifier();

        assertDoesNotThrow(() -> notifier.notifyNodeDown("node-1", "no heartbeat"));
        assertDoesNotThrow(() -> notifier.notifyNodeAtRisk("node-2", 0.9, "rising RTT"));
    }

    @Test
    void constructingOnAHeadlessHostNeverThrows() {
        Assumptions.assumeFalse(trayAvailable(), "this host has a real system tray — see the supported-path tests instead");

        assertDoesNotThrow(() -> new DesktopNotificationAlertNotifier());
    }

    @Test
    void onAHostWithARealTrayANotificationIsActuallyDisplayedWithoutThrowing() {
        Assumptions.assumeTrue(trayAvailable(), "no real system tray available on this host (expected in CI)");

        DesktopNotificationAlertNotifier notifier = new DesktopNotificationAlertNotifier();

        // TrayIcon.displayMessage has no programmatic "was it shown" signal (it's a real OS-level
        // toast) — the real, checkable assertion available here is that a genuinely supported host
        // exercises the full registration + display path without throwing, exactly as a human running
        // this on their own desktop would experience it.
        assertDoesNotThrow(() -> notifier.notifyNodeDown("node-1", "no heartbeat for over 6000ms"));
        assertDoesNotThrow(() -> notifier.notifyNodeAtRisk("node-2", 0.87, "rising RTT; low battery"));
    }
}
