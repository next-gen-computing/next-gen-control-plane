package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.account.Account;

/** The safe, public projection of {@link Account} — never carries a password hash/salt or the raw
 * GitHub access token to the frontend, which has no legitimate use for either. */
public record AccountDto(boolean present, String id, String displayName, String email,
                          String githubLogin, String avatarUrl) {
    private static final AccountDto ABSENT = new AccountDto(false, null, null, null, null, null);

    public static AccountDto absent() {
        return ABSENT;
    }

    public static AccountDto from(Account account) {
        return new AccountDto(true, account.id(), account.displayName(), account.email(),
                account.githubLogin(), account.githubAvatarUrl());
    }
}
