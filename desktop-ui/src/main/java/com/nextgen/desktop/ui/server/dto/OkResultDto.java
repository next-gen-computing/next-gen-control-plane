package com.nextgen.desktop.ui.server.dto;

/** A bare success acknowledgement for action routes with nothing more specific to return. */
public record OkResultDto(boolean ok) {
    public static final OkResultDto OK = new OkResultDto(true);
}
