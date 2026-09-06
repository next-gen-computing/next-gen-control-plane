package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.ClusterSummaryDto;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * {@code GET /api/cluster/stream} — live cluster-wide aggregates. A separate channel from {@code
 * /api/nodes/stream} rather than making the frontend re-derive healthy/warning/offline counts and
 * stale-excluded averages from the raw node list — that aggregation logic already exists once, in
 * {@code NodeMonitoringService.updateClusterSummary}, and duplicating it in JS would only invite drift.
 */
public class ClusterStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("cluster");

    public ClusterStreamHandler(NodeMonitoringService monitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(ClusterSummaryDto.from(monitoringService.getClusterSummary())),
                pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
