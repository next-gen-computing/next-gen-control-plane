package com.nextgen.controlplane.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A real HTTP POST to an operator-configured URL — the concrete channel {@link AlertNotifier} ships
 * with (see that interface's Javadoc for why webhook was the one implemented). One JSON object per
 * alert, sent async and fire-and-forget so a slow/unreachable webhook endpoint can never block
 * {@code HeartbeatMonitor}/{@code RiskMonitor}'s own sweep thread — the same "a logging failure must
 * not take down detection" discipline {@code RiskOutcomeLogger} already applies to disk I/O, applied
 * here to network I/O instead.
 */
public final class WebhookAlertNotifier implements AlertNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookAlertNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI webhookUri;
    private final HttpClient httpClient;

    public WebhookAlertNotifier(String webhookUrl) {
        this(webhookUrl, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    /** @param httpClient injectable so a test can point this at a real local HTTP server without a
     * real outbound network call. */
    public WebhookAlertNotifier(String webhookUrl, HttpClient httpClient) {
        this.webhookUri = URI.create(webhookUrl);
        this.httpClient = httpClient;
    }

    @Override
    public void notifyNodeDown(String nodeId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "node_down");
        payload.put("nodeId", nodeId);
        payload.put("reason", reason);
        payload.put("timestampEpochMillis", System.currentTimeMillis());
        send(payload);
    }

    @Override
    public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "node_at_risk");
        payload.put("nodeId", nodeId);
        payload.put("riskScore", riskScore);
        payload.put("reason", reason);
        payload.put("timestampEpochMillis", System.currentTimeMillis());
        send(payload);
    }

    private void send(Map<String, Object> payload) {
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // Every value here is a plain Map<String, Object> of primitives/strings — cannot actually
            // fail to serialize, but never let a webhook problem propagate regardless.
            LOG.warn("Could not serialise alert payload: {}", e.getMessage());
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        // Fire-and-forget: sendAsync never blocks the calling monitor thread, and the exceptionally()
        // handler swallows any failure (unreachable host, timeout, non-2xx) into a log line rather than
        // letting it surface anywhere that could affect liveness/risk detection.
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() / 100 != 2) {
                        LOG.warn("Alert webhook {} returned HTTP {}", webhookUri, response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    LOG.warn("Could not deliver alert to webhook {}: {}", webhookUri, e.getMessage());
                    return null;
                });
    }
}
