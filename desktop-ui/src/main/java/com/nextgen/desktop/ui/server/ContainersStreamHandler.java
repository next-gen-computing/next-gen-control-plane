package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/containers/stream} — every real container currently reported by any Docker-capable
 * node, live. */
public class ContainersStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("containers");

    public ContainersStreamHandler(DockerResourcesMonitoringService dockerResourcesMonitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(dockerResourcesMonitoringService.getContainers()), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
