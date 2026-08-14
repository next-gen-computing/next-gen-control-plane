package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.model.TaskModel;

public record TaskDto(
        String id,
        String type,
        String typeDisplayName,
        String status,
        String payload,
        String result,
        String assignedNode,
        double progress,
        String createdAt,
        String completedAt
) {
    public static TaskDto from(TaskModel task) {
        return new TaskDto(
                task.getId(),
                task.getType().name(),
                task.getTypeDisplayName(),
                task.getStatus().name(),
                task.getPayload(),
                task.getResult(),
                task.getAssignedNode(),
                task.getProgress(),
                task.getCreatedAt(),
                task.getCompletedAt()
        );
    }
}
