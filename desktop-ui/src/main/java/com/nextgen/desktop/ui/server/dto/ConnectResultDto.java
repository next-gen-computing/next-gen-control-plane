package com.nextgen.desktop.ui.server.dto;

/** Success shape for {@code /api/role/server/launch} and {@code /api/role/node/join}. */
public record ConnectResultDto(boolean ok, String role, String serverId) {
}
