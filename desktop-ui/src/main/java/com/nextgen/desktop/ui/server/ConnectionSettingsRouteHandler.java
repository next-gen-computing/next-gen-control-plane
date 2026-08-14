package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.client.ErrorCategory;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.server.dto.ConnectionApplyRequestDto;
import com.nextgen.desktop.ui.server.dto.ErrorDto;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * {@code POST /api/connection/apply} and {@code POST /api/connection/refresh-interval}, one handler
 * dispatching on path — ported from {@code SettingsView}'s Connection and Monitoring sections.
 */
public class ConnectionSettingsRouteHandler implements HttpHandler {
    private final GrpcConnectionManager connectionManager;
    private final NodeMonitoringService monitoringService;

    public ConnectionSettingsRouteHandler(GrpcConnectionManager connectionManager,
                                          NodeMonitoringService monitoringService) {
        this.connectionManager = connectionManager;
        this.monitoringService = monitoringService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        if (exchange.getRequestURI().getPath().endsWith("/refresh-interval")) {
            handleRefreshInterval(exchange);
        } else {
            handleApply(exchange);
        }
    }

    private void handleApply(HttpExchange exchange) throws IOException {
        ConnectionApplyRequestDto request =
                JsonSupport.MAPPER.readValue(exchange.getRequestBody(), ConnectionApplyRequestDto.class);

        String host = request.host() == null ? "" : request.host().trim();
        if (host.isEmpty()) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Host must not be empty"));
            return;
        }

        int cpPort;
        int predPort;
        try {
            cpPort = parsePort(request.controlPlanePort());
            predPort = parsePort(request.predictorPort());
        } catch (IllegalArgumentException e) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, e.getMessage()));
            return;
        }

        // connectTo rebuilds the channels immediately; the connection banner reports whether it
        // actually worked — this endpoint doesn't optimistically report success.
        connectionManager.connectTo(host, cpPort, predPort);
        JsonSupport.sendJson(exchange, 200, com.nextgen.desktop.ui.server.dto.OkResultDto.OK);
    }

    private void handleRefreshInterval(HttpExchange exchange) throws IOException {
        var body = JsonSupport.MAPPER.readTree(exchange.getRequestBody());
        long intervalMs = body.path("intervalMs").asLong(-1);
        if (intervalMs < 1000 || intervalMs > 10000) {
            JsonSupport.sendJson(exchange, 400,
                    ErrorDto.of(ErrorCategory.UNKNOWN, "intervalMs must be between 1000 and 10000"));
            return;
        }
        monitoringService.setRefreshInterval(intervalMs);
        JsonSupport.sendJson(exchange, 200, com.nextgen.desktop.ui.server.dto.OkResultDto.OK);
    }

    private static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid port number");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }
}
