package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.profile.HistoryEntry;

public record HistoryEntryDto(
        String id,
        String kind,
        String typeLabel,
        String status,
        String clusterLabel,
        long submittedAtEpochMillis,
        long completedAtEpochMillis,
        String summary
) {
    public static HistoryEntryDto from(HistoryEntry entry) {
        return new HistoryEntryDto(
                entry.id(), entry.kind(), entry.typeLabel(), entry.status(), entry.clusterLabel(),
                entry.submittedAtEpochMillis(), entry.completedAtEpochMillis(), entry.summary());
    }
}
