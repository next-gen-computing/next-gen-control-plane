package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.account.AccountService;

/** Response for signup and password-reset — the only two actions that ever hand back a plaintext
 * recovery code, and only once, in this exact response; nothing re-displays it later. */
public record RecoveryCodeResultDto(boolean ok, AccountDto account, String recoveryCode, String message) {
    public static RecoveryCodeResultDto from(AccountService.RecoveryCodeResult result) {
        return result.ok()
                ? new RecoveryCodeResultDto(true, AccountDto.from(result.account()), result.recoveryCode(), null)
                : new RecoveryCodeResultDto(false, null, null, result.errorMessage());
    }
}
