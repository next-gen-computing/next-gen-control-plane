package com.nextgen.desktop.ui.server.dto;

/**
 * POST body for {@code /api/tasks} — a prime-count-range task over the half-open
 * {@code [rangeStart, rangeEnd)}.
 *
 * <p>Stage DD: boxed {@code Long}, deliberately not primitive {@code long} — {@link
 * JobSubmitRequestDto} has the identical primitive gap, but it's saved by its own separate {@code
 * subTaskCount < 1} check; this record has no such second field, so a primitive default of 0 for a
 * missing field would let a {@code {}} body silently deserialize to the valid-looking range
 * {@code [0, 0)} and pass the only check ({@code rangeEnd >= rangeStart}) instead of being rejected as
 * the missing input it actually is. Boxed fields let {@link TasksRouteHandler} tell "genuinely 0" apart
 * from "never provided."
 */
public record TaskSubmitRequestDto(Long rangeStart, Long rangeEnd) {
}
