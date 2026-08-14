package com.nextgen.desktop.ui.server.dto;

/** POST body for {@code /api/tasks} — a prime-count-range task over the half-open {@code [rangeStart, rangeEnd)}. */
public record TaskSubmitRequestDto(long rangeStart, long rangeEnd) {
}
