package com.nextgen.desktop.ui.server.dto;

import com.nextgen.proto.ControlPlaneProto;

/** One task/sub-task anywhere on the cluster, straight from {@code TaskRegistry} via {@code ListTasks}
 * — deliberately cluster-wide, unlike {@link TaskDto}, which only ever covers tasks this desktop-ui
 * instance personally submitted (see {@code ControlPlaneClient#listAllTasks} Javadoc). This is the real
 * data a Task-Manager-style view needs: every node's actual assigned work, not just this client's own. */
public record ClusterTaskDto(
        String taskId,
        String jobId,
        String kind,
        String assignedNodeId,
        String state,
        int attempt,
        String resultJson,
        String error,
        long createdAtEpochMillis,
        long dispatchedAtEpochMillis,
        long updatedAtEpochMillis
) {
    public static ClusterTaskDto from(ControlPlaneProto.TaskStatusResponse response) {
        return new ClusterTaskDto(
                response.getTaskId(),
                response.getJobId(),
                stripPrefix(response.getKind().name(), "TASK_KIND_"),
                response.getAssignedNode(),
                stripPrefix(response.getState().name(), "TASK_STATE_"),
                response.getAttempt(),
                response.getResultJson(),
                response.getError(),
                response.getCreatedAtEpochMillis(),
                response.getDispatchedAtEpochMillis(),
                response.getUpdatedAtEpochMillis()
        );
    }

    /** Raw proto enum {@code .name()} carries its enum's own prefix (e.g. {@code TASK_STATE_RUNNING})
     * — every other status field this app sends the frontend is the bare suffix (e.g. {@code RUNNING}),
     * matching {@code NodeMonitoringService.mapStatus}'s own "never leak the raw proto enum name"
     * discipline, since the frontend's status-to-color lookups key off the bare value. */
    private static String stripPrefix(String enumName, String prefix) {
        return enumName.startsWith(prefix) ? enumName.substring(prefix.length()) : enumName;
    }
}
