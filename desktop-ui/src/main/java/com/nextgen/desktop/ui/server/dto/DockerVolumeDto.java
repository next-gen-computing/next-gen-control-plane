package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

public record DockerVolumeDto(
        String nodeId,
        String name,
        String driver,
        String mountpoint
) {
    public static DockerVolumeDto from(String nodeId, ControlPlaneProto.DockerVolumeInfo info) {
        return new DockerVolumeDto(nodeId, info.getName(), info.getDriver(), info.getMountpoint());
    }
}
