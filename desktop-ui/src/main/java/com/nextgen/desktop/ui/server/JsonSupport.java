package com.nextgen.desktop.ui.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shared Jackson mapper and small response-writing helpers for every route handler in this package. */
final class JsonSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Every value passed through this method is one of this package's own DTO records —
            // a serialization failure here is a programming error, not a runtime condition to
            // recover from.
            throw new IllegalStateException("Failed to serialise " + value.getClass(), e);
        }
    }

    static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
