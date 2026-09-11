package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/volumes/stream} — every real volume currently reported by any Docker-capable node,
 * live. List-only in this stage — see the plan's Stage T scope cuts for volume create/rm. */
public class VolumesStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("volumes");

    public VolumesStreamHandler(DockerResourcesMonitoringService dockerResourcesMonitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(dockerResourcesMonitoringService.getVolumes()), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
