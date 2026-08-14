package com.nextgen.desktop.ui.client;

/**
 * The small set of ways a connection or request can fail, shared by every layer that can detect a
 * failure — a raw TCP probe before any gRPC channel exists, and a gRPC call once one does.
 *
 * <p>The point of having this at all: "something went wrong" is not an error message. A user who
 * mistyped a hostname, a user whose firewall is silently dropping packets, and a user who's being
 * rate-limited by the server all need to do something different next, so they need to be told
 * something different. Every failure in the connection/join path is classified into exactly one of
 * these, and the UI picks its icon, colour and wording from the category — never from raw exception
 * text, which is written for a log file, not a person.
 */
public enum ErrorCategory {

    /** Nothing answers at this address at all: unresolvable hostname, or connection refused. */
    NOT_FOUND("Not found", "🔍"),

    /** The address resolved and something may be listening, but the attempt didn't complete in time. */
    NETWORK("Network problem", "📡"),

    /** The server is there and reachable, but is deliberately declining this request right now. */
    OVERLOAD("Server busy", "⏳"),

    /** The server rejected who we are, not what we're asking. */
    AUTHENTICATION("Not authorized", "🔒"),

    /** The server accepted the request but failed while handling it. */
    SERVER_ERROR("Server error", "⚠"),

    /** Stage J: this replica isn't the current Raft leader — a normal, expected event during a
     * failover, not a real outage. Selected by the PRESENCE of a leader-hint trailer, never by status
     * code alone (it reuses UNAVAILABLE, which otherwise means NOT_FOUND) — see
     * ControlPlaneUnavailableException.categorize. The caller has already followed the hint by the
     * time this is ever surfaced to a user; seeing it at all means every candidate was exhausted. */
    REDIRECT("Following the cluster leader", "🔁"),

    /** Doesn't fit the above — still reported honestly, just without a more specific category. */
    UNKNOWN("Unexpected error", "❓");

    private final String label;
    private final String glyph;

    ErrorCategory(String label, String glyph) {
        this.label = label;
        this.glyph = glyph;
    }

    /** Short, user-facing category name — e.g. for a status pill. */
    public String label() {
        return label;
    }

    /** A single-glyph icon standing in for the category until a real icon set exists. */
    public String glyph() {
        return glyph;
    }
}
