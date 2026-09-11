package com.nextgen.desktop.ui.server.dto;

/** Where the feedback actually landed — shown back to the user rather than a generic "thanks". */
public record FeedbackResultDto(boolean ok, String savedTo) {
}
