package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

public record DockerNetworkDto(
        String nodeId,
        String networkId,
        String name,
        String driver,
        String scope
) {
    public static DockerNetworkDto from(String nodeId, ControlPlaneProto.DockerNetworkInfo info) {
        return new DockerNetworkDto(nodeId, info.getNetworkId(), info.getName(), info.getDriver(), info.getScope());
    }
}
