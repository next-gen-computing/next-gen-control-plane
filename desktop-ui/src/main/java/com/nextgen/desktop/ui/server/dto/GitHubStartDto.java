package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.account.AccountService;

public record GitHubStartDto(String handle, String userCode, String verificationUri,
                              int intervalSeconds, int expiresInSeconds) {
    public static GitHubStartDto from(AccountService.GitHubStart start) {
        return new GitHubStartDto(start.handle(), start.userCode(), start.verificationUri(),
                start.intervalSeconds(), start.expiresInSeconds());
    }
}
