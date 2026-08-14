package com.nextgen.desktop.ui.server.dto;

import com.nextgen.desktop.ui.service.ConnectionStateManager;

/** Wire shape for {@link ConnectionStateManager} — the same fields {@code ConnectionBanner} binds to. */
public record ConnectionStateDto(
        String state,
        String label,
        String colorHex,
        String detail,
        String lastSuccessDescription,
        boolean live
) {
    public static ConnectionStateDto from(ConnectionStateManager manager) {
        var state = manager.getState();
        return new ConnectionStateDto(
                state.name(),
                state.label(),
                state.colorHex(),
                manager.getDetail(),
                manager.lastSuccessDescriptionProperty().get(),
                manager.isLive()
        );
    }
}
