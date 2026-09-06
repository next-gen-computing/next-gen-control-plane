package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.profile.DesktopProfile;

/** {@code GET /api/role/profile} response — what the frontend needs to decide whether to attempt a
 * silent auto-connect on startup instead of showing the role-selection screen. */
public record ProfileDto(boolean present, String role, String launchMode, String serverAddress, String label) {
    private static final ProfileDto ABSENT = new ProfileDto(false, null, null, null, null);

    public static ProfileDto absent() {
        return ABSENT;
    }

    public static ProfileDto from(DesktopProfile profile) {
        return new ProfileDto(true, profile.role(), profile.launchMode(), profile.serverAddress(), profile.label());
    }
}
