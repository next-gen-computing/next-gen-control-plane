package com.nextgen.desktop.ui.server.dto;

/** What the server-setup screen needs to honestly offer (or grey out) Docker launch mode — backed by a
 * real {@code DockerCapabilityDetector} check (CLI presence AND daemon reachability), never guessed. */
public record DockerInfoDto(boolean available, String dockerVersion, String composeVersion) {
}
