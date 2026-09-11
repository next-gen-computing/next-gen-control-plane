package com.nextgen.controlplane.raft;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the {@code RaftConsensus} proto/gRPC plumbing itself — two real {@link RaftNode}s, each
 * wired to a real {@link GrpcRaftTransport} talking over a real (localhost) gRPC server running
 * {@link RaftConsensusServiceImpl} — elect a leader. This is deliberately small: the algorithm itself
 * is already proven exhaustively by the {@link InMemoryRaftTransport}-based Phase-A suite
 * ({@code RaftElectionTest} etc.); this test exists only to catch a wiring mistake in the proto
 * conversion or service registration that an in-memory transport can never exercise.
 */
class RaftConsensusServiceImplTest {

    private final List<Server> servers = new ArrayList<>();
    private final List<RaftNode> nodes = new ArrayList<>();
    private final List<GrpcRaftTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (RaftNode node : nodes) {
            node.close();
        }
        for (GrpcRaftTransport transport : transports) {
            transport.shutdown();
        }
        for (Server server : servers) {
            server.shutdownNow();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void twoNodesWiredOverRealGrpcElectExactlyOneLeader(@TempDir Path dir) throws Exception {
        int portA = freePort();
        int portB = freePort();
        List<RaftPeer> members = List.of(
                new RaftPeer("a", "localhost", portA),
                new RaftPeer("b", "localhost", portB));

        RaftTimings timings = new RaftTimings(20, 100, 200, 1000);

        GrpcRaftTransport transportA = new GrpcRaftTransport();
        GrpcRaftTransport transportB = new GrpcRaftTransport();
        transports.add(transportA);
        transports.add(transportB);

        RaftApplier noopApplier = (index, command) -> { };
        RaftNode nodeA = new RaftNode("a", "localhost:" + portA, members,
                new RaftLog(dir.resolve("a")), noopApplier, transportA, timings);
        RaftNode nodeB = new RaftNode("b", "localhost:" + portB, members,
                new RaftLog(dir.resolve("b")), noopApplier, transportB, timings);
        nodes.add(nodeA);
        nodes.add(nodeB);

        Server serverA = NettyServerBuilder.forPort(portA)
                .addService(new RaftConsensusServiceImpl(nodeA)).build().start();
        Server serverB = NettyServerBuilder.forPort(portB)
                .addService(new RaftConsensusServiceImpl(nodeB)).build().start();
        servers.add(serverA);
        servers.add(serverB);

        nodeA.start();
        nodeB.start();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> nodeA.isLeader() || nodeB.isLeader());

        long leaderCount = (nodeA.isLeader() ? 1 : 0) + (nodeB.isLeader() ? 1 : 0);
        assertTrue(leaderCount == 1, "exactly one of the two real-gRPC-wired nodes must become leader");
    }
}
