package com.nextgen.desktop.v2.integration;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.grpc.ClusterManagerServiceImpl;
import com.nextgen.proto.ClusterManagerGrpc;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for gRPC services.
 */
class gRPCIntegrationTest {

    private Server grpcServer;
    private ManagedChannel channel;
    private ClusterManagerGrpc.ClusterManagerBlockingStub blockingStub;
    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() throws IOException {
        dbManager = DatabaseManager.getInstance();
        String serverName = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder
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
        grpcServer.shutdownNow();
        grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testRequestJoin() {
        // Use unique IDs to avoid conflicts with leftover data
        String uniqueId = java.util.UUID.randomUUID().toString().substring(0, 8);

        // Create a server using RegistrationService so the join request can succeed
        var registrationService = new com.nextgen.desktop.v2.service.RegistrationService(dbManager);
        var server = registrationService.registerServer("TestServer-" + uniqueId, 50051);

        ControlPlaneProto.JoinRequest request = ControlPlaneProto.JoinRequest.newBuilder()
                .setNodeId("node-" + uniqueId)
                .setServerId(server.getId())
                .setNodeName("TestNode-" + uniqueId)
                .build();

        ControlPlaneProto.JoinResponse response = blockingStub.requestJoin(request);

        assertNotNull(response);
        // Response has approved (bool), message (string), server_certificate (bytes)
        assertTrue(response.getApproved());
    }

    @Test
    void testSendCommand() {
        ControlPlaneProto.CommandRequest request = ControlPlaneProto.CommandRequest.newBuilder()
                .setNodeId("command-node")
                .setCommand("STATUS")
                .setPayload("{}")
                .build();
        
        ControlPlaneProto.CommandResponse response = blockingStub.sendCommand(request);
        
        assertNotNull(response);
        assertTrue(response.getSuccess());
    }
}
