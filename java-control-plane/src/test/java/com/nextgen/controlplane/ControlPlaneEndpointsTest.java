package com.nextgen.controlplane;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlPlaneEndpointsTest {

    @Test
    void currentReturnsTheOnlyCandidateForASingleEndpoint() {
        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single("host-a", 50051);
        assertEquals(new ControlPlaneEndpoints.HostPort("host-a", 50051), endpoints.current());
    }

    @Test
    void onFailureRotatesToTheNextCandidate() {
        ControlPlaneEndpoints endpoints = new ControlPlaneEndpoints(List.of(
                new ControlPlaneEndpoints.HostPort("a", 1), new ControlPlaneEndpoints.HostPort("b", 2),
                new ControlPlaneEndpoints.HostPort("c", 3)));

        assertEquals("a", endpoints.current().host());
        endpoints.onFailure();
        assertEquals("b", endpoints.current().host());
        endpoints.onFailure();
        assertEquals("c", endpoints.current().host());
        endpoints.onFailure(); // wraps back around
        assertEquals("a", endpoints.current().host());
    }

    @Test
    void onLeaderHintMakesTheHintedAddressCurrentRegardlessOfRotation() {
        ControlPlaneEndpoints endpoints = new ControlPlaneEndpoints(List.of(
                new ControlPlaneEndpoints.HostPort("a", 1), new ControlPlaneEndpoints.HostPort("b", 2)));

        endpoints.onLeaderHint("cp-2=10.0.0.5:50051");

        assertEquals(new ControlPlaneEndpoints.HostPort("10.0.0.5", 50051), endpoints.current());
    }

    @Test
    void onLeaderHintAcceptsABareHostPortWithoutAnId() {
        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single("a", 1);
        endpoints.onLeaderHint("10.0.0.9:50051");
        assertEquals(new ControlPlaneEndpoints.HostPort("10.0.0.9", 50051), endpoints.current());
    }

    @Test
    void aMalformedHintIsIgnoredRatherThanThrown() {
        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single("a", 1);
        endpoints.onLeaderHint("not-a-valid-hint");
        assertEquals(new ControlPlaneEndpoints.HostPort("a", 1), endpoints.current());
    }

    @Test
    void onFailureDiscardsAnInEffectHintAndResumesRotation() {
        ControlPlaneEndpoints endpoints = new ControlPlaneEndpoints(List.of(
                new ControlPlaneEndpoints.HostPort("a", 1), new ControlPlaneEndpoints.HostPort("b", 2)));
        endpoints.onLeaderHint("stale-hint-host:9999");
        assertEquals("stale-hint-host", endpoints.current().host());

        endpoints.onFailure();

        assertEquals("b", endpoints.current().host(), "a failed hint must fall back to the real candidate list, not retry the same stale hint");
    }

    @Test
    void constructingWithNoCandidatesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ControlPlaneEndpoints(List.of()));
    }

    @Test
    void hostPortParseRejectsTextWithoutAColon() {
        assertThrows(IllegalArgumentException.class, () -> ControlPlaneEndpoints.HostPort.parse("no-port-here"));
    }

    @Test
    void hostPortToStringRoundTrips() {
        var hostPort = new ControlPlaneEndpoints.HostPort("example.com", 443);
        assertEquals("example.com:443", hostPort.toString());
        assertEquals(hostPort, ControlPlaneEndpoints.HostPort.parse(hostPort.toString()));
    }
}
