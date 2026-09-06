package com.nextgen.desktop.ui.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP, a real {@code com.sun.net.httpserver.HttpServer}, and a real SSE connection — no faked
 * {@code HttpExchange} — matching this project's established "real over mocked" testing discipline
 * (the same reasoning {@link LocalUiServerTest} already documents for testing HTTP handlers this way).
 */
class SseChannelTest {

    private HttpServer httpServer;
    private SseChannel channel;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.stop();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /** Stage DD: before this fix, a persistently failing poll left an already-connected client's
     * response stream open forever with no new data and no error — the browser's {@code EventSource}
     * only auto-reconnects on an actual connection close/error, so it would silently stall. */
    @Test
    void aPersistentlyFailingPollClosesConnectedClientsSoTheyCanReconnect() throws Exception {
        channel = new SseChannel("test");
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/stream", channel::register);
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        AtomicInteger callCount = new AtomicInteger(0);
        // The first call succeeds (so a client can actually attach); every call after that fails —
        // proves the circuit breaker fires on a PERSISTENT failure, not a single hiccup.
        channel.startPolling(() -> {
            if (callCount.incrementAndGet() == 1) {
                return "{\"ok\":true}";
            }
            throw new RuntimeException("simulated control-plane outage");
        }, 20);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stream"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        boolean closed = false;
        try (var in = response.body()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            try {
                int b;
                do {
                    b = in.read();
                } while (b != -1 && System.nanoTime() < deadline);
                closed = (b == -1);
            } catch (IOException e) {
                // An abrupt reset is just as valid a signal that the server force-closed the
                // connection as a clean EOF — either way the client's EventSource sees a real error.
                closed = true;
            }
        }
        assertTrue(closed, "expected the server to close the connection after persistent poll failures");
    }

    @Test
    void anOccasionalFailureDoesNotDisconnectClients() throws Exception {
        channel = new SseChannel("test");
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/stream", channel::register);
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        AtomicInteger callCount = new AtomicInteger(0);
        // Fails every 2nd call — never reaches MAX_CONSECUTIVE_POLL_FAILURES in a row, since a success
        // resets the counter each time.
        channel.startPolling(() -> {
            int n = callCount.incrementAndGet();
            if (n % 2 == 0) {
                throw new RuntimeException("transient blip");
            }
            return "{\"tick\":" + n + "}";
        }, 15);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stream"))
                .timeout(Duration.ofSeconds(3))
                .GET().build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        // Real successful ticks keep broadcasting real SSE frames despite the interleaved failures —
        // reading several real bytes without ever hitting EOF (-1) proves the connection was never
        // force-closed by the circuit breaker (which only fires on FIVE CONSECUTIVE failures, and this
        // supplier never fails twice in a row).
        try (var in = response.body()) {
            for (int i = 0; i < 5; i++) {
                int b = in.read();
                assertTrue(b != -1, "connection unexpectedly closed after only occasional poll failures (byte " + i + ")");
            }
        }
    }
}
