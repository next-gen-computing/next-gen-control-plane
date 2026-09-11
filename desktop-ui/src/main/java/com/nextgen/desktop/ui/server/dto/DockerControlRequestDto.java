package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

/** POST body for {@code /api/containers/action}. {@code action} is one of
 * {@code START/STOP/RESTART/REMOVE} (case-insensitive). */
public record DockerControlRequestDto(String nodeId, String containerId, String action) {
    /** @return the matching proto action, or {@code null} if {@code action} doesn't name a real one —
     * the caller must treat that as a client error, never silently default to a guessed action. */
    public ControlPlaneProto.DockerControlAction toProtoAction() {
        if (action == null) {
            return null;
        }
        return switch (action.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "START" -> ControlPlaneProto.DockerControlAction.DOCKER_CONTROL_ACTION_START;
            case "STOP" -> ControlPlaneProto.DockerControlAction.DOCKER_CONTROL_ACTION_STOP;
            case "RESTART" -> ControlPlaneProto.DockerControlAction.DOCKER_CONTROL_ACTION_RESTART;
            case "REMOVE", "RM" -> ControlPlaneProto.DockerControlAction.DOCKER_CONTROL_ACTION_REMOVE;
            default -> null;
        };
    }
}
