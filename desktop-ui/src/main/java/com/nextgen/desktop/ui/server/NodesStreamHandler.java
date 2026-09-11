package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.NodeDto;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/** {@code GET /api/nodes/stream} — every node, pushed whenever the polled snapshot changes. */
public class NodesStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("nodes");

    public NodesStreamHandler(NodeMonitoringService monitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(snapshot(monitoringService)), pollIntervalMs);
    }

    static List<NodeDto> snapshot(NodeMonitoringService monitoringService) {
        return monitoringService.getNodes().stream().map(NodeDto::from).toList();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
