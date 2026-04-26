package com.nextgen.controlplane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HTTP handler that serves static dashboard files (HTML, CSS, JS).
 * Production-grade solution for physical node deployment without nginx.
 */
public class StaticFileHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StaticFileHandler.class);

    private final String basePath;

    public StaticFileHandler(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        // Default to overview.html for root
        if ("/".equals(path) || path.isEmpty()) {
            path = "/overview.html";
        }

        // Security: prevent directory traversal
        if (path.contains("..") || path.contains("~")) {
            send404(exchange);
            return;
        }

        // Map URLs to file paths
        Path filePath;
        if (path.startsWith("/css/")) {
            filePath = Paths.get(basePath, "..", "css", path.substring(5));
        } else if (path.startsWith("/js/")) {
            filePath = Paths.get(basePath, "..", "js", path.substring(4));
        } else {
            // HTML files and anything else from html folder
            String fileName = path.startsWith("/") ? path.substring(1) : path;
            filePath = Paths.get(basePath, fileName);
        }

        File file = filePath.toFile();
        
        if (!file.exists() || !file.isFile()) {
            LOG.debug("File not found: {}", filePath);
            send404(exchange);
            return;
        }

        // Determine content type
        String contentType = URLConnection.guessContentTypeFromName(file.getName());
        if (contentType == null) {
            contentType = switch (getFileExtension(file.getName())) {
                case "html" -> "text/html";
                case "css" -> "text/css";
                case "js" -> "application/javascript";
                case "json" -> "application/json";
                case "png" -> "image/png";
                case "jpg", "jpeg" -> "image/jpeg";
                case "svg" -> "image/svg+xml";
                default -> "application/octet-stream";
            };
        }

        // CORS headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");

        // Serve file
        byte[] bytes = Files.readAllBytes(filePath);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        
        LOG.debug("Served {} ({} bytes)", path, bytes.length);
    }

    private void send404(HttpExchange exchange) throws IOException {
        String response = "404 Not Found";
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(404, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }
}
