package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/images/stream} — every real image currently reported by any Docker-capable node,
 * live. List-only in this stage — see the plan's Stage T scope cuts for image pull/rm/tag. */
public class ImagesStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("images");

    public ImagesStreamHandler(DockerResourcesMonitoringService dockerResourcesMonitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(dockerResourcesMonitoringService.getImages()), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
