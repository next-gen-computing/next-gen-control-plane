package com.nextgen.controlplane.raft;

import com.nextgen.proto.ControlPlaneProto.ClusterStatus;
import com.nextgen.proto.ControlPlaneProto.Empty;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real in-process gRPC tests for {@link RaftLeaderRedirectInterceptor} — a fake
 * {@code ControlPlaneServiceImplBase} standing in for the real service, matching this project's
 * established convention of testing interceptors against a real call rather than invoking
 * {@code interceptCall} directly.
 */
class RaftLeaderRedirectInterceptorTest {

    private Server server;
    private io.grpc.ManagedChannel channel;

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub startWith(LeadershipStatus status) throws Exception {
        return startWith(() -> status, Set.of("nextgen.v1.ControlPlaneService/GetClusterStatus"));
    }

    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub startWith(
            java.util.function.Supplier<LeadershipStatus> statusSupplier, Set<String> followerReadable) throws Exception {
        RaftLeaderRedirectInterceptor interceptor = new RaftLeaderRedirectInterceptor(statusSupplier, followerReadable);
        ControlPlaneServiceGrpc.ControlPlaneServiceImplBase fake = new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(Empty request, StreamObserver<com.nextgen.proto.ControlPlaneProto.NodeList> obs) {
                obs.onNext(com.nextgen.proto.ControlPlaneProto.NodeList.newBuilder().build());
                obs.onCompleted();
            }

            @Override
            public void getClusterStatus(Empty request, StreamObserver<ClusterStatus> obs) {
                obs.onNext(ClusterStatus.newBuilder().setRole("FOLLOWER").build());
                obs.onCompleted();
            }
        };

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(fake, interceptor))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return ControlPlaneServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void aLeaderPassesTheCallThrough() throws Exception {
        var stub = startWith(new LeadershipStatus(RaftRole.LEADER, 5, "self", "localhost:1"));
        // Must not throw.
        stub.getNodes(Empty.newBuilder().build());
    }

    @Test
    void aFollowerIsRejectedWithUnavailableAndALeaderHintTrailer() throws Exception {
        var stub = startWith(new LeadershipStatus(RaftRole.FOLLOWER, 7, "peer-2", "10.0.0.2:50051"));

        AtomicReference<Metadata> trailers = new AtomicReference<>();
        var capturingStub = stub.withInterceptors(trailerCapturingInterceptor(trailers));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> capturingStub.getNodes(Empty.newBuilder().build()));

        assertEquals(io.grpc.Status.Code.UNAVAILABLE, ex.getStatus().getCode());
        assertEquals("peer-2=10.0.0.2:50051", trailers.get().get(RaftLeaderRedirectInterceptor.LEADER_HINT));
        assertEquals("7", trailers.get().get(RaftLeaderRedirectInterceptor.RAFT_TERM));
    }

    @Test
    void aCandidateWithNoKnownLeaderYetOmitsTheHintButStillRejects() throws Exception {
        var stub = startWith(new LeadershipStatus(RaftRole.CANDIDATE, 3, "", ""));

        AtomicReference<Metadata> trailers = new AtomicReference<>();
        var capturingStub = stub.withInterceptors(trailerCapturingInterceptor(trailers));

        assertThrows(StatusRuntimeException.class, () -> capturingStub.getNodes(Empty.newBuilder().build()));
        assertNull(trailers.get().get(RaftLeaderRedirectInterceptor.LEADER_HINT));
        assertEquals("3", trailers.get().get(RaftLeaderRedirectInterceptor.RAFT_TERM));
    }

    /** Captures the trailer {@link Metadata} gRPC delivers when a call closes — the client-side half
     * needed to assert on what {@link RaftLeaderRedirectInterceptor} attaches server-side. */
    private static ClientInterceptor trailerCapturingInterceptor(AtomicReference<Metadata> sink) {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                            @Override
                            public void onClose(io.grpc.Status status, Metadata trailers) {
                                sink.set(trailers);
                                super.onClose(status, trailers);
                            }
                        }, headers);
                    }
                };
            }
        };
    }

    @Test
    void getClusterStatusIsServableByAFollower() throws Exception {
        var stub = startWith(new LeadershipStatus(RaftRole.FOLLOWER, 9, "leader-x", "10.0.0.9:50051"));

        ClusterStatus status = stub.getClusterStatus(Empty.newBuilder().build());

        assertEquals("FOLLOWER", status.getRole());
    }

    @Test
    void followerReadableMethodsIsConfigurable() throws Exception {
        // With an EMPTY follower-readable set, even GetClusterStatus is gated.
        var stub = startWith(() -> new LeadershipStatus(RaftRole.FOLLOWER, 1, "x", "y:1"), Set.of());

        assertThrows(StatusRuntimeException.class, () -> stub.getClusterStatus(Empty.newBuilder().build()));
    }
}
