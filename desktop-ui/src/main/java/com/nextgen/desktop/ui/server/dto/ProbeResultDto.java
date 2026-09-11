package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.client.ConnectionDiagnostics;

/** Result of a pre-connection reachability probe — wraps {@link ConnectionDiagnostics.Result}. */
public record ProbeResultDto(boolean reachable, String category, String glyph, String message) {
    public static ProbeResultDto from(ConnectionDiagnostics.Result result) {
        if (result.reachable()) {
            return new ProbeResultDto(true, null, null, null);
        }
        return new ProbeResultDto(false, result.category().name(), result.category().glyph(), result.message());
    }
}
