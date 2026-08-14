package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.model.NodeModel;

/**
 * Wire shape for one node. {@code cpuUsage}/{@code memoryUsage} are {@code null} when stale rather
 * than a substituted number — mirrors {@link NodeModel#getCpuUsageText()}'s "n/a" rule, just carried
 * as a real JSON {@code null} instead of pre-formatted text, so the frontend decides how to render it.
 */
public record NodeDto(
        String id,
        String name,
        String hostname,
        String ip,
        int port,
        String status,
        Double cpuUsage,
        boolean cpuStale,
        Double memoryUsage,
        boolean memoryStale,
        String lastHeartbeat,
        String failureProbability,
        String predictedLoad,
        String recommendation
) {
    public static NodeDto from(NodeModel node) {
        return new NodeDto(
                node.getId(),
                node.getName(),
                node.getHostname(),
                node.getIp(),
                node.getPort(),
                node.getStatus(),
                node.isCpuStale() ? null : node.getCpuUsage(),
                node.isCpuStale(),
                node.isMemoryStale() ? null : node.getMemoryUsage(),
                node.isMemoryStale(),
                node.getLastHeartbeat(),
                node.getFailureProbability(),
                node.getPredictedLoad(),
                node.getRecommendation()
        );
    }
}
