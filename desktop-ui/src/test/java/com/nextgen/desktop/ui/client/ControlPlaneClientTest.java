package com.nextgen.desktop.ui.client;

import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ControlPlaneClient against an in-process gRPC server.
 *
 * <p>The central guarantee: a failed RPC surfaces as an exception, never as a success-shaped empty
 * result. {@code getNodes()} returning {@code List.of()} on failure is what let the dashboard render
 * a dead control plane as a healthy cluster with zero nodes.
 */
class ControlPlaneClientTest {

    private Server server;
    private ManagedChannel channel;

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

    private ControlPlaneClient clientFor(ControlPlaneServiceGrpc.ControlPlaneServiceImplBase service)
            throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new ControlPlaneClient(channel);
    }

    /** A service whose every RPC fails with the given status. */
    private static ControlPlaneServiceGrpc.ControlPlaneServiceImplBase failingWith(Status status) {
        return new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(ControlPlaneProto.Empty request,
                                 StreamObserver<ControlPlaneProto.NodeList> observer) {
                observer.onError(new StatusRuntimeException(status));
            }

            @Override
            public void submitTask(ControlPlaneProto.TaskRequest request,
                                   StreamObserver<ControlPlaneProto.TaskResponse> observer) {
                observer.onError(new StatusRuntimeException(status));
            }

            @Override
            public void registerNode(ControlPlaneProto.NodeInfo request,
                                     StreamObserver<ControlPlaneProto.RegisterResponse> observer) {
                observer.onError(new StatusRuntimeException(status));
            }

            @Override
            public void sendHeartbeat(ControlPlaneProto.HeartbeatRequest request,
                                      StreamObserver<ControlPlaneProto.HeartbeatResponse> observer) {
                observer.onError(new StatusRuntimeException(status));
            }
        };
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void getNodesReturnsWhatTheServerSent() throws IOException {
        ControlPlaneClient client = clientFor(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(ControlPlaneProto.Empty request,
                                 StreamObserver<ControlPlaneProto.NodeList> observer) {
                observer.onNext(ControlPlaneProto.NodeList.newBuilder()
                        .addNodes(ControlPlaneProto.NodeInfo.newBuilder().setNodeId("node1").build())
                        .build());
                observer.onCompleted();
            }
        });

        List<ControlPlaneProto.NodeInfo> nodes = client.getNodes();

        assertEquals(1, nodes.size());
        assertEquals("node1", nodes.get(0).getNodeId());
    }

    @Test
    void emptyClusterIsDistinguishableFromAFailure() throws IOException {
        ControlPlaneClient client = clientFor(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void getNodes(ControlPlaneProto.Empty request,
                                 StreamObserver<ControlPlaneProto.NodeList> observer) {
                observer.onNext(ControlPlaneProto.NodeList.getDefaultInstance());
                observer.onCompleted();
            }
        });

        // A genuinely empty cluster returns normally; only a failure throws.
        assertTrue(client.getNodes().isEmpty());
    }

    @Test
    void heartbeatCarriesTheAvailabilityFlags() throws IOException {
        var captured = new ControlPlaneProto.HeartbeatRequest[1];
        ControlPlaneClient client = clientFor(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void sendHeartbeat(ControlPlaneProto.HeartbeatRequest request,
                                      StreamObserver<ControlPlaneProto.HeartbeatResponse> observer) {
                captured[0] = request;
                observer.onNext(ControlPlaneProto.HeartbeatResponse.newBuilder().setStatus("OK").build());
                observer.onCompleted();
            }
        });

        client.sendHeartbeat("node1", 0f, false, 42f, true);

        assertFalse(captured[0].getCpuAvailable(),
                "an unreadable CPU must be flagged, not sent as a real 0.0");
        assertTrue(captured[0].getMemoryAvailable());
        assertEquals(42f, captured[0].getMemory(), 0.001);
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void getNodesThrowsInsteadOfReturningAnEmptyList() throws IOException {
        ControlPlaneClient client = clientFor(failingWith(Status.UNAVAILABLE));

        assertThrows(ControlPlaneUnavailableException.class, client::getNodes);
    }

    @Test
    void submitTaskThrowsOnFailure() throws IOException {
        ControlPlaneClient client = clientFor(failingWith(Status.UNAVAILABLE));

        assertThrows(ControlPlaneUnavailableException.class, () -> client.submitTask(
                "t1", ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE, "{}"));
    }

    @Test
    void registerNodeThrowsInsteadOfReturningAnErrorShapedResponse() throws IOException {
        ControlPlaneClient client = clientFor(failingWith(Status.UNAVAILABLE));

        assertThrows(ControlPlaneUnavailableException.class,
                () -> client.registerNode("node1", "10.0.0.1", 50051, "host"));
    }

    @Test
    void heartbeatThrowsOnFailure() throws IOException {
        ControlPlaneClient client = clientFor(failingWith(Status.UNAVAILABLE));

        assertThrows(ControlPlaneUnavailableException.class,
                () -> client.sendHeartbeat("node1", 1f, true, 1f, true));
    }

    @Test
    void shortReasonIsUserFacingAndCarriesNoStackTrace() throws IOException {
        ControlPlaneClient client = clientFor(failingWith(Status.UNAVAILABLE));

        ControlPlaneUnavailableException failure =
                assertThrows(ControlPlaneUnavailableException.class, client::getNodes);

        assertEquals("Control plane unreachable", failure.shortReason());
        assertFalse(failure.shortReason().contains("io.grpc"),
                "the status bar must not display internals: " + failure.shortReason());
    }

    @Test
    void distinctFailureCausesGetDistinctMessages() {
        assertEquals("Control plane unreachable", reasonFor(Status.UNAVAILABLE));
        assertEquals("Control plane timed out", reasonFor(Status.DEADLINE_EXCEEDED));
        assertEquals("Authentication rejected", reasonFor(Status.UNAUTHENTICATED));
        assertEquals("Permission denied", reasonFor(Status.PERMISSION_DENIED));
        assertTrue(reasonFor(Status.INTERNAL).contains("INTERNAL"));
    }

    private static String reasonFor(Status status) {
        return new ControlPlaneUnavailableException("op", new StatusRuntimeException(status))
                .shortReason();
    }
}
