package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

public record DockerImageDto(
        String nodeId,
        String imageId,
        String repository,
        String tag,
        long sizeBytes,
        long createdAtEpochMillis
) {
    public static DockerImageDto from(String nodeId, ControlPlaneProto.DockerImageInfo info) {
        return new DockerImageDto(
                nodeId,
                info.getImageId(),
                info.getRepository(),
                info.getTag(),
                info.getSizeBytes(),
                info.getCreatedAtEpochMillis()
        );
    }
}
