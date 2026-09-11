package com.nextgen.desktop.ui.server.dto;

/** {@code status} is one of {@code pending}/{@code success}/{@code failed}; the frontend's polling
 * loop keys off it directly rather than inferring state from which fields are null. */
public record GitHubPollDto(String status, int intervalSeconds, AccountDto account, String message) {
    public static GitHubPollDto pending(int intervalSeconds) {
        return new GitHubPollDto("pending", intervalSeconds, null, null);
    }

    public static GitHubPollDto success(AccountDto account) {
        return new GitHubPollDto("success", 0, account, null);
    }

    public static GitHubPollDto failed(String message) {
        return new GitHubPollDto("failed", 0, null, message);
    }
}
