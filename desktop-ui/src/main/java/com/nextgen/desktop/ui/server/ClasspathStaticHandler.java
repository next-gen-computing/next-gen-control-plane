package com.nextgen.desktop.ui.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Serves the HTML/CSS/JS frontend from the classpath rather than the filesystem.
 *
 * <p>{@link com.nextgen.controlplane.StaticFileHandler} (the existing dashboard's equivalent) reads
 * from a filesystem directory, which works for that server because it runs unpacked, launched
 * directly from the repo. This server ships inside the same shaded jar as the rest of the desktop
 * app, so its resources are only ever available via the classpath — a filesystem base path would not
 * exist once packaged.
 */
public class ClasspathStaticHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ClasspathStaticHandler.class);

    /** Classpath root the handler is allowed to serve from, e.g. "/web". No trailing slash. */
    private final String classpathBase;

    public ClasspathStaticHandler(String classpathBase) {
        this.classpathBase = classpathBase;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }

        String resourcePath = resolveWithinBase(path);
        if (resourcePath == null) {
            LOG.warn("Rejected static request outside the served root: {}", path);
            send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            bytes = in.readAllBytes();
        }

        exchange.getResponseHeaders().set("Content-Type", contentTypeFor(resourcePath));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        send(exchange, 200, null, bytes);
    }

    /**
     * Resolves {@code requestPath} against {@link #classpathBase}, normalising away {@code .}/{@code
     * ..} segments, and returns {@code null} if the normalised result would escape the base — the
     * traversal guard the filesystem equivalent enforces by rejecting a literal {@code ".."}
     * substring. Normalising and re-checking the prefix (rather than only string-matching) also
     * catches traversal attempts hidden behind redundant separators or trailing segments.
     */
    private String resolveWithinBase(String requestPath) {
        String normalized = Path.of(classpathBase, requestPath).normalize().toString().replace('\\', '/');
        if (!normalized.equals(classpathBase) && !normalized.startsWith(classpathBase + "/")) {
            return null;
        }
        return normalized;
    }

    private static String contentTypeFor(String resourcePath) {
        String guessed = URLConnection.guessContentTypeFromName(resourcePath);
        if (guessed != null) {
            return guessed;
        }
        int dot = resourcePath.lastIndexOf('.');
        String ext = dot >= 0 ? resourcePath.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css";
            case "js", "mjs" -> "application/javascript";
            case "json" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            default -> "application/octet-stream";
        };
    }

    private static void send(HttpExchange exchange, int status, String contentTypeOrNull, byte[] body)
            throws IOException {
        if (contentTypeOrNull != null) {
            exchange.getResponseHeaders().set("Content-Type", contentTypeOrNull);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
