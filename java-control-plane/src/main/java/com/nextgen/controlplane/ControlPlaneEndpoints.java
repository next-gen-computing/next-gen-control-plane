package com.nextgen.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The set of candidate control-plane addresses a client (desktop-ui or a node agent) may reach, plus
 * the leader-redirect-following bookkeeping every client needs identically — shared here (rather than
 * duplicated per client) because {@code desktop-ui} already depends on this module.
 *
 * <p>{@link #fromEnvironment()} reads {@code CONTROL_PLANE_ENDPOINTS} ({@code "host1:port1,host2:port2,..."})
 * when set, falling back to the single {@code CONTROL_PLANE_HOST}/{@code CONTROL_PLANE_PORT} pair every
 * existing deployment already sets — a single-node, non-Raft deployment needs zero configuration change.
 *
 * <p>A redirect hint from {@link #onLeaderHint} is always just a hint, never a permanent replacement of
 * the configured candidate list: if dialling it subsequently fails, {@link #onFailure} discards it and
 * resumes rotating the original candidates.
 */
public final class ControlPlaneEndpoints {

    private final List<HostPort> candidates;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private volatile HostPort hinted;

    public ControlPlaneEndpoints(List<HostPort> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one candidate address is required");
        }
        this.candidates = List.copyOf(candidates);
    }

    public static ControlPlaneEndpoints single(String host, int port) {
        return new ControlPlaneEndpoints(List.of(new HostPort(host, port)));
    }

    public static ControlPlaneEndpoints fromEnvironment() {
        String raw = EnvConfig.stringValue("CONTROL_PLANE_ENDPOINTS", "");
        if (!raw.isBlank()) {
            List<HostPort> parsed = new ArrayList<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(HostPort.parse(trimmed));
                }
            }
            if (!parsed.isEmpty()) {
                return new ControlPlaneEndpoints(parsed);
            }
        }
        String host = EnvConfig.stringValue("CONTROL_PLANE_HOST", "localhost");
        int port = EnvConfig.intValue("CONTROL_PLANE_PORT", 50051);
        return single(host, port);
    }

    /** The address to try right now: a still-in-effect leader hint, or the current rotation candidate. */
    public synchronized HostPort current() {
        return hinted != null ? hinted : candidates.get(Math.floorMod(currentIndex.get(), candidates.size()));
    }

    /**
     * A redirect hint arrived — either {@code "id=host:port"} (as {@code RaftLeaderRedirectInterceptor}
     * sends it) or a bare {@code "host:port"} — and becomes the address {@link #current()} returns
     * until it either succeeds or {@link #onFailure()} discards it. A malformed hint is ignored rather
     * than thrown, since acting on a hint is always best-effort.
     */
    public synchronized void onLeaderHint(String hint) {
        if (hint == null || hint.isBlank()) {
            return;
        }
        String hostPort = hint.contains("=") ? hint.substring(hint.indexOf('=') + 1) : hint;
        try {
            hinted = HostPort.parse(hostPort);
        } catch (RuntimeException e) {
            hinted = null;
        }
    }

    /** The current address failed outright (not a redirect) — drop any hint and rotate to the next
     * configured candidate. */
    public synchronized void onFailure() {
        hinted = null;
        currentIndex.incrementAndGet();
    }

    public List<HostPort> candidates() {
        return candidates;
    }

    public record HostPort(String host, int port) {
        public static HostPort parse(String text) {
            int colon = text.lastIndexOf(':');
            if (colon < 0 || colon == text.length() - 1) {
                throw new IllegalArgumentException("expected host:port, got: " + text);
            }
            String host = text.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(text.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected host:port, got: " + text, e);
            }
            return new HostPort(host, port);
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
