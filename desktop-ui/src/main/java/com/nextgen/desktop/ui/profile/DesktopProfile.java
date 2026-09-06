package com.nextgen.desktop.ui.profile;

/**
 * The device's remembered onboarding choice — what lets a second launch skip straight back to the
 * dashboard instead of asking "Server or Node?" again every time.
 *
 * <p>{@code role} is {@code "server"} or {@code "node"}. {@code launchMode} ({@code "native"} /
 * {@code "docker"}) only applies to the server role; {@code serverAddress}/{@code enrollmentUsed}
 * only apply to the node role — the unused half is null/false for the other role, never guessed.
 */
public record DesktopProfile(
        String role,
        String launchMode,
        String serverAddress,
        boolean enrollmentUsed,
        String label,
        long savedAtEpochMillis
) {
    public boolean isServerRole() {
        return "server".equals(role);
    }

    public boolean isDockerLaunchMode() {
        return "docker".equals(launchMode);
    }
}
