package com.nextgen.desktop.ui.account;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link GitHubDeviceAuthClient} against a real local {@link HttpServer} standing in for
 * GitHub's three device-flow endpoints — matching this project's established preference for a real
 * in-process server over a mocked HTTP client, the same reasoning {@code ControlPlaneServiceImplTest}
 * and friends already apply to gRPC.
 */
class GitHubDeviceAuthClientTest {
    private HttpServer fakeGitHub;

    @AfterEach
    void stopFakeServer() {
        if (fakeGitHub != null) {
            fakeGitHub.stop(0);
        }
    }

    private GitHubDeviceAuthClient clientFor(HttpServer server) {
        int port = server.getAddress().getPort();
        return new GitHubDeviceAuthClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + port + "/device/code"),
                URI.create("http://127.0.0.1:" + port + "/token"),
                URI.create("http://127.0.0.1:" + port + "/user"));
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

    @Test
    void requestDeviceCodeParsesARealResponse() throws Exception {
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeGitHub.createContext("/device/code", exchange -> writeJson(exchange, 200, """
                {"device_code":"dc-1","user_code":"ABCD-1234",
                 "verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""));
        fakeGitHub.start();

        var deviceCode = clientFor(fakeGitHub).requestDeviceCode("client-123");

        assertEquals("dc-1", deviceCode.deviceCode());
        assertEquals("ABCD-1234", deviceCode.userCode());
        assertEquals("https://github.com/login/device", deviceCode.verificationUri());
        assertEquals(900, deviceCode.expiresInSeconds());
        assertEquals(5, deviceCode.intervalSeconds());
    }

    @Test
    void pollForTokenReportsPendingThenEventuallySucceeds() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeGitHub.createContext("/token", exchange -> {
            if (callCount.getAndIncrement() < 2) {
                writeJson(exchange, 200, "{\"error\":\"authorization_pending\"}");
            } else {
                writeJson(exchange, 200, "{\"access_token\":\"gho_realtoken\",\"token_type\":\"bearer\"}");
            }
        });
        fakeGitHub.start();
        GitHubDeviceAuthClient client = clientFor(fakeGitHub);

        assertInstanceOf(GitHubDeviceAuthClient.PollResult.Pending.class, client.pollForToken("id", "dc"));
        assertInstanceOf(GitHubDeviceAuthClient.PollResult.Pending.class, client.pollForToken("id", "dc"));
        var third = client.pollForToken("id", "dc");
        assertInstanceOf(GitHubDeviceAuthClient.PollResult.Success.class, third);
        assertEquals("gho_realtoken", ((GitHubDeviceAuthClient.PollResult.Success) third).accessToken());
    }

    @Test
    void pollForTokenReportsDeniedAndExpired() throws Exception {
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeGitHub.createContext("/token", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            // Distinguish which case by device_code in the POST body instead — read it back.
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("device_code=denied")) {
                writeJson(exchange, 200, "{\"error\":\"access_denied\"}");
            } else {
                writeJson(exchange, 200, "{\"error\":\"expired_token\"}");
            }
        });
        fakeGitHub.start();
        GitHubDeviceAuthClient client = clientFor(fakeGitHub);

        assertInstanceOf(GitHubDeviceAuthClient.PollResult.Denied.class, client.pollForToken("id", "denied"));
        assertInstanceOf(GitHubDeviceAuthClient.PollResult.Expired.class, client.pollForToken("id", "gone"));
    }

    @Test
    void fetchUserReadsTheRealProfileFields() throws Exception {
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeGitHub.createContext("/user", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer gho_realtoken".equals(auth)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, """
                    {"login":"octocat","name":"The Octocat","avatar_url":"https://avatars.example/octocat.png"}""");
        });
        fakeGitHub.start();

        var user = clientFor(fakeGitHub).fetchUser("gho_realtoken");

        assertEquals("octocat", user.login());
        assertEquals("The Octocat", user.name());
        assertEquals("https://avatars.example/octocat.png", user.avatarUrl());
    }

    @Test
    void fetchUserFallsBackToLoginWhenNameIsAbsent() throws Exception {
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeGitHub.createContext("/user", exchange -> writeJson(exchange, 200,
                "{\"login\":\"octocat\",\"avatar_url\":\"https://avatars.example/octocat.png\"}"));
        fakeGitHub.start();

        var user = clientFor(fakeGitHub).fetchUser("token");

        assertEquals("octocat", user.name());
    }

    @Test
    void anHttpErrorFromGitHubSurfacesAsAnIoException() throws Exception {
        fakeGitHub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeGitHub.createContext("/user", exchange -> writeJson(exchange, 403, "{\"message\":\"Bad credentials\"}"));
        fakeGitHub.start();
        GitHubDeviceAuthClient client = clientFor(fakeGitHub);

        assertTrue(assertThrows(java.io.IOException.class, () -> client.fetchUser("bad-token"))
                .getMessage().contains("403"));
    }
}
