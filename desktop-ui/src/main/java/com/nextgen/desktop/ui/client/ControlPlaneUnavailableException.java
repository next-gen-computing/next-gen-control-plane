package com.nextgen.desktop.ui.client;

import com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptor;
import io.grpc.StatusRuntimeException;

/**
 * Thrown when an RPC to the control plane could not be completed.
 *
 * <p>Exists so callers cannot mistake a failure for a result. The client previously swallowed
 * {@link StatusRuntimeException} and returned an empty list, which the UI rendered as a healthy
 * cluster with zero nodes.
 */
public class ControlPlaneUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String shortReason;
    private final ErrorCategory category;

    public ControlPlaneUnavailableException(String operation, StatusRuntimeException cause) {
        super("control plane " + operation + " failed: " + cause.getStatus(), cause);
        this.category = categorize(cause);
        this.shortReason = describe(cause, category);
    }

    /** A short, user-facing cause suitable for a status bar. Never a stack trace. */
    public String shortReason() {
        return shortReason;
    }

    /** Which of the small set of failure shapes this was, for consistent icon/colour treatment. */
    public ErrorCategory category() {
        return category;
    }

    private static ErrorCategory categorize(StatusRuntimeException cause) {
        // Checked by trailer PRESENCE, not status code alone: RaftLeaderRedirectInterceptor reuses
        // UNAVAILABLE (gRPC's standard "try elsewhere" signal) for a "not the leader" rejection, which
        // is a normal, expected event during a failover — very different from "nothing is listening".
        if (cause.getTrailers() != null
                && cause.getTrailers().get(RaftLeaderRedirectInterceptor.LEADER_HINT) != null) {
            return ErrorCategory.REDIRECT;
        }
        return switch (cause.getStatus().getCode()) {
            case UNAVAILABLE -> ErrorCategory.NOT_FOUND;
            case DEADLINE_EXCEEDED -> ErrorCategory.NETWORK;
            // The rate limiter (Phase 3) returns this specifically when enrolment is being throttled.
            // It's a distinct situation from "down" or "unreachable": the server is fine, it's saying
            // "not right now" — and the fix is to wait, not to re-check the address.
            case RESOURCE_EXHAUSTED -> ErrorCategory.OVERLOAD;
            case UNAUTHENTICATED, PERMISSION_DENIED -> ErrorCategory.AUTHENTICATION;
            case INTERNAL, UNKNOWN, DATA_LOSS -> ErrorCategory.SERVER_ERROR;
            default -> ErrorCategory.UNKNOWN;
        };
    }

    private static String describe(StatusRuntimeException cause, ErrorCategory category) {
        return switch (category) {
            case NOT_FOUND -> "Control plane unreachable";
            case NETWORK -> "Control plane timed out";
            case OVERLOAD -> {
                String retryAfter = cause.getTrailers() == null ? null
                        : cause.getTrailers().get(io.grpc.Metadata.Key.of(
                                "retry-after-millis", io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                yield retryAfter != null
                        ? "Too many attempts — try again in " + formatRetryAfter(retryAfter)
                        : "Too many attempts — try again shortly";
            }
            case AUTHENTICATION -> cause.getStatus().getCode() == io.grpc.Status.Code.UNAUTHENTICATED
                    ? "Authentication rejected"
                    : "Permission denied";
            case SERVER_ERROR -> "Control plane reported an internal error ("
                    + cause.getStatus().getCode() + ")";
            case REDIRECT -> "Following the cluster leader";
            default -> "Control plane error (" + cause.getStatus().getCode() + ")";
        };
    }

    private static String formatRetryAfter(String millisText) {
        try {
            long millis = Long.parseLong(millisText);
            long seconds = Math.max(1, millis / 1000);
            return seconds == 1 ? "1 second" : seconds + " seconds";
        } catch (NumberFormatException e) {
            return "a moment";
        }
    }
}
