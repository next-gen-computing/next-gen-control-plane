package com.nextgen.desktop.ui.server.dto;

/** {@code state} is one of "NOT_ENROLLED" / "EXPIRED" / "EXPIRING_SOON" / "VALID". */
public record CertificateStatusDto(String state, String colorHex, String detail) {
}
