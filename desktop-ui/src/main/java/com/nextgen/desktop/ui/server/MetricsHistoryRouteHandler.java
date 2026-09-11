package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.MetricSampleDto;
import com.nextgen.desktop.ui.service.MetricsHistory;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/metrics/history} — a one-shot dump of every sample currently held for every node
 * and metric, including gaps, for backfilling a chart the moment a screen opens. {@code
 * /api/metrics/stream} then carries only new points from that moment on.
 */
public class MetricsHistoryRouteHandler implements HttpHandler {
    private final NodeMonitoringService monitoringService;

    public MetricsHistoryRouteHandler(NodeMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Stage DD: same missing-method-check fix as StateRouteHandler's own.
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        JsonSupport.sendJson(exchange, 200, snapshot(monitoringService));
    }

    static List<MetricSampleDto> snapshot(NodeMonitoringService monitoringService) {
        MetricsHistory history = monitoringService.getMetricsHistory();
        List<MetricSampleDto> samples = new ArrayList<>();
        for (String nodeId : history.nodeIds()) {
            int slot = history.slotOf(nodeId);
            for (MetricsHistory.Metric metric : MetricsHistory.Metric.values()) {
                for (MetricsHistory.Sample sample : history.rawSamples(nodeId, metric)) {
                    samples.add(new MetricSampleDto(nodeId, metric.name(), sample.epochMillis(),
                            sample.available() ? sample.value() : null, slot));
                }
            }
        }
        return samples;
    }
}
