package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

/** One real container, as reported by the node that's actually running it — see
 * {@code DockerStateCollector}'s own Javadoc for how {@code nodeId} is attached alongside the fields
 * Docker itself reports (never synthesized here). {@code cpuPercent}/memory/network fields (Stage RR)
 * come from a separate {@code docker stats} collection and are simply proto-default 0 when that specific
 * collection failed or the container isn't running — never fabricated. */
public record DockerContainerDto(
        String nodeId,
        String containerId,
        String name,
        String image,
        String status,
        String stateText,
        java.util.List<String> ports,
        long createdAtEpochMillis,
        String command,
        float cpuPercent,
        long memoryUsageBytes,
        long memoryLimitBytes,
        float memoryPercent,
        long netRxBytes,
        long netTxBytes
) {
    public static DockerContainerDto from(String nodeId, ControlPlaneProto.DockerContainerInfo info) {
        return new DockerContainerDto(
                nodeId,
                info.getContainerId(),
                info.getName(),
                info.getImage(),
                info.getStatus(),
                info.getStateText(),
                info.getPortsList(),
                info.getCreatedAtEpochMillis(),
                info.getCommand(),
                info.getCpuPercent(),
                info.getMemoryUsageBytes(),
                info.getMemoryLimitBytes(),
                info.getMemoryPercent(),
                info.getNetRxBytes(),
                info.getNetTxBytes()
        );
    }
}
