package com.nextgen.desktop.ui.server.dto;

/** POST body for {@code /api/role/server/launch}. {@code mode} is {@code "native"} (embedded, in this
 * JVM — the pre-existing behavior) or {@code "docker"} ({@code docker compose up} the project's own
 * {@code docker-compose.yml}); null/blank/missing defaults to {@code "native"}, so an older frontend
 * build posting no body at all keeps working unchanged. */
public record ServerLaunchRequestDto(String mode) {
    public boolean isDockerMode() {
        return "docker".equalsIgnoreCase(mode);
    }
}
