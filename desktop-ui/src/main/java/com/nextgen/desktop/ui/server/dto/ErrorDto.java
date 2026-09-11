package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.client.ErrorCategory;

/**
 * Uniform failure envelope for every action route. Built from the same {@link ErrorCategory} the
 * JavaFX screens already classify failures into, so the frontend's "404 / network / overload /
 * authentication / server error" handling is one rendering function fed by one shape, not five
 * ad-hoc error paths.
 */
public record ErrorDto(boolean ok, String category, String glyph, String label, String message) {
    public static ErrorDto of(ErrorCategory category, String message) {
        return new ErrorDto(false, category.name(), category.glyph(), category.label(), message);
    }
}
