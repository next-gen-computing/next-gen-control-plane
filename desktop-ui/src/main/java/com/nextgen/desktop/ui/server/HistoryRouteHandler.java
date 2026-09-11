package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import com.nextgen.desktop.ui.server.dto.HistoryEntryDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/** {@code GET /api/history} — every remembered task/job submission, newest first, surviving app
 * restarts (unlike {@code TaskExecutionService}/{@code JobExecutionService}'s in-memory lists). */
public class HistoryRouteHandler implements HttpHandler {
    private final DesktopHistoryStore historyStore;

    public HistoryRouteHandler(DesktopHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        List<HistoryEntryDto> entries = historyStore.list().stream().map(HistoryEntryDto::from).toList();
        JsonSupport.sendJson(exchange, 200, entries);
    }
}
