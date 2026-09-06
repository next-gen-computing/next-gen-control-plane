package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

public record DockerControlResultDto(boolean ok, String message) {
    public static DockerControlResultDto from(ControlPlaneProto.DockerControlResult result) {
        return new DockerControlResultDto(result.getOk(), result.getMessage());
    }
}
