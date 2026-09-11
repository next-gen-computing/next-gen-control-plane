package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.account.Account;

/** Response shape for signup/login/switch-account — a validation failure here (bad email, wrong
 * password) is a normal outcome, not a server error, so it carries a plain message rather than
 * {@link ErrorDto}'s connection-failure-oriented category/glyph. */
public record AccountResultDto(boolean ok, AccountDto account, String message) {
    public static AccountResultDto ok(Account account) {
        return new AccountResultDto(true, AccountDto.from(account), null);
    }

    public static AccountResultDto error(String message) {
        return new AccountResultDto(false, null, message);
    }
}
