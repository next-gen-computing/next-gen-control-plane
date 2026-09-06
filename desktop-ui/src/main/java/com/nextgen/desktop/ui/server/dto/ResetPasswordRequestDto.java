package com.nextgen.desktop.ui.server.dto;

public record ResetPasswordRequestDto(String email, String recoveryCode, String newPassword, String confirmPassword) {
}
