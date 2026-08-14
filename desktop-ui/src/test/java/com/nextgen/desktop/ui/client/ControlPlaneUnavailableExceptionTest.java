package com.nextgen.desktop.ui.client;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for how a gRPC failure is classified and worded for the UI.
 *
 * <p>The categorisation is what lets the UI treat "the server doesn't exist", "the network is
 * having a bad day" and "the server said no, try later" as three different situations instead of one
 * generic "control plane error" — each needs a different next action from the person looking at it.
 */
class ControlPlaneUnavailableExceptionTest {

    private static ControlPlaneUnavailableException forStatus(Status status) {
        return new ControlPlaneUnavailableException("op", new StatusRuntimeException(status));
    }

    @Test
    void unavailableIsNotFound() {
        var failure = forStatus(Status.UNAVAILABLE);

        assertEquals(ErrorCategory.NOT_FOUND, failure.category());
        assertEquals("Control plane unreachable", failure.shortReason());
    }

    @Test
    void deadlineExceededIsNetwork() {
        var failure = forStatus(Status.DEADLINE_EXCEEDED);

        assertEquals(ErrorCategory.NETWORK, failure.category());
        assertEquals("Control plane timed out", failure.shortReason());
    }

    @Test
    void unauthenticatedIsAuthentication() {
        var failure = forStatus(Status.UNAUTHENTICATED);

        assertEquals(ErrorCategory.AUTHENTICATION, failure.category());
        assertEquals("Authentication rejected", failure.shortReason());
    }

    @Test
    void permissionDeniedIsAlsoAuthenticationButWordedDifferently() {
        var failure = forStatus(Status.PERMISSION_DENIED);

        assertEquals(ErrorCategory.AUTHENTICATION, failure.category());
        assertEquals("Permission denied", failure.shortReason());
    }

    @Test
    void internalErrorNamesItsCodeForDebuggability() {
        var failure = forStatus(Status.INTERNAL);

        assertEquals(ErrorCategory.SERVER_ERROR, failure.category());
        assertTrue(failure.shortReason().contains("INTERNAL"), failure.shortReason());
    }

    @Test
    void unmappedCodeFallsBackToUnknownRatherThanMisreporting() {
        var failure = forStatus(Status.ABORTED);

        assertEquals(ErrorCategory.UNKNOWN, failure.category());
        assertTrue(failure.shortReason().contains("ABORTED"), failure.shortReason());
    }

    // ── The rate-limiter (overload) case ─────────────────────────────────────

    @Test
    void resourceExhaustedIsOverloadNotGenericUnavailable() {
        // This is the case that was missing entirely before: RATE_LIMITED enrolment attempts came
        // back as RESOURCE_EXHAUSTED and fell into the same generic "default" bucket as everything
        // else unrecognised, telling the user nothing about why or what to do next.
        var failure = forStatus(Status.RESOURCE_EXHAUSTED);

        assertEquals(ErrorCategory.OVERLOAD, failure.category());
        assertTrue(failure.shortReason().toLowerCase().contains("too many"), failure.shortReason());
    }

    @Test
    void overloadMessageIncludesTheServersRetryAfterWhenPresent() {
        Metadata trailers = new Metadata();
        trailers.put(Metadata.Key.of("retry-after-millis", Metadata.ASCII_STRING_MARSHALLER), "5000");
        StatusRuntimeException cause = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED, trailers);

        var failure = new ControlPlaneUnavailableException("enroll", cause);

        assertTrue(failure.shortReason().contains("5 seconds"), failure.shortReason());
    }

    @Test
    void overloadMessageDegradesGracefullyWithoutRetryAfter() {
        var failure = forStatus(Status.RESOURCE_EXHAUSTED);

        assertDoesNotThrow(failure::shortReason);
        assertFalse(failure.shortReason().isBlank());
    }

    @Test
    void malformedRetryAfterDoesNotBreakTheMessage() {
        Metadata trailers = new Metadata();
        trailers.put(Metadata.Key.of("retry-after-millis", Metadata.ASCII_STRING_MARSHALLER), "not-a-number");
        StatusRuntimeException cause = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED, trailers);

        var failure = new ControlPlaneUnavailableException("enroll", cause);

        assertDoesNotThrow(failure::shortReason);
    }

    // ── The exception itself never leaks internals into the short message ────

    @Test
    void shortReasonNeverContainsAStackTrace() {
        var failure = forStatus(Status.UNAVAILABLE);

        assertFalse(failure.shortReason().contains("\tat "));
        assertFalse(failure.shortReason().contains(".java:"));
    }

    @Test
    void causeIsPreservedForLogging() {
        StatusRuntimeException cause = new StatusRuntimeException(Status.UNAVAILABLE);

        var failure = new ControlPlaneUnavailableException("getNodes", cause);

        assertSame(cause, failure.getCause());
    }
}
