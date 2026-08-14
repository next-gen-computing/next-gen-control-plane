package com.nextgen.desktop.ui.server.dto;

public record ConnectionApplyRequestDto(String host, String controlPlanePort, String predictorPort) {
}
