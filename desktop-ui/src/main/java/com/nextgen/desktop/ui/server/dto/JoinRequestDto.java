package com.nextgen.desktop.ui.server.dto;

/** POST body for {@code /api/role/node/join}. {@code enrollmentToken} is null/blank for a plaintext join. */
public record JoinRequestDto(String address, String enrollmentToken) {
    public boolean wantsEnrollment() {
        return enrollmentToken != null && !enrollmentToken.isBlank();
    }
}
