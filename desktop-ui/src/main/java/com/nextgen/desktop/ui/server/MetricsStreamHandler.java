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
 * {@code GET /api/metrics/stream} (SSE) — the latest sample per (node, metric), re-sent whenever any
 * of them changes. The frontend tracks the newest timestamp it has already plotted per (node, metric)
 * and appends only genuinely new points (via {@code Plotly.extendTraces}) — see {@code
 * plotly-timeseries.js}. Complements {@link MetricsHistoryRouteHandler}, which backfills everything
 * that came before a screen opened.
 */
public class MetricsStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("metrics");

    public MetricsStreamHandler(NodeMonitoringService monitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(latestSamples(monitoringService)), pollIntervalMs);
    }

    private static List<MetricSampleDto> latestSamples(NodeMonitoringService monitoringService) {
        MetricsHistory history = monitoringService.getMetricsHistory();
        List<MetricSampleDto> samples = new ArrayList<>();
        for (String nodeId : history.nodeIds()) {
            int slot = history.slotOf(nodeId);
            for (MetricsHistory.Metric metric : MetricsHistory.Metric.values()) {
                List<MetricsHistory.Sample> raw = history.rawSamples(nodeId, metric);
                if (raw.isEmpty()) {
                    continue;
                }
                MetricsHistory.Sample latest = raw.get(raw.size() - 1);
                samples.add(new MetricSampleDto(nodeId, metric.name(), latest.epochMillis(),
                        latest.available() ? latest.value() : null, slot));
            }
        }
        return samples;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
