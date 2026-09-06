package com.nextgen.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.task.BuildContextStore;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.PortRelayManager;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.training.JobOutcomeLogger;
import com.nextgen.proto.ControlPlaneProto.TunnelFrame;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, in-process gRPC end to end: a real external TCP consumer connects to a relay port, a fake
 * "node" (playing {@link com.nextgen.agent.task.PortTunnelClient}'s role, over the real
 * {@code tunnelPort} RPC) receives the forwarded bytes and echoes them back — proving the whole
 * {@code ControlPlaneServiceImpl.tunnelPort} ↔ {@link PortRelayManager} wiring works over the wire, not
 * just at the unit level (see {@code PortRelayManagerTest} for that).
 */
class TunnelPortIntegrationTest {

    private static final int RANGE_START = 41200;
    private static final int RANGE_END = 41299;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub;
    private ControlPlaneServiceImpl service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        NodeRegistry nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);
        TaskRegistry taskRegistry = new TaskRegistry();
        NodeTaskChannelRegistry channelRegistry = new NodeTaskChannelRegistry();
        BuildContextStore buildContextStore = new BuildContextStore(tempDir, 60_000, 60_000);
        PortRelayManager portRelayManager = new PortRelayManager(RANGE_START, RANGE_END);
        service = new ControlPlaneServiceImpl(nodeRegistry, new RoundRobinScheduler(),
                taskRegistry, channelRegistry, new HeuristicNodeCapacityScorer(), JobOutcomeLogger.noop(),
                new JobRegistry(), null, null, buildContextStore, portRelayManager);

        server = InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        asyncStub = ControlPlaneServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void aRealConsumerConnectionRoundTripsThroughTheRelayToAFakeNodeAndBack() throws Exception {
        int port = service.portRelayManager().reservePort("proj", "echo-service");

        LinkedBlockingQueue<TunnelFrame> nodeReceived = new LinkedBlockingQueue<>();
        StreamObserver<TunnelFrame> nodeOutbound = asyncStub.tunnelPort(new StreamObserver<>() {
            @Override
            public void onNext(TunnelFrame frame) {
                nodeReceived.add(frame);
            }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });

        String hello = MAPPER.createObjectNode()
                .put("project_name", "proj")
                .put("service_name", "echo-service")
                .toString();
        nodeOutbound.onNext(TunnelFrame.newBuilder().setData(ByteString.copyFromUtf8(hello)).build());

        try (Socket consumer = new Socket("localhost", port)) {
            OutputStream out = consumer.getOutputStream();
            out.write("hello-relay".getBytes(StandardCharsets.UTF_8));
            out.flush();

            TunnelFrame forwarded = nodeReceived.poll(5, TimeUnit.SECONDS);
            assertNotNull(forwarded, "the real consumer's bytes must reach the fake node over the real RPC");
            assertEquals("hello-relay", forwarded.getData().toStringUtf8());

            // The fake node "echoes" — exactly what a real PortTunnelClient forwarding a local socket's
            // reply would send back up the same stream.
            nodeOutbound.onNext(TunnelFrame.newBuilder()
                    .setTunnelId(forwarded.getTunnelId())
                    .setData(ByteString.copyFromUtf8("hello-relay"))
                    .build());

            InputStream in = consumer.getInputStream();
            byte[] buffer = new byte["hello-relay".length()];
            int totalRead = 0;
            while (totalRead < buffer.length) {
                int read = in.read(buffer, totalRead, buffer.length - totalRead);
                assertTrue(read > 0, "the echoed reply must reach the real consumer socket");
                totalRead += read;
            }
            assertEquals("hello-relay", new String(buffer, StandardCharsets.UTF_8));
        }
    }

    @Test
    void aStreamForAnUnreservedServiceIsRejected() throws InterruptedException {
        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StreamObserver<TunnelFrame> nodeOutbound = asyncStub.tunnelPort(new StreamObserver<>() {
            @Override public void onNext(TunnelFrame value) { }
            @Override public void onError(Throwable t) { errors.add(t); }
            @Override public void onCompleted() { }
        });

        String hello = MAPPER.createObjectNode()
                .put("project_name", "proj")
                .put("service_name", "never-reserved")
                .toString();
        nodeOutbound.onNext(TunnelFrame.newBuilder().setData(ByteString.copyFromUtf8(hello)).build());

        Throwable error = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error, "a hello for an unreserved (project, service) must be rejected, not silently accepted");
    }
}
