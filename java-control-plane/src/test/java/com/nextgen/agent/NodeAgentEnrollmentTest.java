package com.nextgen.agent;

import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.EnrollmentTokenStore;
import com.nextgen.security.PkiPaths;
import com.nextgen.security.RateLimitPolicy;
import com.nextgen.security.SecurityPolicy;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link NodeAgent}'s own {@code ensureEnrolled}/{@code buildChannel} production code
 * against a real server, rather than re-testing the CA/interceptor machinery those methods sit on top
 * of (that's {@code MutualTlsEndToEndTest}'s job).
 *
 * <p>This distinction matters: an earlier version of {@code buildChannel} always used the
 * enrolment-only (server-auth) TLS context, even for the ongoing operational channel after a
 * certificate had been issued — so a node could enrol successfully and then have every subsequent
 * heartbeat rejected. That bug was invisible to {@code MutualTlsEndToEndTest} because that test
 * builds its channels directly rather than calling {@code NodeAgent}'s own methods. These tests call
 * exactly what {@code NodeAgent.start()} calls.
 *
 * <p>{@code System.setProperty} is used for {@code NEXTGEN_ENROLLMENT_TOKEN} / {@code NEXTGEN_CA_CERT}
 * rather than environment variables: {@code EnvConfig} checks a same-named system property before the
 * environment, specifically so tests can inject configuration without touching the process
 * environment.
 */
class NodeAgentEnrollmentTest {

    private Server server;
    private ManagedChannel channelUnderTest;
    private CertificateAuthority ca;
    private EnrollmentTokenStore tokens;

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("NEXTGEN_ENROLLMENT_TOKEN");
        System.clearProperty("NEXTGEN_CA_CERT");
        if (channelUnderTest != null) {
            channelUnderTest.shutdownNow();
            channelUnderTest.awaitTermination(5, TimeUnit.SECONDS);
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

    private Path writeCaCert(Path dir) throws Exception {
        Path caFile = dir.resolve("ca-for-agent.crt");
        Files.writeString(caFile, ca.caCertificatePem());
        System.setProperty("NEXTGEN_CA_CERT", caFile.toString());
        return caFile;
    }

    // ── ensureEnrolled ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ensureEnrolled performs a real enrolment and NodeAgent's own buildChannel can then heartbeat")
    void ensureEnrolledActuallyEnrollsAndTheResultingChannelWorks(
            @TempDir Path pkiDir, @TempDir Path agentDir) throws Exception {
        int port = startServer(pkiDir);
        writeCaCert(agentDir);
        System.setProperty("NEXTGEN_ENROLLMENT_TOKEN", tokens.mint("agent-under-test"));

        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));
        NodeAgent.ensureEnrolled(credentials, "agent-under-test", "localhost", port,
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test");

        assertTrue(credentials.hasCertificate(), "ensureEnrolled must leave a usable certificate behind");

        // This is the actual regression check: NodeAgent's own buildChannel, not a hand-built one in
        // the test, must produce a channel the server accepts for an operational RPC.
        channelUnderTest = NodeAgent.buildChannel("localhost", port, true, credentials);
        var stub = com.nextgen.proto.ControlPlaneServiceGrpc.newBlockingStub(channelUnderTest);

        stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("agent-under-test").setIp("127.0.0.1").setPort(port)
                .setHostname("agent-under-test").build());
        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(
                ControlPlaneProto.HeartbeatRequest.newBuilder()
                        .setNodeId("agent-under-test").setCpu(10f).setCpuAvailable(true)
                        .setMemory(20f).setMemoryAvailable(true).build());

        assertEquals("OK", response.getStatus(),
                "the operational channel must present the issued certificate, not the enrolment-only "
                        + "context, or the server rejects this heartbeat as anonymous");
    }

    @Test
    @DisplayName("ensureEnrolled skips the network entirely when a valid certificate already exists")
    void ensureEnrolledSkipsWhenCertificateIsAlreadyValid(
            @TempDir Path pkiDir, @TempDir Path agentDir) throws Exception {
        startServer(pkiDir);
        writeCaCert(agentDir);
        String token = tokens.mint("agent-under-test");
        System.setProperty("NEXTGEN_ENROLLMENT_TOKEN", token);

        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));
        // First call performs the real enrolment.
        NodeAgent.ensureEnrolled(credentials, "agent-under-test", "localhost", server.getPort(),
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test");

        // The token was single-use and is already consumed. If this second call tried to enrol again
        // it would fail (INVALID_TOKEN) and throw. It must not throw, because it must not try.
        AgentCredentials reloaded = new AgentCredentials(agentDir.resolve("creds"));
        assertDoesNotThrow(() -> NodeAgent.ensureEnrolled(reloaded, "agent-under-test",
                "localhost", server.getPort(),
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test"));
    }

    @Test
    @DisplayName("ensureEnrolled fails fast with no certificate and no token, rather than hanging")
    void ensureEnrolledRequiresATokenWhenNoCertificateExists(
            @TempDir Path pkiDir, @TempDir Path agentDir) throws Exception {
        int port = startServer(pkiDir);
        writeCaCert(agentDir);
        // Deliberately no NEXTGEN_ENROLLMENT_TOKEN set.

        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                NodeAgent.ensureEnrolled(credentials, "agent-under-test", "localhost", port,
                        ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test"));
        assertTrue(failure.getMessage().contains("NEXTGEN_ENROLLMENT_TOKEN"), failure.getMessage());
    }

    @Test
    @DisplayName("ensureEnrolled fails fast without a CA certificate rather than trusting on first use")
    void ensureEnrolledRequiresACaCertificate(@TempDir Path agentDir) {
        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));

        // No NEXTGEN_CA_CERT, no server even running: the CA check must happen before any network
        // call, since trust-on-first-use is deliberately unsupported (see docs/ARCHITECTURE.md).
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                NodeAgent.ensureEnrolled(credentials, "agent-under-test", "localhost", 1,
                        ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test"));
        assertTrue(failure.getMessage().toLowerCase().contains("ca certificate"), failure.getMessage());
    }

    @Test
    @DisplayName("An invalid token is reported clearly rather than retried into a busy loop")
    void ensureEnrolledSurfacesRejectionReasonForAnInvalidToken(
            @TempDir Path pkiDir, @TempDir Path agentDir) throws Exception {
        int port = startServer(pkiDir);
        writeCaCert(agentDir);
        System.setProperty("NEXTGEN_ENROLLMENT_TOKEN", "not-a-real-token");

        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                NodeAgent.ensureEnrolled(credentials, "agent-under-test", "localhost", port,
                        ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "test"));
        assertTrue(failure.getMessage().contains("INVALID_TOKEN"), failure.getMessage());
        assertFalse(credentials.hasCertificate());
    }

    // ── buildChannel ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildChannel refuses to silently fall back to plaintext when TLS is enabled but no certificate exists")
    void buildChannelRefusesToFallBackToPlaintext(@TempDir Path agentDir) {
        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                NodeAgent.buildChannel("localhost", 1, true, credentials));
        assertTrue(failure.getMessage().contains("ensureEnrolled"), failure.getMessage());
    }

    @Test
    @DisplayName("buildChannel builds a plain channel without requiring any credentials when TLS is off")
    void buildChannelWorksPlaintextWithNoCredentials(@TempDir Path agentDir) {
        AgentCredentials credentials = new AgentCredentials(agentDir.resolve("creds"));

        channelUnderTest = assertDoesNotThrow(() ->
                NodeAgent.buildChannel("localhost", 1, false, credentials));
        assertNotNull(channelUnderTest);
    }
}
