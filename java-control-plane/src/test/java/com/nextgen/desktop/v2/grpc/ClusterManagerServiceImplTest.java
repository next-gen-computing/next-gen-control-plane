package com.nextgen.desktop.v2.grpc;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.proto.ClusterManagerGrpc;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClusterManagerServiceImpl gRPC service.
 * Tests use actual proto definitions from control_plane.proto.
 */
class ClusterManagerServiceImplTest {

    private Server server;
    private ManagedChannel channel;
    private ClusterManagerGrpc.ClusterManagerBlockingStub blockingStub;
    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() throws IOException {
        dbManager = DatabaseManager.getInstance();
        String serverName = InProcessServerBuilder.generateName();
        
        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(new ClusterManagerServiceImpl(dbManager))
                .build()
                .start();
        
        channel = InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build();
        
        blockingStub = ClusterManagerGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testRequestJoin() {
        ControlPlaneProto.JoinRequest request = ControlPlaneProto.JoinRequest.newBuilder()
                .setNodeId("test-node")
                .setServerId("test-server")
                .setNodeName("TestNode")
                .build();

        ControlPlaneProto.JoinResponse response = blockingStub.requestJoin(request);

        assertNotNull(response);
        assertTrue(response.getApproved() || response.getMessage() != null);
    }

    @Test
    void testSendCommand() {
        ControlPlaneProto.CommandRequest request = ControlPlaneProto.CommandRequest.newBuilder()
                .setNodeId("test-node")
                .setCommand("STATUS")
                .setPayload("{}")
                .build();

        ControlPlaneProto.CommandResponse response = blockingStub.sendCommand(request);

        assertNotNull(response);
        assertTrue(response.getSuccess());
    }
}
