package com.nextgen.desktop.ui.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A real client for GitHub's OAuth <a href="https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow">
 * device authorization flow</a> — the same mechanism the GitHub CLI ({@code gh auth login}) uses, and
 * the only OAuth flow that suits a distributed desktop binary honestly: no client secret has to be
 * embedded in the jar (a secret shipped inside something anyone can decompile is not actually secret),
 * and no localhost redirect listener or custom URI scheme is needed either. The trade-off is a short
 * user-facing step: type an 8-character code into a browser tab this app opens for you.
 *
 * <p>Uses the JDK's own {@link HttpClient} — no new dependency. Every endpoint is a constructor
 * parameter (defaulted to the real GitHub URLs by the no-arg constructor) specifically so tests can
 * point this at a fake local server instead of mocking this class away.
 */
public class GitHubDeviceAuthClient {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubDeviceAuthClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    /** Read-only profile info is all this needs — never requests write/repo access. */
    public static final String SCOPE = "read:user";

    private final HttpClient http;
    private final URI deviceCodeUri;
    private final URI tokenUri;
    private final URI userApiUri;

    public GitHubDeviceAuthClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://github.com/login/device/code"),
                URI.create("https://github.com/login/oauth/access_token"),
                URI.create("https://api.github.com/user"));
    }

    GitHubDeviceAuthClient(HttpClient http, URI deviceCodeUri, URI tokenUri, URI userApiUri) {
        this.http = http;
        this.deviceCodeUri = deviceCodeUri;
        this.tokenUri = tokenUri;
        this.userApiUri = userApiUri;
    }

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                              int expiresInSeconds, int intervalSeconds) {
    }

    public sealed interface PollResult {
        record Pending() implements PollResult {
        }

        record SlowDown(int newIntervalSeconds) implements PollResult {
        }

        record Success(String accessToken) implements PollResult {
        }

        record Denied() implements PollResult {
        }

        record Expired() implements PollResult {
        }
    }

    public record GitHubUser(String login, String name, String avatarUrl) {
    }

    /** Step 1: ask GitHub for a device code + the code the user needs to enter. */
    public DeviceCode requestDeviceCode(String clientId) throws IOException, InterruptedException {
        String form = formEncode(Map.of("client_id", clientId, "scope", SCOPE));
        HttpRequest request = HttpRequest.newBuilder(deviceCodeUri)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = parseOrThrow(response, "requesting a device code");
        return new DeviceCode(
                text(body, "device_code"), text(body, "user_code"), text(body, "verification_uri"),
                body.path("expires_in").asInt(900), body.path("interval").asInt(5));
    }

    /** Step 2 (called repeatedly on {@code intervalSeconds}): has the user approved yet? */
    public PollResult pollForToken(String clientId, String deviceCode) throws IOException, InterruptedException {
        String form = formEncode(Map.of(
                "client_id", clientId, "device_code", deviceCode, "grant_type", DEVICE_CODE_GRANT_TYPE));
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("GitHub returned an unparseable response while polling for a token", e);
        }

        if (body.hasNonNull("access_token")) {
            return new PollResult.Success(body.get("access_token").asText());
        }
        String error = body.path("error").asText();
        return switch (error) {
            case "authorization_pending" -> new PollResult.Pending();
            case "slow_down" -> new PollResult.SlowDown(body.path("interval").asInt(5) + 5);
            case "expired_token" -> new PollResult.Expired();
            case "access_denied" -> new PollResult.Denied();
            default -> throw new IOException("GitHub device-flow poll failed: "
                    + (error.isEmpty() ? response.body() : error));
        };
    }

    /** Step 3: read the profile the user just proved they own. */
    public GitHubUser fetchUser(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(userApiUri)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = parseOrThrow(response, "fetching the GitHub profile");
        String login = text(body, "login");
        String name = body.hasNonNull("name") ? body.get("name").asText() : login;
        String avatarUrl = text(body, "avatar_url");
        return new GitHubUser(login, name, avatarUrl);
    }

    private static JsonNode parseOrThrow(HttpResponse<String> response, String action) throws IOException {
        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("GitHub returned an unparseable response while " + action, e);
        }
        if (response.statusCode() / 100 != 2) {
            LOG.warn("GitHub API error while {}: HTTP {} — {}", action, response.statusCode(), response.body());
            throw new IOException("GitHub API error while " + action + " (HTTP " + response.statusCode() + ")");
        }
        return body;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String formEncode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
