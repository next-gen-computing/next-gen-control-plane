package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.account.Account;
import com.nextgen.desktop.ui.account.AccountService;
import com.nextgen.desktop.ui.server.dto.AccountDto;
import com.nextgen.desktop.ui.server.dto.AccountListDto;
import com.nextgen.desktop.ui.server.dto.AccountResultDto;
import com.nextgen.desktop.ui.server.dto.GitHubPollDto;
import com.nextgen.desktop.ui.server.dto.GitHubStartDto;
import com.nextgen.desktop.ui.server.dto.LoginRequestDto;
import com.nextgen.desktop.ui.server.dto.OkResultDto;
import com.nextgen.desktop.ui.server.dto.RecoveryCodeResultDto;
import com.nextgen.desktop.ui.server.dto.ResetPasswordRequestDto;
import com.nextgen.desktop.ui.server.dto.SignUpRequestDto;
import com.nextgen.desktop.ui.server.dto.SwitchAccountRequestDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * {@code /api/account/*} — local device account signup/login/logout/switch, plus the GitHub
 * OAuth device-flow start/poll pair. See {@link com.nextgen.desktop.ui.account.Account}'s Javadoc for
 * exactly what "account" means here (device-local, no cloud sync) before assuming this is more than
 * it is.
 */
public class AccountRouteHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AccountRouteHandler.class);

    private final AccountService accountService;

    public AccountRouteHandler(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (path.endsWith("/current") && "GET".equals(method)) {
                handleCurrent(exchange);
            } else if (path.endsWith("/list") && "GET".equals(method)) {
                handleList(exchange);
            } else if (path.endsWith("/signup") && "POST".equals(method)) {
                handleSignUp(exchange);
            } else if (path.endsWith("/login") && "POST".equals(method)) {
                handleLogin(exchange);
            } else if (path.endsWith("/logout") && "POST".equals(method)) {
                handleLogout(exchange);
            } else if (path.endsWith("/switch") && "POST".equals(method)) {
                handleSwitch(exchange);
            } else if (path.endsWith("/reset-password") && "POST".equals(method)) {
                handleResetPassword(exchange);
            } else if (path.endsWith("/github/available") && "GET".equals(method)) {
                handleGitHubAvailable(exchange);
            } else if (path.endsWith("/github/start") && "POST".equals(method)) {
                handleGitHubStart(exchange);
            } else if (path.endsWith("/github/poll") && "GET".equals(method)) {
                handleGitHubPoll(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (RuntimeException e) {
            LOG.error("Unhandled failure in account route {}", path, e);
            JsonSupport.sendJson(exchange, 500, AccountResultDto.error("Internal error"));
        }
    }

    private void handleCurrent(HttpExchange exchange) throws IOException {
        JsonSupport.sendJson(exchange, 200,
                accountService.currentAccount().map(AccountDto::from).orElse(AccountDto.absent()));
    }

    private void handleList(HttpExchange exchange) throws IOException {
        JsonSupport.sendJson(exchange, 200, AccountListDto.from(accountService.listAccounts()));
    }

    private void handleSignUp(HttpExchange exchange) throws IOException {
        SignUpRequestDto request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), SignUpRequestDto.class);
        char[] password = request.password() == null ? new char[0] : request.password().toCharArray();
        char[] confirmPassword = request.confirmPassword() == null ? new char[0] : request.confirmPassword().toCharArray();
        AccountService.RecoveryCodeResult result =
                accountService.signUp(request.email(), password, confirmPassword, request.displayName());
        writeRecoveryCodeResult(exchange, result);
    }

    private void handleResetPassword(HttpExchange exchange) throws IOException {
        ResetPasswordRequestDto request =
                JsonSupport.MAPPER.readValue(exchange.getRequestBody(), ResetPasswordRequestDto.class);
        char[] newPassword = request.newPassword() == null ? new char[0] : request.newPassword().toCharArray();
        char[] confirmPassword = request.confirmPassword() == null ? new char[0] : request.confirmPassword().toCharArray();
        AccountService.RecoveryCodeResult result = accountService.resetPassword(
                request.email(), request.recoveryCode(), newPassword, confirmPassword);
        writeRecoveryCodeResult(exchange, result);
    }

    private void writeRecoveryCodeResult(HttpExchange exchange, AccountService.RecoveryCodeResult result)
            throws IOException {
        JsonSupport.sendJson(exchange, result.ok() ? 200 : 400, RecoveryCodeResultDto.from(result));
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        LoginRequestDto request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), LoginRequestDto.class);
        char[] password = request.password() == null ? new char[0] : request.password().toCharArray();
        AccountService.Result result = accountService.login(request.email(), password);
        writeResult(exchange, result);
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        accountService.logout();
        JsonSupport.sendJson(exchange, 200, OkResultDto.OK);
    }

    private void handleSwitch(HttpExchange exchange) throws IOException {
        SwitchAccountRequestDto request =
                JsonSupport.MAPPER.readValue(exchange.getRequestBody(), SwitchAccountRequestDto.class);
        var target = accountService.listAccounts().stream()
                .filter(a -> a.id().equals(request.accountId()))
                .findFirst();
        if (target.isEmpty()) {
            writeResult(exchange, AccountService.Result.error("No such account on this device"));
            return;
        }
        Account account = target.get();
        AccountService.Result result = account.isGitHubAccount()
                ? accountService.switchToGitHubAccount(account.id())
                : accountService.switchToPasswordAccount(account.id(),
                        request.password() == null ? new char[0] : request.password().toCharArray());
        writeResult(exchange, result);
    }

    private void writeResult(HttpExchange exchange, AccountService.Result result) throws IOException {
        if (result.ok()) {
            JsonSupport.sendJson(exchange, 200, AccountResultDto.ok(result.account()));
        } else {
            JsonSupport.sendJson(exchange, 400, AccountResultDto.error(result.errorMessage()));
        }
    }

    // ── GitHub device flow ───────────────────────────────────────────────

    private void handleGitHubAvailable(HttpExchange exchange) throws IOException {
        JsonSupport.sendJson(exchange, 200, Map.of("available", accountService.isGitHubConfigured()));
    }

    private void handleGitHubStart(HttpExchange exchange) throws IOException {
        try {
            AccountService.GitHubStart start = accountService.startGitHubLogin();
            JsonSupport.sendJson(exchange, 200, GitHubStartDto.from(start));
        } catch (IllegalStateException e) {
            JsonSupport.sendJson(exchange, 400, AccountResultDto.error(e.getMessage()));
        } catch (IOException e) {
            LOG.warn("Could not start GitHub login: {}", e.getMessage());
            JsonSupport.sendJson(exchange, 502,
                    AccountResultDto.error("Could not reach GitHub: " + e.getMessage()));
        }
    }

    private void handleGitHubPoll(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String handle = query.getOrDefault("handle", "");
        AccountService.GitHubPoll result = accountService.pollGitHubLogin(handle);
        GitHubPollDto dto = switch (result) {
            case AccountService.GitHubPoll.Pending pending -> GitHubPollDto.pending(pending.intervalSeconds());
            case AccountService.GitHubPoll.Success success -> GitHubPollDto.success(AccountDto.from(success.account()));
            case AccountService.GitHubPoll.Failed failed -> GitHubPollDto.failed(failed.reason());
        };
        JsonSupport.sendJson(exchange, 200, dto);
    }

    private static Map<String, String> parseQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new java.util.HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}
