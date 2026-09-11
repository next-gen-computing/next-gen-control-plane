package com.nextgen.desktop.ui.server.dto;

/** POST body for {@code /api/feedback}. {@code contact} is optional — left blank if not given. */
public record FeedbackRequestDto(String category, String message, String contact) {
}
