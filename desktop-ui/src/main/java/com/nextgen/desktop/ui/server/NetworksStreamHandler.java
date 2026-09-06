package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/networks/stream} — every real network currently reported by any Docker-capable
 * node, live. List-only in this stage — see the plan's Stage T scope cuts for network create/rm. */
public class NetworksStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("networks");

    public NetworksStreamHandler(DockerResourcesMonitoringService dockerResourcesMonitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(dockerResourcesMonitoringService.getNetworks()), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
