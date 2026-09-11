package com.nextgen.desktop.ui.server.dto;

public record SignUpRequestDto(String email, String password, String confirmPassword, String displayName) {
}
