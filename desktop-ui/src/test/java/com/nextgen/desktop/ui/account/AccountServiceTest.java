package com.nextgen.desktop.ui.account;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {
    private HttpServer fakeGitHub;

    @AfterEach
    void stopFakeServer() {
        if (fakeGitHub != null) {
            fakeGitHub.stop(0);
        }
        System.clearProperty("GITHUB_OAUTH_CLIENT_ID");
    }

    /** Most tests don't care about confirm-password mismatch handling (covered separately below), so
     * this keeps every other call site a plain three-argument signup. */
    private static AccountService.RecoveryCodeResult signUp(AccountService service, String email,
            char[] password, String displayName) {
        return service.signUp(email, password, password, displayName);
    }

    // ── Email + password ─────────────────────────────────────────────────

    @Test
    void signUpRejectsAnInvalidEmail(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));

        var result = signUp(service, "not-an-email", "longenough1".toCharArray(), "Ada");

        assertFalse(result.ok());
        assertTrue(service.currentAccount().isEmpty());
    }

    @Test
    void signUpRejectsAShortPassword(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));

        var result = signUp(service, "ada@example.com", "short".toCharArray(), "Ada");

        assertFalse(result.ok());
    }

    @Test
    void signUpRejectsMismatchedConfirmPassword(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));

        var result = service.signUp("ada@example.com", "longenough1".toCharArray(),
                "differentpassword1".toCharArray(), "Ada");

        assertFalse(result.ok());
        assertTrue(service.currentAccount().isEmpty());
    }

    @Test
    void signUpSucceedsAndSignsInAndReturnsAOneTimeRecoveryCode(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));

        var result = signUp(service, "ada@example.com", "longenough1".toCharArray(), "Ada Lovelace");

        assertTrue(result.ok());
        assertEquals("Ada Lovelace", result.account().displayName());
        assertEquals("ada@example.com", service.currentAccount().orElseThrow().email());
        assertTrue(result.recoveryCode() != null && result.recoveryCode().length() > 0);
        assertTrue(result.account().hasRecoveryCode());
    }

    @Test
    void signUpDefaultsDisplayNameToTheEmailLocalPartWhenBlank(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));

        var result = signUp(service, "ada@example.com", "longenough1".toCharArray(), "  ");

        assertEquals("ada", result.account().displayName());
    }

    @Test
    void signUpRejectsADuplicateEmail(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        signUp(service, "ada@example.com", "longenough1".toCharArray(), "Ada");

        var result = signUp(service, "ADA@example.com", "differentpw1".toCharArray(), "Ada Again");

        assertFalse(result.ok());
    }

    @Test
    void loginSucceedsWithTheCorrectPassword(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        signUp(service, "ada@example.com", "longenough1".toCharArray(), "Ada");
        service.logout();

        var result = service.login("ada@example.com", "longenough1".toCharArray());

        assertTrue(result.ok());
        assertTrue(service.currentAccount().isPresent());
    }

    @Test
    void loginFailsWithTheWrongPasswordUsingAGenericMessage(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        signUp(service, "ada@example.com", "longenough1".toCharArray(), "Ada");
        service.logout();

        var wrongPassword = service.login("ada@example.com", "wrongpassword1".toCharArray());
        var unknownEmail = service.login("nobody@example.com", "irrelevant1".toCharArray());

        assertFalse(wrongPassword.ok());
        assertFalse(unknownEmail.ok());
        // Deliberately the same message for "wrong password" and "unknown email" — see AccountService's
        // own comment on why telling them apart would let an attacker enumerate registered emails.
        assertEquals(wrongPassword.errorMessage(), unknownEmail.errorMessage());
    }

    @Test
    void logoutClearsTheCurrentAccountButNotTheStoredData(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        signUp(service, "ada@example.com", "longenough1".toCharArray(), "Ada");

        service.logout();

        assertTrue(service.currentAccount().isEmpty());
        assertFalse(service.listAccounts().isEmpty());
    }

    @Test
    void switchToPasswordAccountRequiresTheCorrectPassword(@TempDir Path dir) {
        AccountStore store = new AccountStore(dir);
        AccountService service = new AccountService(store);
        signUp(service, "ada@example.com", "longenough1".toCharArray(), "Ada");
        String adaId = service.currentAccount().orElseThrow().id();
        signUp(service, "grace@example.com", "longenough2".toCharArray(), "Grace");

        var wrong = service.switchToPasswordAccount(adaId, "wrongpassword".toCharArray());
        var right = service.switchToPasswordAccount(adaId, "longenough1".toCharArray());

        assertFalse(wrong.ok());
        assertTrue(right.ok());
        assertEquals(adaId, service.currentAccount().orElseThrow().id());
    }

    // ── Password reset via recovery code ─────────────────────────────────

    @Test
    void resetPasswordSucceedsWithTheCorrectRecoveryCodeAndIssuesANewOne(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        var signUpResult = signUp(service, "ada@example.com", "originalpw1".toCharArray(), "Ada");
        String recoveryCode = signUpResult.recoveryCode();

        var reset = service.resetPassword("ada@example.com", recoveryCode,
                "brandnewpw1".toCharArray(), "brandnewpw1".toCharArray());

        assertTrue(reset.ok());
        assertNotEquals(recoveryCode, reset.recoveryCode());

        // Old password no longer works, new one does.
        service.logout();
        assertFalse(service.login("ada@example.com", "originalpw1".toCharArray()).ok());
        assertTrue(service.login("ada@example.com", "brandnewpw1".toCharArray()).ok());
    }

    @Test
    void resetPasswordFailsWithTheWrongRecoveryCode(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        signUp(service, "ada@example.com", "originalpw1".toCharArray(), "Ada");

        var reset = service.resetPassword("ada@example.com", "WRONG-CODE-0000",
                "brandnewpw1".toCharArray(), "brandnewpw1".toCharArray());

        assertFalse(reset.ok());
        service.logout();
        assertTrue(service.login("ada@example.com", "originalpw1".toCharArray()).ok());
    }

    @Test
    void resetPasswordFailsWithMismatchedConfirmPassword(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        var signUpResult = signUp(service, "ada@example.com", "originalpw1".toCharArray(), "Ada");

        var reset = service.resetPassword("ada@example.com", signUpResult.recoveryCode(),
                "brandnewpw1".toCharArray(), "somethingelse1".toCharArray());

        assertFalse(reset.ok());
    }

    @Test
    void aUsedRecoveryCodeCannotBeReusedForASecondReset(@TempDir Path dir) {
        AccountService service = new AccountService(new AccountStore(dir));
        var signUpResult = signUp(service, "ada@example.com", "originalpw1".toCharArray(), "Ada");
        String firstCode = signUpResult.recoveryCode();
        service.resetPassword("ada@example.com", firstCode, "secondpw1".toCharArray(), "secondpw1".toCharArray());

        var secondAttempt = service.resetPassword("ada@example.com", firstCode,
                "thirdpw1".toCharArray(), "thirdpw1".toCharArray());

        assertFalse(secondAttempt.ok());
    }

    // ── GitHub device flow ────────────────────────────────────────────────

    @Test
    void gitHubLoginReportsNotConfiguredWithoutAClientId(@TempDir Path dir) {
        System.clearProperty("GITHUB_OAUTH_CLIENT_ID");
        AccountService service = new AccountService(new AccountStore(dir));

        assertFalse(service.isGitHubConfigured());
        assertThrowsIllegalState(service::startGitHubLogin);
    }

    @Test
    void fullGitHubDeviceFlowCreatesAndSignsInANewAccount(@TempDir Path dir) throws Exception {
        System.setProperty("GITHUB_OAUTH_CLIENT_ID", "test-client-id");
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = fakeGitHub.getAddress().getPort();
        fakeGitHub.createContext("/device/code", exchange -> writeJson(exchange, 200, """
                {"device_code":"dc-1","user_code":"WXYZ-6789",
                 "verification_uri":"https://github.com/login/device","expires_in":900,"interval":1}"""));
        fakeGitHub.createContext("/token", exchange -> writeJson(exchange, 200,
                "{\"access_token\":\"gho_token123\",\"token_type\":\"bearer\"}"));
        fakeGitHub.createContext("/user", exchange -> writeJson(exchange, 200, """
                {"login":"octocat","name":"The Octocat","avatar_url":"https://avatars.example/octocat.png"}"""));
        // getAddress() must be resolved before start(); createContext binds paths ahead of start() too.
        fakeGitHub.start();

        GitHubDeviceAuthClient realClientAgainstFakeServer = new GitHubDeviceAuthClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + port + "/device/code"),
                URI.create("http://127.0.0.1:" + port + "/token"),
                URI.create("http://127.0.0.1:" + port + "/user"));
        AccountStore store = new AccountStore(dir);
        AccountService service = new AccountService(store, realClientAgainstFakeServer);

        var start = service.startGitHubLogin();
        assertEquals("WXYZ-6789", start.userCode());

        var poll = service.pollGitHubLogin(start.handle());

        assertInstanceOf(AccountService.GitHubPoll.Success.class, poll);
        Account account = ((AccountService.GitHubPoll.Success) poll).account();
        assertEquals("octocat", account.githubLogin());
        assertEquals("The Octocat", account.displayName());
        assertFalse(account.hasRecoveryCode());
        assertEquals(account.id(), service.currentAccount().orElseThrow().id());
        assertTrue(store.findByGitHubLogin("octocat").isPresent());
    }

    @Test
    void pollingAnUnknownHandleFails(@TempDir Path dir) {
        System.setProperty("GITHUB_OAUTH_CLIENT_ID", "test-client-id");
        AccountService service = new AccountService(new AccountStore(dir));

        var poll = service.pollGitHubLogin("never-started");

        assertInstanceOf(AccountService.GitHubPoll.Failed.class, poll);
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void assertThrowsIllegalState(org.junit.jupiter.api.function.Executable exec) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, exec);
    }
}
