package com.nextgen.desktop.ui.client;

import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptor;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ControlPlaneClient}'s redirect-following constructor, driven against real in-process gRPC
 * servers standing in for a follower (rejects with a leader hint) and a leader (serves normally).
 */
class ControlPlaneClientLeaderRedirectTest {

    private final List<Server> servers = new java.util.ArrayList<>();
    private final List<ManagedChannel> channels = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ControlPlaneEndpoints.HostPort startFollower(String leaderHint) throws Exception {
        return startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(ControlPlaneProto.Empty request, StreamObserver<ControlPlaneProto.NodeList> obs) {
                Metadata trailers = new Metadata();
                trailers.put(RaftLeaderRedirectInterceptor.LEADER_HINT, leaderHint);
                trailers.put(RaftLeaderRedirectInterceptor.RAFT_TERM, "5");
                obs.onError(Status.UNAVAILABLE.withDescription("NOT_LEADER")
                        .asRuntimeException(trailers));
            }
        });
    }

    private ControlPlaneEndpoints.HostPort startLeader(AtomicInteger callCount) throws Exception {
        return startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(ControlPlaneProto.Empty request, StreamObserver<ControlPlaneProto.NodeList> obs) {
                callCount.incrementAndGet();
                obs.onNext(ControlPlaneProto.NodeList.newBuilder()
                        .addNodes(ControlPlaneProto.NodeInfo.newBuilder().setNodeId("real-node").build())
                        .build());
                obs.onCompleted();
            }
        });
    }

    /**
     * Uses a real loopback TCP port (not in-process) so the "id=host:port" hint round-trips through a
     * dialable address, exactly like production. Binds directly to port 0 and reads back whatever the
     * OS actually assigned — reserving a port via a throwaway {@code ServerSocket} and rebinding to it
     * a moment later is a real, observed race on Windows (the OS doesn't always release a just-closed
     * listening socket in time for an immediate rebind), so this avoids that dance entirely.
     */
    private ControlPlaneEndpoints.HostPort startServer(ControlPlaneServiceGrpc.ControlPlaneServiceImplBase impl)
            throws Exception {
        Server server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forPort(0)
                .addService(impl).build().start();
        servers.add(server);
        return new ControlPlaneEndpoints.HostPort("localhost", server.getPort());
    }

    private ControlPlaneClient clientFor(ControlPlaneEndpoints endpoints) {
        Map<ControlPlaneEndpoints.HostPort, ManagedChannel> cache = new HashMap<>();
        return new ControlPlaneClient(endpoints, target -> cache.computeIfAbsent(target, t -> {
            ManagedChannel channel = io.grpc.ManagedChannelBuilder
                    .forAddress(t.host(), t.port()).usePlaintext().build();
            channels.add(channel);
            return channel;
        }));
    }

    @Test
    void followsALeaderHintOnTheFirstRetryAndSucceeds() throws Exception {
        AtomicInteger leaderCalls = new AtomicInteger();
        ControlPlaneEndpoints.HostPort leaderAddr = startLeader(leaderCalls);
        ControlPlaneEndpoints.HostPort followerAddr = startFollower("cp-leader=" + leaderAddr);

        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single(followerAddr.host(), followerAddr.port());
        ControlPlaneClient client = clientFor(endpoints);

        List<ControlPlaneProto.NodeInfo> nodes = client.getNodes();

        assertEquals(1, nodes.size());
        assertEquals("real-node", nodes.get(0).getNodeId());
        assertEquals(1, leaderCalls.get());
    }

    @Test
    void aStaleHintPointingAtADeadAddressFallsBackToTheNextConfiguredCandidate() throws Exception {
        AtomicInteger leaderCalls = new AtomicInteger();
        ControlPlaneEndpoints.HostPort realLeader = startLeader(leaderCalls);
        // A follower whose hint points at a port nothing is listening on.
        ControlPlaneEndpoints.HostPort deadHintTarget;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadHintTarget = new ControlPlaneEndpoints.HostPort("localhost", probe.getLocalPort());
        } // closed immediately — guaranteed nothing listens here now
        ControlPlaneEndpoints.HostPort followerAddr = startFollower("stale=" + deadHintTarget);

        // The candidate list itself includes the real leader as the SECOND entry, so once the stale
        // hint fails outright, onFailure()'s rotation reaches a real address.
        ControlPlaneEndpoints endpoints = new ControlPlaneEndpoints(List.of(followerAddr, realLeader));
        ControlPlaneClient client = clientFor(endpoints);

        List<ControlPlaneProto.NodeInfo> nodes = client.getNodes();

        assertEquals(1, nodes.size());
        assertTrue(leaderCalls.get() >= 1);
    }

    @Test
    void exhaustingEveryCandidateThrowsRatherThanReturningAnEmptySuccess() throws Exception {
        ControlPlaneEndpoints.HostPort followerAddr = startFollower("nowhere=127.0.0.1:1");

        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single(followerAddr.host(), followerAddr.port());
        ControlPlaneClient client = clientFor(endpoints);

        assertThrows(ControlPlaneUnavailableException.class, client::getNodes);
    }

    @Test
    void aRealApplicationErrorIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ControlPlaneEndpoints.HostPort addr = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(ControlPlaneProto.Empty request, StreamObserver<ControlPlaneProto.NodeList> obs) {
                calls.incrementAndGet();
                obs.onError(Status.INVALID_ARGUMENT.withDescription("bad request").asRuntimeException());
            }
        });
        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single(addr.host(), addr.port());
        ControlPlaneClient client = clientFor(endpoints);

        assertThrows(ControlPlaneUnavailableException.class, client::getNodes);
        assertEquals(1, calls.get(), "a non-transport, non-redirect error must be surfaced immediately, not retried");
    }
}
