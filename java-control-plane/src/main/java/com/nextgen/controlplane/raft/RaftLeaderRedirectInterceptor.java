package com.nextgen.controlplane.raft;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Rejects an RPC arriving at a replica that is not currently the Raft leader — {@code UNAVAILABLE}
 * with a leader-hint trailer, never a silent no-op or a hang. {@code UNAVAILABLE} rather than
 * {@code FAILED_PRECONDITION} deliberately: it is gRPC's standard "try elsewhere" signal, distinct
 * from a permanent application-level rejection, and is what {@code ControlPlaneClient}'s redirect-
 * following retry loop keys off.
 *
 * <p><b>All RPCs are gated in this first cut except {@link #followerReadableMethods}</b> (just
 * {@code GetClusterStatus} by default) — a follower's registries receive zero heartbeats (heartbeats
 * are deliberately leader-local, never replicated; see the plan's Stage J notes on what crosses the
 * Raft line), so a follower answering, say, {@code GetNodes} would render every node falsely stale.
 * Follower-served reads for methods that don't have this problem are named future work, not built here.
 *
 * <p>Must be chained <b>after</b> {@code MtlsPolicyInterceptor}/{@code PeerIdentityInterceptor} — in
 * {@code ServerInterceptors.intercept(service, a, b, c)}, the interceptor listed <i>last</i> runs
 * <i>first</i>, so {@code intercept(serviceImpl, raftRedirect, mtlsPolicy, peerIdentity)} authenticates
 * before this ever runs, ensuring an unauthenticated caller is rejected before it can learn anything
 * about cluster topology from a leader-hint trailer.
 */
public final class RaftLeaderRedirectInterceptor implements ServerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(RaftLeaderRedirectInterceptor.class);

    public static final Metadata.Key<String> LEADER_HINT =
            Metadata.Key.of("x-nextgen-raft-leader", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> RAFT_TERM =
            Metadata.Key.of("x-nextgen-raft-term", Metadata.ASCII_STRING_MARSHALLER);

    private static final Set<String> DEFAULT_FOLLOWER_READABLE_METHODS =
            Set.of("nextgen.v1.ControlPlaneService/GetClusterStatus");

    private final Supplier<LeadershipStatus> leadership;
    private final Set<String> followerReadableMethods;

    public RaftLeaderRedirectInterceptor(Supplier<LeadershipStatus> leadership) {
        this(leadership, DEFAULT_FOLLOWER_READABLE_METHODS);
    }

    /** @param followerReadableMethods fully-qualified methods (e.g. {@code "nextgen.v1.ControlPlaneService/Foo"})
     *                                 a non-leader may still serve — the extension point for follower-
     *                                 served stale reads later; empty by default except GetClusterStatus. */
    public RaftLeaderRedirectInterceptor(Supplier<LeadershipStatus> leadership, Set<String> followerReadableMethods) {
        this.leadership = leadership;
        this.followerReadableMethods = Set.copyOf(followerReadableMethods);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        if (followerReadableMethods.contains(method)) {
            return next.startCall(call, headers);
        }

        LeadershipStatus status = leadership.get();
        if (status.isLeader()) {
            return next.startCall(call, headers);
        }

        Metadata trailers = new Metadata();
        if (!status.leaderId().isEmpty() && !status.leaderAddress().isEmpty()) {
            trailers.put(LEADER_HINT, status.leaderId() + "=" + status.leaderAddress());
        }
        trailers.put(RAFT_TERM, Long.toString(status.term()));
        LOG.debug("Rejecting {} — not the leader (role={}, term={}, leader={})",
                method, status.role(), status.term(), status.leaderId());
        call.close(Status.UNAVAILABLE.withDescription("NOT_LEADER"), trailers);
        // MUST NOT be null: gRPC dereferences the returned listener and NPEs if it is.
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                new ServerCall.Listener<ReqT>() {
                }) {
        };
    }
}
