package com.nextgen.desktop.ui.server.dto;

import java.util.List;

/**
 * One-shot snapshot for first paint — everything the frontend needs to render its first frame
 * without waiting on every SSE channel to deliver its first event. Every SSE channel this app
 * exposes carries the same shape as the corresponding field here, so a client can reuse one decoder.
 */
public record StateDto(
        String role,
        String serverId,
        boolean darkMode,
        ConnectionStateDto connection,
        ClusterSummaryDto cluster,
        List<NodeDto> nodes,
        List<TaskDto> tasks,
        List<JobDto> jobs,
        String connectionHost,
        int controlPlanePort,
        int predictorPort,
        long refreshIntervalMs
) {
}
