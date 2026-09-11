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
        // Stage DD: a literal JSON `null` body parses successfully to a null request without throwing
        // — previously this fell through to Platform.runLater(() -> ... request.dark()), which NPEs
        // ASYNCHRONOUSLY on the JavaFX thread, invisible to this HTTP exchange, while the handler still
        // returned 200 (JsonSupport.sendJson serializes a null value to the literal string "null"
        // without throwing) — telling the caller the theme was set when it never was.
        if (request == null) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        // darkMode is a JavaFX BooleanProperty, which by convention must only be touched from the FX
        // Application Thread. This handler runs on a LocalUiServer worker thread, so it hands off
        // rather than calling setDarkMode() directly.
        Platform.runLater(() -> themeService.setDarkMode(request.dark()));

        JsonSupport.sendJson(exchange, 200, request);
    }

    void stop() {
        channel.stop();
    }
}
