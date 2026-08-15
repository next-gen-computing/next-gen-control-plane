package com.nextgen.agent;

import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.EnrollmentTokenStore;
import com.nextgen.security.PkiPaths;
import com.nextgen.security.RateLimitPolicy;
import com.nextgen.security.SecurityPolicy;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link NodeAgent.CertificateRenewalLoop} directly — {@code tick()} is package-private
 * precisely so tests can call it without waiting on real scheduling, following the same convention
 * {@code NodeAgent.HeartbeatLoop} documents for itself.
 *
 * <p>This was previously untested entirely: {@code CERT_RENEW_WINDOW_MINUTES} was only ever checked
 * once, at {@code ensureEnrolled} time, and nothing ever re-checked it — see README.md/
 * docs/ARCHITECTURE.md's own "named limitation" callout that this class fixes.
 */
class CertificateRenewalLoopTest {

    private Server server;
    private ScheduledExecutorService scheduler;
    private CertificateAuthority ca;
    private EnrollmentTokenStore tokens;

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private int startServer(Path pkiDir) throws Exception {
        SecurityPolicy policy = new SecurityPolicy(true, true,
                List.of("localhost", "127.0.0.1"), Duration.ofMinutes(60), RateLimitPolicy.defaults());
        ca = new CertificateAuthority(new PkiPaths(pkiDir), Clock.systemUTC());
        tokens = new EnrollmentTokenStore(Clock.systemUTC(), Duration.ofMinutes(60));
        NodeRegistry registry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);

        server = ControlPlaneServer.buildGrpcServer(0, registry, policy, ca, tokens).start();
        return server.getPort();
    }

    private AgentCredentials enrolledCredentials(Path dir, int port, String nodeId)
            throws Exception {
        Files.writeString(dir.resolve("ca.crt"), ca.caCertificatePem());
        System.setProperty("NEXTGEN_CA_CERT", dir.resolve("ca.crt").toString());
        System.setProperty("NEXTGEN_ENROLLMENT_TOKEN", tokens.mint(nodeId));
        try {
            AgentCredentials credentials = new AgentCredentials(dir.resolve("creds"));
            NodeAgent.ensureEnrolled(credentials, nodeId, "localhost", port,
                    ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test");
            return credentials;
        } finally {
            System.clearProperty("NEXTGEN_CA_CERT");
            System.clearProperty("NEXTGEN_ENROLLMENT_TOKEN");
        }
    }

    private NodeAgent.CertificateRenewalLoop loopFor(ControlPlaneConnection connection,
            AgentCredentials credentials, String nodeId, Duration renewWindow) {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        return new NodeAgent.CertificateRenewalLoop(scheduler, connection, credentials, nodeId,
                renewWindow, Duration.ofDays(1).toMillis(), BackoffPolicy.defaultPolicy());
    }

    @Test
    @DisplayName("tick() renews when the certificate is within the renewal window, and the new cert works")
    void tickRenewsWhenWithinTheWindow(@TempDir Path pkiDir, @TempDir Path agentDir)
            throws Exception {
        int port = startServer(pkiDir);
        AgentCredentials credentials = enrolledCredentials(agentDir, port, "node1");
        BigInteger originalSerial = credentials.certificate().orElseThrow().getSerialNumber();
        PrivateKey originalKey = credentials.privateKey();

        ControlPlaneConnection connection =
                new ControlPlaneConnection(ControlPlaneEndpoints.single("localhost", port), true, credentials);
        // A renewal window far longer than the certificate's own lifetime guarantees needsRenewal()
        // returns true unconditionally, without this test needing to know or fake the CA's exact
        // certificate lifetime.
        NodeAgent.CertificateRenewalLoop loop =
                loopFor(connection, credentials, "node1", Duration.ofDays(365 * 50));

        loop.tick();

        BigInteger renewedSerial = credentials.certificate().orElseThrow().getSerialNumber();
        assertNotEquals(originalSerial, renewedSerial,
                "tick() must have replaced the certificate with a genuinely new one");
        assertNotEquals(originalKey, credentials.privateKey(),
                "renewal generates a fresh key pair, not just a new certificate over the old key");

        // The SAME connection object must keep working afterward — this is the actual regression check
        // for invalidateCurrentChannel(): the old certificate was just revoked server-side as part of
        // issuing the new one, so if the cached channel weren't discarded, this call would fail as
        // certificate_revoked (see ControlPlaneConnection.invalidateCurrentChannel's own Javadoc).
        connection.blockingStub().registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1").setIp("127.0.0.1").setPort(port).setHostname("node1").build());
        var onSameConnection = connection.blockingStub().sendHeartbeat(
                ControlPlaneProto.HeartbeatRequest.newBuilder()
                        .setNodeId("node1").setCpu(1f).setCpuAvailable(true)
                        .setMemory(1f).setMemoryAvailable(true).build());
        assertEquals("OK", onSameConnection.getStatus(),
                "the connection must keep working after renewal, not keep presenting the revoked cert");

        // Also prove the renewed credentials are independently usable, on a brand fresh channel.
        var freshChannel = NodeAgent.buildChannel("localhost", port, true, credentials);
        try {
            var stub = com.nextgen.proto.ControlPlaneServiceGrpc.newBlockingStub(freshChannel);
            var response = stub.sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                    .setNodeId("node1").setCpu(1f).setCpuAvailable(true)
                    .setMemory(1f).setMemoryAvailable(true).build());
            assertEquals("OK", response.getStatus());
        } finally {
            freshChannel.shutdownNow();
        }
        connection.shutdown();
    }

    @Test
    @DisplayName("tick() does nothing when the certificate is not yet due for renewal")
    void tickSkipsWhenNotDue(@TempDir Path pkiDir, @TempDir Path agentDir)
            throws Exception {
        int port = startServer(pkiDir);
        AgentCredentials credentials = enrolledCredentials(agentDir, port, "node1");
        BigInteger originalSerial = credentials.certificate().orElseThrow().getSerialNumber();

        ControlPlaneConnection connection =
                new ControlPlaneConnection(ControlPlaneEndpoints.single("localhost", port), true, credentials);
        // A zero-length window: only an already-expired certificate would need renewal, and a freshly
        // issued one is nowhere close.
        NodeAgent.CertificateRenewalLoop loop = loopFor(connection, credentials, "node1", Duration.ZERO);

        loop.tick();

        assertEquals(originalSerial, credentials.certificate().orElseThrow().getSerialNumber(),
                "tick() must not touch a certificate that isn't due for renewal yet");
        connection.shutdown();
    }

    @Test
    @DisplayName("A failed renewal attempt leaves the still-valid on-disk credentials usable, not corrupted")
    void failedRenewalReloadsLastKnownGoodCredentials(
            @TempDir Path pkiDir, @TempDir Path agentDir) throws Exception {
        int port = startServer(pkiDir);
        AgentCredentials credentials = enrolledCredentials(agentDir, port, "node1");
        BigInteger originalSerial = credentials.certificate().orElseThrow().getSerialNumber();
        PrivateKey originalKey = credentials.privateKey();

        // Stop the server before renewal is attempted, so the RPC fails AFTER createCsr() has already
        // overwritten the in-memory key pair — the exact failure window that would otherwise leave the
        // agent holding a private key that matches nothing on disk.
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);

        ControlPlaneConnection connection =
                new ControlPlaneConnection(ControlPlaneEndpoints.single("localhost", port), true, credentials);
        NodeAgent.CertificateRenewalLoop loop =
                loopFor(connection, credentials, "node1", Duration.ofDays(365 * 50));

        assertDoesNotThrow(loop::tick, "tick() must swallow the failure internally, never propagate it");

        assertEquals(originalSerial, credentials.certificate().orElseThrow().getSerialNumber(),
                "on failure, credentials must be reloaded from disk — still the original certificate");
        assertEquals(originalKey, credentials.privateKey(),
                "the in-memory key pair must be restored to match what's actually on disk, not left as "
                        + "the orphaned fresh key createCsr() generated before the RPC failed");
        connection.shutdown();
    }
}
