package com.nextgen.desktop.ui.server.dto;

/** {@code password} is only used (and required) when switching to a password account; ignored for a
 * GitHub account, whose stored token is reused instead. */
public record SwitchAccountRequestDto(String accountId, String password) {
}
