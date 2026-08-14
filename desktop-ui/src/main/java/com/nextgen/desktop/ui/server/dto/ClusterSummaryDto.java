package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.model.ClusterSummary;

/**
 * Wire shape for cluster-wide stats. {@code avgCpuUsage}/{@code avgMemoryUsage}/{@code healthPercent}
 * are {@code null} when nothing is measurable, mirroring {@link ClusterSummary}'s negative-sentinel
 * convention — the frontend never sees a fabricated {@code 0}.
 */
public record ClusterSummaryDto(
        int totalNodes,
        int healthyNodes,
        int warningNodes,
        int offlineNodes,
        Double avgCpuUsage,
        Double avgMemoryUsage,
        Double healthPercent,
        int activeTasks,
        String lastUpdated
) {
    public static ClusterSummaryDto from(ClusterSummary summary) {
        return new ClusterSummaryDto(
                summary.getTotalNodes(),
                summary.getHealthyNodes(),
                summary.getWarningNodes(),
                summary.getOfflineNodes(),
                summary.hasCpuReading() ? summary.getAvgCpuUsage() : null,
                summary.hasMemoryReading() ? summary.getAvgMemoryUsage() : null,
                summary.hasHealthReading() ? summary.getHealthPercent() : null,
                summary.getActiveTasks(),
                summary.getLastUpdated()
        );
    }
}
