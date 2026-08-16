package com.nextgen.desktop.ui.server;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A Server-Sent-Events channel: registers long-lived {@link HttpExchange}s and pushes JSON frames to
 * all of them whenever the underlying data actually changes.
 *
 * <p>Deliberately polls its data source (via {@link #startPolling}) rather than attaching JavaFX
 * property listeners directly. The services this channel fronts ({@code NodeMonitoringService},
 * {@code ConnectionStateManager}, ...) mutate their state on the JavaFX application thread — a
 * listener firing there would either block it on slow client I/O or need its own hand-off machinery.
 * A short poll interval on a dedicated thread, diffing against the last payload sent, gets the same
 * near-real-time behaviour without touching the FX thread at all, and needs no new API surface on
 * classes that already have real test coverage.
 *
 * <p>{@code com.sun.net.httpserver} has no native SSE support: the idiom is to send headers with a
 * zero content length (which selects chunked encoding), keep the {@link HttpExchange}'s response
 * stream open past the point the handler method returns, and write {@code data: ...\n\n} frames to it
 * until the client disconnects (detected as an {@link IOException} on write).
 */
public class SseChannel {
    private static final Logger LOG = LoggerFactory.getLogger(SseChannel.class);

    /** Stage DD: after this many consecutive poll failures, already-connected clients are force-closed
     * so their browser's {@code EventSource} sees a real connection error and uses its own built-in
     * reconnect/backoff — a persistently failing poll otherwise left clients silently stale forever
     * (no error frame, no close; "no new data" is indistinguishable from "channel still healthy"). */
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 5;

    private final CopyOnWriteArrayList<HttpExchange> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger consecutivePollFailures = new AtomicInteger(0);
    private volatile String lastPayload;

    public SseChannel(String name) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    /** Registers a new client. Sends the most recent payload immediately if one exists. */
    public void register(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        clients.add(exchange);

        String current = lastPayload;
        if (current != null) {
            writeTo(exchange, current);
        }
    }

    /** Starts polling {@code payloadSupplier} every {@code intervalMs}, broadcasting only on change. */
    public void startPolling(Supplier<String> payloadSupplier, long intervalMs) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String payload = payloadSupplier.get();
                consecutivePollFailures.set(0);
                if (!payload.equals(lastPayload)) {
                    lastPayload = payload;
                    broadcast(payload);
                }
            } catch (RuntimeException e) {
                LOG.warn("SSE payload supplier failed", e);
                if (consecutivePollFailures.incrementAndGet() >= MAX_CONSECUTIVE_POLL_FAILURES
                        && !clients.isEmpty()) {
                    LOG.warn("SSE payload supplier failed {} times in a row — closing {} client(s) so "
                            + "their EventSource reconnects", consecutivePollFailures.get(), clients.size());
                    disconnectAll();
                    consecutivePollFailures.set(0);
                }
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void broadcast(String payload) {
        for (HttpExchange exchange : clients) {
            try {
                writeTo(exchange, payload);
            } catch (IOException e) {
                disconnect(exchange);
            }
        }
    }

    private void writeTo(HttpExchange exchange, String payload) throws IOException {
        OutputStream out = exchange.getResponseBody();
        out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void disconnect(HttpExchange exchange) {
        clients.remove(exchange);
        exchange.close();
    }

    private void disconnectAll() {
        for (HttpExchange exchange : clients) {
            disconnect(exchange);
        }
    }

    /** Stops polling and closes every open client connection. Called from {@code LocalUiServer.stop()}. */
    public void stop() {
        scheduler.shutdownNow();
        for (HttpExchange exchange : clients) {
            exchange.close();
        }
        clients.clear();
    }
}
