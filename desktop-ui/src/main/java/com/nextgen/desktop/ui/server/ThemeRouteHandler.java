package com.nextgen.desktop.ui.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.nextgen.desktop.ui.server.dto.ThemeDto;
import com.nextgen.desktop.ui.service.ThemeService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import javafx.application.Platform;

import java.io.IOException;

/**
 * {@code GET/POST /api/theme} plus {@code GET /api/theme/stream}, all handled by one instance
 * registered at both paths (distinguished by the exact request path at dispatch time).
 */
public class ThemeRouteHandler implements HttpHandler {
    private final ThemeService themeService;
    private final SseChannel channel = new SseChannel("theme");

    public ThemeRouteHandler(ThemeService themeService, long pollIntervalMs) {
        this.themeService = themeService;
        channel.startPolling(() -> JsonSupport.toJson(new ThemeDto(themeService.isDarkMode())), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().endsWith("/stream")) {
            channel.register(exchange);
            return;
        }

        switch (exchange.getRequestMethod()) {
            case "GET" -> JsonSupport.sendJson(exchange, 200, new ThemeDto(themeService.isDarkMode()));
            case "POST" -> handleSetTheme(exchange);
            default -> {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        }
    }

    private void handleSetTheme(HttpExchange exchange) throws IOException {
        ThemeDto request;
        try {
            request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), ThemeDto.class);
        } catch (JsonProcessingException e) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        // ThemeService.setDarkMode fires a listener that mutates the JavaFX Scene's stylesheet list
        // directly (see ThemeService.applyTheme) — a scene-graph mutation, which JavaFX requires to
        // happen on its own application thread. This handler runs on a LocalUiServer worker thread,
        // so it must hand off rather than call setDarkMode() directly.
        Platform.runLater(() -> themeService.setDarkMode(request.dark()));

        JsonSupport.sendJson(exchange, 200, request);
    }

    void stop() {
        channel.stop();
    }
}
