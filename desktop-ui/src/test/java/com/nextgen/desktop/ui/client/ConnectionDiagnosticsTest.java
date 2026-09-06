package com.nextgen.desktop.ui.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the raw-socket connection probe used before any gRPC channel exists — the first thing
 * that runs when a node tries to join a server.
 */
class ConnectionDiagnosticsTest {

    // ── probe(): success ─────────────────────────────────────────────────────

    @Test
    void reachesARealListeningPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            var result = ConnectionDiagnostics.probe("localhost", serverSocket.getLocalPort(), 2000);

            assertTrue(result.reachable());
            assertNull(result.category());
        }
    }

    // ── probe(): failure classification ──────────────────────────────────────

    @Test
    void unresolvableHostnameIsClassifiedNotFound() {
        var result = ConnectionDiagnostics.probe(
                "this-hostname-should-never-resolve.invalid", 50051, 2000);

        assertFalse(result.reachable());
        assertEquals(ErrorCategory.NOT_FOUND, result.category());
        assertTrue(result.message().toLowerCase().contains("could not find"), result.message());
    }

    @Test
    void nothingListeningIsClassifiedNotFound() {
        // Port 1 is a reserved, essentially-guaranteed-closed port on localhost (TCP port assignment
        // requires a privileged process, and nothing binds it in test environments).
        var result = ConnectionDiagnostics.probe("localhost", 1, 2000);

        assertFalse(result.reachable());
        assertEquals(ErrorCategory.NOT_FOUND, result.category());
    }

    @Test
    void blankHostIsClassifiedNotFoundWithoutAttemptingAnyConnection() {
        var result = ConnectionDiagnostics.probe("", 50051, 2000);

        assertFalse(result.reachable());
        assertEquals(ErrorCategory.NOT_FOUND, result.category());
        assertEquals("No address was given", result.message());
    }

    @Test
    void nullHostIsHandledTheSameAsBlank() {
        var result = ConnectionDiagnostics.probe(null, 50051, 2000);

        assertFalse(result.reachable());
        assertEquals(ErrorCategory.NOT_FOUND, result.category());
    }

    @Test
    void aVeryShortTimeoutAgainstAnUnroutableAddressIsClassifiedAsNetworkOrNotFound() {
        // 10.255.255.1 is a private, non-routable-from-here address chosen so this either times out
        // or is refused quickly; either NETWORK or NOT_FOUND is an honest classification, unlike
        // treating every failure identically the way the code this replaces did.
        var result = ConnectionDiagnostics.probe("10.255.255.1", 50051, 300);

        assertFalse(result.reachable());
        assertTrue(result.category() == ErrorCategory.NETWORK || result.category() == ErrorCategory.NOT_FOUND,
                "unexpected category: " + result.category());
        assertNotNull(result.message());
        assertFalse(result.message().isBlank());
    }

    // ── parseAddress ─────────────────────────────────────────────────────────

    @Test
    void parsesHostAndPort() {
        var hostPort = ConnectionDiagnostics.parseAddress("example.com:9090", 50051);

        assertEquals("example.com", hostPort.host());
        assertEquals(9090, hostPort.port());
    }

    @Test
    void bareHostnameGetsTheDefaultPort() {
        var hostPort = ConnectionDiagnostics.parseAddress("example.com", 50051);

        assertEquals("example.com", hostPort.host());
        assertEquals(50051, hostPort.port());
    }

    @Test
    void aTrailingColonWithNoDigitsFallsBackToTheWholeStringAsHostname() {
        // ":" with nothing parseable after it is not a valid port, so the whole input is treated as
        // the hostname rather than silently producing a mangled address.
        var hostPort = ConnectionDiagnostics.parseAddress("example.com:", 50051);

        assertEquals("example.com:", hostPort.host());
        assertEquals(50051, hostPort.port());
    }

    @Test
    void whitespaceIsTrimmed() {
        var hostPort = ConnectionDiagnostics.parseAddress("  example.com:9090  ", 50051);

        assertEquals("example.com", hostPort.host());
        assertEquals(9090, hostPort.port());
    }

    @Test
    void anOutOfRangePortNumberIsTreatedAsPartOfTheHostname() {
        // "99999" is not a valid TCP port; falling back to "the whole string is a hostname" is safer
        // than silently clamping or truncating it into a port that was never intended.
        var hostPort = ConnectionDiagnostics.parseAddress("example.com:99999", 50051);

        assertEquals("example.com:99999", hostPort.host());
        assertEquals(50051, hostPort.port());
    }

    @Test
    void blankInputIsNotValid() {
        assertFalse(ConnectionDiagnostics.parseAddress("", 50051).isValid());
        assertFalse(ConnectionDiagnostics.parseAddress("   ", 50051).isValid());
        assertFalse(ConnectionDiagnostics.parseAddress(null, 50051).isValid());
    }

    @Test
    void nonBlankInputIsValid() {
        assertTrue(ConnectionDiagnostics.parseAddress("host", 50051).isValid());
    }

    @Test
    void toStringIsHostColonPort() {
        assertEquals("example.com:9090", ConnectionDiagnostics.parseAddress("example.com:9090", 1).toString());
    }
}
