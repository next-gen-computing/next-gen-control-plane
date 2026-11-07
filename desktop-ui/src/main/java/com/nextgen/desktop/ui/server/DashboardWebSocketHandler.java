package com.nextgen.desktop.ui.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Minimal WebSocket upgrade for the desktop dashboard. This keeps the existing SSE-based polling
 * code working unchanged while adding the missing real WebSocket endpoint the roadmap calls out.
 */
public class DashboardWebSocketHandler implements HttpHandler {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
        String connection = exchange.getRequestHeaders().getFirst("Connection");
        String key = exchange.getRequestHeaders().getFirst("Sec-WebSocket-Key");

        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)
                || connection == null || !connection.toLowerCase().contains("upgrade")
                || key == null || key.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        String accept;
        try {
            accept = calculateWebSocketAccept(key);
        } catch (NoSuchAlgorithmException e) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("Upgrade", "websocket");
        exchange.getResponseHeaders().add("Connection", "Upgrade");
        exchange.getResponseHeaders().add("Sec-WebSocket-Accept", accept);
        exchange.sendResponseHeaders(101, -1);

        try (InputStream in = exchange.getRequestBody(); OutputStream out = exchange.getResponseBody()) {
            writeFrame(out, (byte) 0x81, "{\"type\":\"connected\",\"endpoint\":\"/api/dashboard/ws\"}".getBytes(StandardCharsets.UTF_8));
            while (true) {
                WebSocketFrame frame = readFrame(in);
                if (frame == null) {
                    break;
                }
                if (frame.opcode == 0x8) {
                    writeCloseFrame(out);
                    break;
                }
                if (frame.opcode == 0x1) {
                    String text = new String(frame.payload, StandardCharsets.UTF_8);
                    writeFrame(out, (byte) 0x81, ("{\"type\":\"echo\",\"payload\":" + escapeJson(text) + "}").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static String calculateWebSocketAccept(String key) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest((key.trim() + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static void writeFrame(OutputStream out, byte firstByte, byte[] payload) throws IOException {
        out.write(firstByte);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length <= 65535) {
            out.write(126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write((payload.length >>> 24) & 0xFF);
            out.write((payload.length >>> 16) & 0xFF);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        }
        out.write(payload);
        out.flush();
    }

    private static void writeCloseFrame(OutputStream out) throws IOException {
        out.write(0x88);
        out.write(0x02);
        out.write(0x03);
        out.write(0xE8);
        out.flush();
    }

    private static WebSocketFrame readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }

        int second = in.read();
        if (second == -1) {
            return null;
        }

        boolean masked = (second & 0x80) != 0;
        int length = second & 0x7F;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            long hugeLength = 0L;
            for (int i = 0; i < 8; i++) {
                hugeLength = (hugeLength << 8) | (in.read() & 0xFF);
            }
            if (hugeLength > Integer.MAX_VALUE) {
                throw new IOException("WebSocket payload too large");
            }
            length = (int) hugeLength;
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[] { (byte) in.read(), (byte) in.read(), (byte) in.read(), (byte) in.read() };
        }

        byte[] payload = new byte[length];
        int read = 0;
        while (read < length) {
            int chunk = in.read(payload, read, length - read);
            if (chunk < 0) {
                throw new IOException("Socket closed while reading WebSocket payload");
            }
            read += chunk;
        }

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }

        return new WebSocketFrame(first, second, payload);
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class WebSocketFrame {
        private final int opcode;
        private final byte[] payload;

        private WebSocketFrame(int firstByte, int secondByte, byte[] payload) {
            this.opcode = firstByte & 0x0F;
            this.payload = payload;
        }
    }
}
