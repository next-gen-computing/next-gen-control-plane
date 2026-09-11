package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.ConnectionStateDto;
import com.nextgen.desktop.ui.service.ConnectionStateManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/connection/stream} — the same state {@code ConnectionBanner} binds to today. */
public class ConnectionStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("connection");

    public ConnectionStreamHandler(ConnectionStateManager connectionState, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(ConnectionStateDto.from(connectionState)), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
