package com.nextgen.controlplane.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP: a real {@code com.sun.net.httpserver.HttpServer} receiving a real POST from a real
 * {@code WebhookAlertNotifier} — no mocked HTTP client, matching this project's established "real over
 * mocked" discipline (see {@code SseChannelTest}'s identical reasoning).
 */
class WebhookAlertNotifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LinkedBlockingQueue<JsonNode> startCapturingServer() throws Exception {
        LinkedBlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                received.add(MAPPER.readTree(body));
            } catch (Exception ignored) {
                // Malformed body would fail the test's own assertion below via a missing/null poll.
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return received;
    }

    private String webhookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    @Test
    void notifyNodeDownPostsARealJsonPayloadToTheConfiguredWebhook() throws Exception {
        LinkedBlockingQueue<JsonNode> received = startCapturingServer();
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(webhookUrl());

        notifier.notifyNodeDown("node-7", "no heartbeat for over 6000ms");

        JsonNode payload = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(payload, "the webhook server never received a real POST");
        assertEquals("node_down", payload.path("event").asText());
        assertEquals("node-7", payload.path("nodeId").asText());
        assertEquals("no heartbeat for over 6000ms", payload.path("reason").asText());
        assertTrue(payload.path("timestampEpochMillis").asLong() > 0);
    }

    @Test
    void notifyNodeAtRiskPostsARealJsonPayloadIncludingTheRiskScore() throws Exception {
        LinkedBlockingQueue<JsonNode> received = startCapturingServer();
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(webhookUrl());

        notifier.notifyNodeAtRisk("node-3", 0.87, "rising RTT; low battery");

        JsonNode payload = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(payload, "the webhook server never received a real POST");
        assertEquals("node_at_risk", payload.path("event").asText());
        assertEquals("node-3", payload.path("nodeId").asText());
        assertEquals(0.87, payload.path("riskScore").asDouble(), 0.0001);
        assertEquals("rising RTT; low battery", payload.path("reason").asText());
    }

    /** The whole point of fire-and-forget: an unreachable webhook target must never throw back into
     * the caller (a monitor's sweep thread), matching {@code RiskOutcomeLogger}'s identical "a logging
     * failure must not take down detection" discipline applied to network I/O instead of disk I/O. */
    @Test
    void anUnreachableWebhookNeverThrowsBackToTheCaller() {
        // Port 1 is a reserved, always-refused port on loopback — a fast, reliable "nothing there".
        WebhookAlertNotifier notifier = new WebhookAlertNotifier("http://127.0.0.1:1/webhook");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> notifier.notifyNodeDown("node-x", "unreachable webhook test"));
    }
}
