package com.nextgen.desktop.ui.server;

import com.nextgen.controlplane.EnvConfig;
import com.nextgen.desktop.ui.client.ErrorCategory;
import com.nextgen.desktop.ui.server.dto.ErrorDto;
import com.nextgen.desktop.ui.server.dto.FeedbackRequestDto;
import com.nextgen.desktop.ui.server.dto.FeedbackResultDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * {@code POST /api/feedback} — saves submitted feedback to a real local file. There is no live
 * support team or backend inbox behind this app, so the honest behaviour is a genuine local save the
 * user (or whoever administers this machine) can find and act on — not a fake "sent to our team"
 * confirmation for something that goes nowhere.
 *
 * <p>Mirrors {@code PkiPaths}'s directory convention: configurable via an env var, defaulting under
 * the user's home directory.
 */
public class FeedbackRouteHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FeedbackRouteHandler.class);
    private static final DateTimeFormatter FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        FeedbackRequestDto request;
        try {
            request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), FeedbackRequestDto.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Malformed feedback body"));
            return;
        }

        if (request.message() == null || request.message().isBlank()) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Feedback message is empty"));
            return;
        }

        try {
            Path saved = save(request);
            LOG.info("Feedback saved to {}", saved);
            JsonSupport.sendJson(exchange, 200, new FeedbackResultDto(true, saved.toString()));
        } catch (IOException e) {
            LOG.error("Could not save feedback", e);
            JsonSupport.sendJson(exchange, 500, ErrorDto.of(ErrorCategory.SERVER_ERROR,
                    "Could not save feedback locally: " + e.getMessage()));
        }
    }

    private Path save(FeedbackRequestDto request) throws IOException {
        Path dir = feedbackDir();
        Files.createDirectories(dir);

        String filename = FILENAME_TIME.format(java.time.LocalDateTime.now()) + ".txt";
        Path file = dir.resolve(filename);

        String category = request.category() == null || request.category().isBlank() ? "general" : request.category();
        String contact = request.contact() == null || request.contact().isBlank() ? "(not provided)" : request.contact();
        String content = "Timestamp: " + Instant.now() + "\n"
                + "Category: " + category + "\n"
                + "Contact: " + contact + "\n"
                + "---\n"
                + request.message() + "\n";

        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Path feedbackDir() {
        String configured = EnvConfig.stringValue("NEXTGEN_FEEDBACK_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "feedback").toString());
        return Paths.get(configured);
    }
}
