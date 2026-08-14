package com.nextgen.desktop.ui.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Probes whether an address is reachable at all, before any gRPC channel exists — and classifies
 * exactly how it failed, using the same {@link ErrorCategory} the gRPC-level errors use.
 *
 * <p>This is the first thing that runs when a node tries to join a server: at this point there's no
 * gRPC status code to read yet, only a raw socket exception. Java's own exception hierarchy for
 * socket failures already distinguishes the cases that matter — this class just names them the way a
 * person would rather than the way the JDK does.
 */
public final class ConnectionDiagnostics {

    private ConnectionDiagnostics() {
    }

    /** The outcome of a reachability probe. */
    public record Result(boolean reachable, ErrorCategory category, String message) {

        static Result ok() {
            return new Result(true, null, null);
        }

        static Result failure(ErrorCategory category, String message) {
            return new Result(false, category, message);
        }
    }

    /**
     * Attempts a TCP connection to {@code host:port}, classifying the failure if there is one.
     *
     * @param timeoutMs how long to wait before treating the attempt as a network problem rather than
     *                  waiting indefinitely on a silently-dropping firewall
     */
    public static Result probe(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank()) {
            return Result.failure(ErrorCategory.NOT_FOUND, "No address was given");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return Result.ok();
        } catch (UnknownHostException e) {
            // DNS could not resolve the name at all — almost always a typo, or a hostname that only
            // resolves on a different network than the one this machine is on.
            return Result.failure(ErrorCategory.NOT_FOUND,
                    "Could not find a server at '" + host + "' — check the address for typos");
        } catch (ConnectException e) {
            // The host resolved and answered, but nothing is listening on this port: "connection
            // refused". Different from a timeout, and worth telling apart — refused means the machine
            // is there and reachable, just not running (or not on) this particular service.
            return Result.failure(ErrorCategory.NOT_FOUND,
                    "No server is listening on port " + port + " at " + host);
        } catch (NoRouteToHostException e) {
            return Result.failure(ErrorCategory.NETWORK,
                    "No network route to " + host + " — check your connection or a firewall in between");
        } catch (SocketTimeoutException e) {
            // Resolved, but nothing answered within the budget. The most common real-world cause on
            // the open internet is a firewall dropping the packets rather than rejecting them —
            // functionally invisible from here, but worth naming so "it's just slow" isn't the
            // takeaway.
            return Result.failure(ErrorCategory.NETWORK,
                    "Timed out reaching " + host + ":" + port
                            + " — check the port is open and reachable from here");
        } catch (javax.net.ssl.SSLException e) {
            return Result.failure(ErrorCategory.AUTHENTICATION,
                    "Reached " + host + ":" + port + " but the TLS handshake failed: "
                            + shortMessage(e));
        } catch (IOException e) {
            return Result.failure(ErrorCategory.UNKNOWN,
                    "Could not reach " + host + ":" + port + ": " + shortMessage(e));
        }
    }

    /** Splits {@code "host:port"} or bare {@code "host"}, defaulting the port when omitted. */
    public static HostPort parseAddress(String input, int defaultPort) {
        String trimmed = input == null ? "" : input.trim();
        int colon = trimmed.lastIndexOf(':');
        // Guard against bare IPv6 literals like "::1", which contain colons that aren't a port
        // separator; this app never needs to accept a bracketed "[::1]:port" form, so a plain
        // best-effort split is sufficient rather than a full IPv6 parser.
        if (colon > 0 && colon < trimmed.length() - 1) {
            String portPart = trimmed.substring(colon + 1);
            try {
                int port = Integer.parseInt(portPart);
                if (port > 0 && port <= 65535) {
                    return new HostPort(trimmed.substring(0, colon), port);
                }
            } catch (NumberFormatException ignored) {
                // Falls through to "the whole thing is a hostname" below.
            }
        }
        return new HostPort(trimmed, defaultPort);
    }

    public record HostPort(String host, int port) {
        public boolean isValid() {
            return host != null && !host.isBlank();
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
        return message;
    }
}
