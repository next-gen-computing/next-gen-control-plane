package com.nextgen.desktop.ui.server.dto;

/** What the server-setup screen needs before the user clicks "Launch": all real, all pre-launch. */
public record ServerInfoDto(String lanIp, int grpcPort, boolean tlsEnabled, String serverId) {
}
