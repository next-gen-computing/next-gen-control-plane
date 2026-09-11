package com.nextgen.desktop.ui.server.dto;

/**
 * One reading. {@code v} is {@code null} for a gap (unavailable), never a substituted zero — mirrors
 * {@link com.nextgen.desktop.ui.service.MetricsHistory.Sample}. Plotly renders a {@code null} y-value
 * as a genuine break in the line by default, so this null-for-gap convention is what makes outages
 * render as gaps on the frontend with no further translation needed.
 */
public record MetricSampleDto(String nodeId, String metric, long t, Double v, int slot) {
}
