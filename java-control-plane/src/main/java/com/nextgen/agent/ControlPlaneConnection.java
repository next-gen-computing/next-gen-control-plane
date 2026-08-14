package com.nextgen.agent;

import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptor;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns every channel this agent ever dials to the control plane — one per
 * {@link ControlPlaneEndpoints.HostPort} candidate, built lazily via {@link NodeAgent#buildChannel} and
 * cached, so a failover between two already-contacted replicas doesn't pay fresh TLS/TCP setup on every
 * redirect — plus the leader-redirect bookkeeping ({@link #onLeaderHint}/{@link #onFailure}) that
 * registration, heartbeats, and the task channel all need identically.
 *
 * <p>One instance is shared across all three of those call sites in {@link NodeAgent#start}, which
 * means leader knowledge propagates between them for free: once a heartbeat discovers a new leader via
 * a redirect hint, the task channel's own next reconnect resolves the SAME hinted address immediately,
 * rather than each path rediscovering the leader independently.
 */
public final class ControlPlaneConnection {

    /** Bounds a pathological redirect loop (e.g. two replicas each hinting at the other) to a handful of
     * hops rather than spinning forever chasing hints that never resolve. */
    public static final int MAX_LEADER_HOPS = 3;

    private final ControlPlaneEndpoints endpoints;
    private final boolean tlsEnabled;
    private final AgentCredentials credentials;
    private final Map<ControlPlaneEndpoints.HostPort, ManagedChannel> channels = new ConcurrentHashMap<>();

    public ControlPlaneConnection(ControlPlaneEndpoints endpoints, boolean tlsEnabled,
                                  AgentCredentials credentials) {
        this.endpoints = endpoints;
        this.tlsEnabled = tlsEnabled;
        this.credentials = credentials;
    }

    public ControlPlaneEndpoints.HostPort current() {
        return endpoints.current();
    }

    private ManagedChannel currentChannel() {
        ControlPlaneEndpoints.HostPort target = endpoints.current();
        return channels.computeIfAbsent(target,
                t -> NodeAgent.buildChannel(t.host(), t.port(), tlsEnabled, credentials));
    }

    /** A fresh blocking stub bound to whichever candidate {@link ControlPlaneEndpoints#current()}
     * currently resolves to — call this again after following a redirect, never cache the result. */
    public ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub() {
        return ControlPlaneServiceGrpc.newBlockingStub(currentChannel());
    }

    /** As {@link #blockingStub()}, for the async stub the long-lived task channel stream is opened on. */
    public ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub() {
        return ControlPlaneServiceGrpc.newStub(currentChannel());
    }

    /** A redirect hint arrived — see {@link ControlPlaneEndpoints#onLeaderHint}. */
    public void onLeaderHint(String hint) {
        endpoints.onLeaderHint(hint);
    }

    /** The current candidate failed outright (not a redirect) — see {@link ControlPlaneEndpoints#onFailure}. */
    public void onFailure() {
        endpoints.onFailure();
    }

    /**
     * The leader-hint trailer carried by {@code t}, or {@code null} if {@code t} is not a redirect —
     * whether because it's a genuine transport failure or a real application error. Shared by
     * registration, the heartbeat loop, and {@link com.nextgen.agent.task.TaskChannelClient} so all
     * three classify a failure identically.
     */
    public static String leaderHint(Throwable t) {
        if (!(t instanceof StatusRuntimeException sre)) {
            return null;
        }
        Metadata trailers = sre.getTrailers();
        if (trailers == null) {
            return null;
        }
        String hint = trailers.get(RaftLeaderRedirectInterceptor.LEADER_HINT);
        return (hint == null || hint.isBlank()) ? null : hint;
    }

    public void shutdown() {
        channels.values().forEach(ch -> {
            try {
                ch.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        channels.clear();
    }
}
