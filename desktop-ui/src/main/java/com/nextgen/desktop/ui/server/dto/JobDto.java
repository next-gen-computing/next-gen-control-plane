package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.model.JobModel;

public record JobDto(
        String id,
        long rangeStart,
        long rangeEnd,
        String status,
        String statusDisplayName,
        int subTaskCount,
        int completedCount,
        int failedCount,
        String combinedResult,
        double progress,
        String createdAt,
        String updatedAt
) {
    public static JobDto from(JobModel job) {
        return new JobDto(
                job.getId(),
                job.getRangeStart(),
                job.getRangeEnd(),
                job.getStatus().name(),
                job.getStatusDisplayName(),
                job.getSubTaskCount(),
                job.getCompletedCount(),
                job.getFailedCount(),
                job.getCombinedResult(),
                job.getProgress(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
