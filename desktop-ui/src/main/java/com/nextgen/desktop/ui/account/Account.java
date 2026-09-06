package com.nextgen.desktop.ui.account;

/**
 * A local device account — not a cloud account. Everything here lives in one JSON file on this
 * machine ({@link AccountStore}); there is no server anywhere that verifies a password or issues a
 * session token. "Log in with GitHub" is real (a genuine OAuth device-flow token, used once to read
 * this machine's own copy of your GitHub username/avatar/name), but it does not sync anything to any
 * other device — an account created here has no existence anywhere else until it is created there too.
 *
 * <p>Exactly one of the two identity paths is populated, never both partially: a password account has
 * {@code email}/{@code passwordHash}/{@code passwordSalt} set and {@code githubLogin} null; a
 * GitHub-only account has {@code githubLogin}/{@code githubAvatarUrl} set and the password fields
 * null. A future "link GitHub to my password account" feature could populate both, but nothing does
 * today — not built, not implied by this shape.
 *
 * <p>{@code recoveryCodeHash}/{@code recoveryCodeSalt} back password reset (only ever set for password
 * accounts): this app has no email-sending capability, so instead of a "we emailed you a link" flow
 * this project does not actually implement, a one-time recovery code is shown to the user once at
 * signup and its hash stored the same way a password's is — never the plaintext code itself.
 */
public record Account(
        String id,
        String displayName,
        String email,
        String passwordHash,
        String passwordSalt,
        String recoveryCodeHash,
        String recoveryCodeSalt,
        String githubLogin,
        String githubAvatarUrl,
        String githubAccessToken,
        long createdAtEpochMillis,
        long lastLoginAtEpochMillis
) {
    public boolean isPasswordAccount() {
        return email != null;
    }

    public boolean isGitHubAccount() {
        return githubLogin != null;
    }

    public boolean hasRecoveryCode() {
        return recoveryCodeHash != null;
    }

    public Account withLastLogin(long epochMillis) {
        return new Account(id, displayName, email, passwordHash, passwordSalt, recoveryCodeHash,
                recoveryCodeSalt, githubLogin, githubAvatarUrl, githubAccessToken, createdAtEpochMillis,
                epochMillis);
    }

    /** Used by password reset — the new password takes effect and, since a used recovery code cannot
     * be reused for a second reset, the caller must issue a fresh one via {@link #withRecoveryCode}. */
    public Account withPassword(String newPasswordHash, String newPasswordSalt) {
        return new Account(id, displayName, email, newPasswordHash, newPasswordSalt, recoveryCodeHash,
                recoveryCodeSalt, githubLogin, githubAvatarUrl, githubAccessToken, createdAtEpochMillis,
                lastLoginAtEpochMillis);
    }

    public Account withRecoveryCode(String newRecoveryCodeHash, String newRecoveryCodeSalt) {
        return new Account(id, displayName, email, passwordHash, passwordSalt, newRecoveryCodeHash,
                newRecoveryCodeSalt, githubLogin, githubAvatarUrl, githubAccessToken, createdAtEpochMillis,
                lastLoginAtEpochMillis);
    }
}
