package com.nextgen.desktop.ui.account;

import com.nextgen.controlplane.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Account signup/login/logout and the GitHub device-flow orchestration, sitting on top of {@link
 * AccountStore} (persistence) and {@link GitHubDeviceAuthClient} (the real OAuth calls). See {@link
 * Account}'s Javadoc for what "account" does and does not mean here — local-device only, no sync.
 */
public class AccountService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Excludes visually ambiguous characters (0/O, 1/I/L) — this code has to be hand-typed back in
     * during a reset, unlike a password a manager can autofill. */
    private static final String RECOVERY_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountStore store;
    private final GitHubDeviceAuthClient github;
    private final ConcurrentHashMap<String, PendingGitHubLogin> pendingGitHubLogins = new ConcurrentHashMap<>();

    public AccountService(AccountStore store) {
        this(store, new GitHubDeviceAuthClient());
    }

    AccountService(AccountStore store, GitHubDeviceAuthClient github) {
        this.store = store;
        this.github = github;
    }

    public record Result(boolean ok, Account account, String errorMessage) {
        public static Result ok(Account account) {
            return new Result(true, account, null);
        }

        public static Result error(String message) {
            return new Result(false, null, message);
        }
    }

    /** Carries the one-time-visible plaintext recovery code alongside the outcome — only {@link
     * #signUp} and {@link #resetPassword} ever produce one of these, and only the caller of that exact
     * request ever sees {@code recoveryCode}; it is never persisted or logged in plaintext anywhere. */
    public record RecoveryCodeResult(boolean ok, Account account, String recoveryCode, String errorMessage) {
        public static RecoveryCodeResult ok(Account account, String recoveryCode) {
            return new RecoveryCodeResult(true, account, recoveryCode, null);
        }

        public static RecoveryCodeResult error(String message) {
            return new RecoveryCodeResult(false, null, null, message);
        }
    }

    private static String generateRecoveryCode() {
        StringBuilder sb = new StringBuilder(14);
        for (int group = 0; group < 3; group++) {
            if (group > 0) sb.append('-');
            for (int i = 0; i < 4; i++) {
                sb.append(RECOVERY_CODE_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_CODE_ALPHABET.length())));
            }
        }
        return sb.toString();
    }

    public Optional<Account> currentAccount() {
        return store.currentAccount();
    }

    public List<Account> listAccounts() {
        return store.listAccounts();
    }

    // ── Email + password ─────────────────────────────────────────────────

    public RecoveryCodeResult signUp(String emailRaw, char[] password, char[] confirmPassword,
            String displayNameRaw) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return RecoveryCodeResult.error("Enter a valid email address");
        }
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            return RecoveryCodeResult.error("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (confirmPassword == null || !java.util.Arrays.equals(password, confirmPassword)) {
            return RecoveryCodeResult.error("Passwords do not match");
        }
        if (store.findByEmail(email).isPresent()) {
            return RecoveryCodeResult.error("An account with this email already exists on this device — log in instead");
        }

        String displayName = (displayNameRaw == null || displayNameRaw.isBlank())
                ? email.substring(0, email.indexOf('@'))
                : displayNameRaw.trim();
        String salt = PasswordHasher.generateSalt();
        String recoveryCode = generateRecoveryCode();
        String recoverySalt = PasswordHasher.generateSalt();
        long now = System.currentTimeMillis();
        Account account = new Account(UUID.randomUUID().toString(), displayName, email,
                PasswordHasher.hash(password, salt), salt,
                PasswordHasher.hash(recoveryCode.toCharArray(), recoverySalt), recoverySalt,
                null, null, null, now, now);
        store.save(account, true);
        LOG.info("Signed up new local account for {}", email);
        return RecoveryCodeResult.ok(account, recoveryCode);
    }

    /** Resets a password account's password using the one-time recovery code shown at signup (or the
     * most recent previous reset — each use issues a fresh one, exactly like backup-code rotation). No
     * email is involved because this app has no email-sending capability; see this class's Javadoc. */
    public RecoveryCodeResult resetPassword(String emailRaw, String recoveryCodeRaw, char[] newPassword,
            char[] confirmPassword) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        String recoveryCode = recoveryCodeRaw == null ? "" : recoveryCodeRaw.trim().toUpperCase();
        Optional<Account> found = store.findByEmail(email);
        // Same generic-failure discipline as login(): unknown email and wrong recovery code produce the
        // identical message, so this endpoint can't be used to enumerate registered emails either.
        if (found.isEmpty() || !found.get().hasRecoveryCode()
                || !PasswordHasher.matches(recoveryCode.toCharArray(), found.get().recoveryCodeSalt(),
                        found.get().recoveryCodeHash())) {
            return RecoveryCodeResult.error("Invalid email or recovery code");
        }
        if (newPassword == null || newPassword.length < MIN_PASSWORD_LENGTH) {
            return RecoveryCodeResult.error("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (confirmPassword == null || !java.util.Arrays.equals(newPassword, confirmPassword)) {
            return RecoveryCodeResult.error("Passwords do not match");
        }

        String newSalt = PasswordHasher.generateSalt();
        String newRecoveryCode = generateRecoveryCode();
        String newRecoverySalt = PasswordHasher.generateSalt();
        Account account = found.get()
                .withPassword(PasswordHasher.hash(newPassword, newSalt), newSalt)
                .withRecoveryCode(PasswordHasher.hash(newRecoveryCode.toCharArray(), newRecoverySalt), newRecoverySalt)
                .withLastLogin(System.currentTimeMillis());
        store.save(account, true);
        LOG.info("Password reset via recovery code for {}", email);
        return RecoveryCodeResult.ok(account, newRecoveryCode);
    }

    public Result login(String emailRaw, char[] password) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        Optional<Account> found = store.findByEmail(email);
        // Deliberately the same error whether the email is unknown or the password is wrong — telling
        // the two apart lets an attacker enumerate which emails have accounts on this device.
        if (found.isEmpty() || password == null
                || !PasswordHasher.matches(password, found.get().passwordSalt(), found.get().passwordHash())) {
            return Result.error("Invalid email or password");
        }
        Account account = found.get().withLastLogin(System.currentTimeMillis());
        store.save(account, true);
        return Result.ok(account);
    }

    public void logout() {
        store.signOut();
    }

    /** "Switch account" for an already-used password account on this device — re-asks for the
     * password, the same reasoning a shared-device OS account switcher would. */
    public Result switchToPasswordAccount(String accountId, char[] password) {
        Optional<Account> found = store.findById(accountId);
        if (found.isEmpty() || !found.get().isPasswordAccount()) {
            return Result.error("No such account on this device");
        }
        if (password == null || !PasswordHasher.matches(password, found.get().passwordSalt(), found.get().passwordHash())) {
            return Result.error("Invalid password");
        }
        Account account = found.get().withLastLogin(System.currentTimeMillis());
        store.save(account, true);
        return Result.ok(account);
    }

    /** "Switch account" for a GitHub account already used on this device — the stored token from the
     * original login is reused rather than asking the user to redo the device-flow dance every time. */
    public Result switchToGitHubAccount(String accountId) {
        Optional<Account> found = store.findById(accountId);
        if (found.isEmpty() || !found.get().isGitHubAccount()) {
            return Result.error("No such account on this device");
        }
        Account account = found.get().withLastLogin(System.currentTimeMillis());
        store.save(account, true);
        return Result.ok(account);
    }

    // ── GitHub OAuth device flow ─────────────────────────────────────────

    public boolean isGitHubConfigured() {
        return !githubClientId().isBlank();
    }

    private static String githubClientId() {
        return EnvConfig.stringValue("GITHUB_OAUTH_CLIENT_ID", "");
    }

    public record GitHubStart(String handle, String userCode, String verificationUri,
                               int intervalSeconds, int expiresInSeconds) {
    }

    public sealed interface GitHubPoll {
        record Pending(int intervalSeconds) implements GitHubPoll {
        }

        record Success(Account account) implements GitHubPoll {
        }

        record Failed(String reason) implements GitHubPoll {
        }
    }

    private record PendingGitHubLogin(String deviceCode, long expiresAtEpochMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtEpochMillis;
        }
    }

    public GitHubStart startGitHubLogin() throws IOException {
        if (!isGitHubConfigured()) {
            throw new IllegalStateException(
                    "GitHub login is not configured on this device (set GITHUB_OAUTH_CLIENT_ID)");
        }
        pendingGitHubLogins.values().removeIf(PendingGitHubLogin::isExpired);

        GitHubDeviceAuthClient.DeviceCode deviceCode;
        try {
            deviceCode = github.requestDeviceCode(githubClientId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting GitHub login", e);
        }

        String handle = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + deviceCode.expiresInSeconds() * 1000L;
        pendingGitHubLogins.put(handle, new PendingGitHubLogin(deviceCode.deviceCode(), expiresAt));
        return new GitHubStart(handle, deviceCode.userCode(), deviceCode.verificationUri(),
                deviceCode.intervalSeconds(), deviceCode.expiresInSeconds());
    }

    /** Called once per {@code intervalSeconds} by the frontend, exactly as {@code gh auth login}
     * itself polls — this method makes at most one real network call per invocation. */
    public GitHubPoll pollGitHubLogin(String handle) {
        PendingGitHubLogin pending = pendingGitHubLogins.get(handle);
        if (pending == null) {
            return new GitHubPoll.Failed("This login attempt is no longer active — start again");
        }
        if (pending.isExpired()) {
            pendingGitHubLogins.remove(handle);
            return new GitHubPoll.Failed("The code expired before it was entered — start again");
        }

        GitHubDeviceAuthClient.PollResult result;
        try {
            result = github.pollForToken(githubClientId(), pending.deviceCode());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("GitHub device-flow poll failed: {}", e.getMessage());
            return new GitHubPoll.Pending(5);
        }

        return switch (result) {
            case GitHubDeviceAuthClient.PollResult.Pending ignored -> new GitHubPoll.Pending(5);
            case GitHubDeviceAuthClient.PollResult.SlowDown slowDown -> new GitHubPoll.Pending(slowDown.newIntervalSeconds());
            case GitHubDeviceAuthClient.PollResult.Denied ignored -> {
                pendingGitHubLogins.remove(handle);
                yield new GitHubPoll.Failed("Sign-in was denied");
            }
            case GitHubDeviceAuthClient.PollResult.Expired ignored -> {
                pendingGitHubLogins.remove(handle);
                yield new GitHubPoll.Failed("The code expired before it was entered — start again");
            }
            case GitHubDeviceAuthClient.PollResult.Success success -> {
                pendingGitHubLogins.remove(handle);
                yield completeGitHubLogin(success.accessToken());
            }
        };
    }

    private GitHubPoll completeGitHubLogin(String accessToken) {
        GitHubDeviceAuthClient.GitHubUser user;
        try {
            user = github.fetchUser(accessToken);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Signed in with GitHub but could not fetch the profile: {}", e.getMessage());
            return new GitHubPoll.Failed("Signed in, but could not read the GitHub profile: " + e.getMessage());
        }

        Optional<Account> existing = store.findByGitHubLogin(user.login());
        long now = System.currentTimeMillis();
        Account account = existing
                .map(a -> new Account(a.id(), user.name(), null, null, null, null, null,
                        user.login(), user.avatarUrl(), accessToken, a.createdAtEpochMillis(), now))
                .orElseGet(() -> new Account(UUID.randomUUID().toString(), user.name(), null, null, null,
                        null, null, user.login(), user.avatarUrl(), accessToken, now, now));
        store.save(account, true);
        LOG.info("Signed in with GitHub as {}", user.login());
        return new GitHubPoll.Success(account);
    }
}
