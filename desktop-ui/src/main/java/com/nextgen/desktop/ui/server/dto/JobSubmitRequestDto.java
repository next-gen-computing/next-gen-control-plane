package com.nextgen.desktop.ui.server.dto;

/** POST body for {@code /api/jobs} — split {@code [rangeStart, rangeEnd)} into {@code subTaskCount} sub-tasks. */
public record JobSubmitRequestDto(long rangeStart, long rangeEnd, int subTaskCount) {
}
