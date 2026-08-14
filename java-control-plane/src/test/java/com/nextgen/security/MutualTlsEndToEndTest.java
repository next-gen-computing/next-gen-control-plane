package com.nextgen.security;

import com.google.protobuf.ByteString;
import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import com.nextgen.proto.NodeEnrollmentGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end mutual TLS over a real Netty transport on an ephemeral port.
 *
 * <p>This is the authoritative verification for Phase 3: it exercises the actual TLS handshake,
 * certificate validation and interceptor chain, not an in-process shortcut.
 */
class MutualTlsEndToEndTest {

    private Server server;
    private final List<ManagedChannel> channels = new ArrayList<>();
    private CertificateAuthority ca;
    private EnrollmentTokenStore tokens;

    @AfterEach
    void tearDown() throws Exception {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Starts a TLS-enforcing control plane on an ephemeral port. */
    private int startServer(Path pkiDir) throws Exception {
        SecurityPolicy policy = new SecurityPolicy(true, true,
                List.of("localhost", "127.0.0.1"), Duration.ofMinutes(60), RateLimitPolicy.defaults());
        ca = new CertificateAuthority(new PkiPaths(pkiDir), Clock.systemUTC());
        tokens = new EnrollmentTokenStore(Clock.systemUTC(), Duration.ofMinutes(60));
        NodeRegistry registry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);

        server = ControlPlaneServer.buildGrpcServer(0, registry, policy, ca, tokens).start();
        return server.getPort();
    }

    private ManagedChannel channel(int port, io.grpc.netty.shaded.io.netty.handler.ssl.SslContext ssl) {
        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", port)
                .overrideAuthority("localhost")
                .sslContext(ssl)
                .build();
        channels.add(channel);
        return channel;
    }

    /** Runs the full enrolment exchange and returns the issued material. */
    private Enrolled enroll(int port, String nodeId, String token) throws Exception {
        ManagedChannel enrollmentChannel =
                channel(port, TlsConfig.enrollmentClientContext(ca.caCertificatePem()));

        Metadata headers = new Metadata();
        headers.put(com.nextgen.controlplane.NodeEnrollmentServiceImpl.TOKEN_HEADER, token);

        ControlPlaneProto.EnrollResponse response = NodeEnrollmentGrpc
                .newBlockingStub(enrollmentChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .enroll(buildEnrollRequest(nodeId));

        return new Enrolled(response, enrollmentChannel);
    }

    private ControlPlaneProto.EnrollRequest buildEnrollRequest(String nodeId) {
        com.nextgen.agent.AgentCredentials credentials =
                new com.nextgen.agent.AgentCredentials(Path.of(System.getProperty("java.io.tmpdir"),
                        "nextgen-test-" + nodeId + "-" + System.nanoTime()));
        String csr = credentials.createCsr(nodeId);
        lastCredentials = credentials;
        return ControlPlaneProto.EnrollRequest.newBuilder()
                .setNodeId(nodeId)
                .setCsrPem(ByteString.copyFrom(csr, StandardCharsets.UTF_8))
                .build();
    }

    private com.nextgen.agent.AgentCredentials lastCredentials;

    private record Enrolled(ControlPlaneProto.EnrollResponse response, ManagedChannel channel) {
    }

    // ── Enrolment ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A node with a valid token can enrol without a client certificate")
    void agentWithoutCertificateCanEnroll(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        String token = tokens.mint("node1");

        ControlPlaneProto.EnrollResponse response = enroll(port, "node1", token).response();

        assertEquals(ControlPlaneProto.EnrollmentResult.ENROLLMENT_RESULT_ISSUED, response.getResult());
        assertFalse(response.getClientCertificatePem().isEmpty());
        assertFalse(response.getCaCertificatePem().isEmpty());
        assertTrue(response.getNotAfterEpochMillis() > response.getNotBeforeEpochMillis());
    }

    @Test
    @DisplayName("A token works exactly once")
    void tokenCannotBeReused(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        String token = tokens.mint("node1");
        enroll(port, "node1", token);

        ControlPlaneProto.EnrollResponse second = enroll(port, "node1", token).response();

        assertEquals(ControlPlaneProto.EnrollmentResult.ENROLLMENT_RESULT_INVALID_TOKEN,
                second.getResult());
        assertTrue(second.getClientCertificatePem().isEmpty(),
                "a rejected enrolment must not leak certificate bytes");
    }

    @Test
    @DisplayName("An unknown token is rejected")
    void unknownTokenIsRejected(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);

        ControlPlaneProto.EnrollResponse response =
                enroll(port, "node1", "not-a-real-token").response();

        assertEquals(ControlPlaneProto.EnrollmentResult.ENROLLMENT_RESULT_INVALID_TOKEN,
                response.getResult());
    }

    @Test
    @DisplayName("A CSR claiming a different identity still yields the token's identity")
    void csrCannotChooseItsOwnIdentity(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        String token = tokens.mint("node7");

        // The request asks to be "admin"; the token is bound to "node7".
        ManagedChannel enrollmentChannel =
                channel(port, TlsConfig.enrollmentClientContext(ca.caCertificatePem()));
        Metadata headers = new Metadata();
        headers.put(com.nextgen.controlplane.NodeEnrollmentServiceImpl.TOKEN_HEADER, token);

        com.nextgen.agent.AgentCredentials credentials = new com.nextgen.agent.AgentCredentials(
                Path.of(System.getProperty("java.io.tmpdir"), "nextgen-test-" + System.nanoTime()));
        ControlPlaneProto.EnrollResponse response = NodeEnrollmentGrpc.newBlockingStub(enrollmentChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .enroll(ControlPlaneProto.EnrollRequest.newBuilder()
                        .setNodeId("admin")
                        .setCsrPem(ByteString.copyFrom(credentials.createCsr("admin"),
                                StandardCharsets.UTF_8))
                        .build());

        X509Certificate issued = CertificateAuthority.readCertificate(
                response.getClientCertificatePem().toStringUtf8());
        assertEquals("node7", PeerIdentityInterceptor.commonNameOf(issued),
                "the token's binding must win over anything the client asks for");
    }

    @Test
    @DisplayName("A malformed CSR is rejected without a certificate")
    void malformedCsrIsRejected(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        String token = tokens.mint("node1");

        ManagedChannel enrollmentChannel =
                channel(port, TlsConfig.enrollmentClientContext(ca.caCertificatePem()));
        Metadata headers = new Metadata();
        headers.put(com.nextgen.controlplane.NodeEnrollmentServiceImpl.TOKEN_HEADER, token);

        ControlPlaneProto.EnrollResponse response = NodeEnrollmentGrpc.newBlockingStub(enrollmentChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .enroll(ControlPlaneProto.EnrollRequest.newBuilder()
                        .setNodeId("node1")
                        .setCsrPem(ByteString.copyFromUtf8("this is not a CSR"))
                        .build());

        assertEquals(ControlPlaneProto.EnrollmentResult.ENROLLMENT_RESULT_CSR_INVALID,
                response.getResult());
        assertTrue(response.getClientCertificatePem().isEmpty());
    }

    // ── The acceptance criterion ─────────────────────────────────────────────

    @Test
    @DisplayName("A node WITHOUT a valid client certificate cannot join")
    void agentWithoutCertificateCannotHeartbeat(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        ManagedChannel plainTls =
                channel(port, TlsConfig.enrollmentClientContext(ca.caCertificatePem()));

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                ControlPlaneServiceGrpc.newBlockingStub(plainTls)
                        .sendHeartbeat(ControlPlaneProto.HeartbeatRequest.newBuilder()
                                .setNodeId("node1").setCpuAvailable(true).build()));

        assertEquals(Status.Code.UNAUTHENTICATED, failure.getStatus().getCode());
        assertTrue(String.valueOf(failure.getStatus().getDescription()).contains("certificate"),
                failure.getStatus().getDescription());
    }

    @Test
    @DisplayName("A certificate from a DIFFERENT CA is rejected at the handshake")
    void certificateFromAnotherCaIsRejected(@TempDir Path pkiDir, @TempDir Path rogueDir)
            throws Exception {
        int port = startServer(pkiDir);

        // A completely separate CA issues a perfectly well-formed certificate for "node1".
        CertificateAuthority rogueCa = new CertificateAuthority(new PkiPaths(rogueDir), Clock.systemUTC());
        com.nextgen.agent.AgentCredentials rogueCreds = new com.nextgen.agent.AgentCredentials(
                Path.of(System.getProperty("java.io.tmpdir"), "nextgen-rogue-" + System.nanoTime()));
        String csr = rogueCreds.createCsr("node1");
        CertificateAuthority.IssuedCertificate rogueCert = rogueCa.issueClientCertificate("node1",
                com.nextgen.controlplane.NodeEnrollmentServiceImpl.parseCsr(csr));

        ManagedChannel rogue = channel(port, TlsConfig.mutualClientContext(
                ca.caCertificatePem(), rogueCert.certificate(), rogueCreds.privateKey()));

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                ControlPlaneServiceGrpc.newBlockingStub(rogue)
                        .getNodes(ControlPlaneProto.Empty.getDefaultInstance()));

        // A rejected certificate surfaces as a TRANSPORT failure, not UNAUTHENTICATED, and the exact
        // code is timing-dependent. Asserting a single code here makes the test flaky.
        assertTrue(failure.getStatus().getCode() == Status.Code.UNAVAILABLE
                        || failure.getStatus().getCode() == Status.Code.INTERNAL
                        || failure.getStatus().getCode() == Status.Code.UNKNOWN,
                "unexpected status: " + failure.getStatus());
        assertTrue(hasHandshakeFailureInCauseChain(failure),
                "expected an SSL handshake failure in the cause chain");
    }

    private static boolean hasHandshakeFailureInCauseChain(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof SSLHandshakeException
                    || String.valueOf(t.getMessage()).toLowerCase().contains("handshake")
                    || String.valueOf(t.getMessage()).toLowerCase().contains("certificate")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("After enrolling, a node can heartbeat once it rebuilds its channel")
    void enrolledAgentCanHeartbeatAfterChannelRebuild(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        String token = tokens.mint("node1");

        Enrolled enrolled = enroll(port, "node1", token);
        assertEquals(ControlPlaneProto.EnrollmentResult.ENROLLMENT_RESULT_ISSUED,
                enrolled.response().getResult());

        // TLS client auth happens once per connection, at the handshake, and gRPC/Netty exposes no
        // TLS 1.3 post-handshake auth. The enrolment channel therefore CANNOT be upgraded in place —
        // it must be shut down and a new channel built with the issued key material.
        enrolled.channel().shutdownNow();

        X509Certificate clientCert = CertificateAuthority.readCertificate(
                enrolled.response().getClientCertificatePem().toStringUtf8());
        ManagedChannel mtls = channel(port, TlsConfig.mutualClientContext(
                ca.caCertificatePem(), clientCert, lastCredentials.privateKey()));

        ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub =
                ControlPlaneServiceGrpc.newBlockingStub(mtls);
        stub.registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1").setIp("127.0.0.1").setPort(port).setHostname("node1").build());

        ControlPlaneProto.HeartbeatResponse response = stub.sendHeartbeat(
                ControlPlaneProto.HeartbeatRequest.newBuilder()
                        .setNodeId("node1").setCpu(10f).setCpuAvailable(true)
                        .setMemory(20f).setMemoryAvailable(true).build());

        assertEquals("OK", response.getStatus());
    }

    @Test
    @DisplayName("Revoking a certificate breaks the NEXT RPC on an already-open channel")
    void revocationTakesEffectOnAnOpenConnection(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        String token = tokens.mint("node1");
        Enrolled enrolled = enroll(port, "node1", token);
        enrolled.channel().shutdownNow();

        X509Certificate clientCert = CertificateAuthority.readCertificate(
                enrolled.response().getClientCertificatePem().toStringUtf8());
        ManagedChannel mtls = channel(port, TlsConfig.mutualClientContext(
                ca.caCertificatePem(), clientCert, lastCredentials.privateKey()));
        ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub =
                ControlPlaneServiceGrpc.newBlockingStub(mtls);

        assertDoesNotThrow(() -> stub.getNodes(ControlPlaneProto.Empty.getDefaultInstance()));

        // This is the property a CRL cannot provide: a CRL is consulted at handshake time, so
        // revoking would leave this long-lived channel working until it happened to reconnect.
        ca.denylist().revoke(clientCert.getSerialNumber(),
                clientCert.getNotAfter().toInstant(), "compromised");

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> stub.getNodes(ControlPlaneProto.Empty.getDefaultInstance()));
        assertEquals(Status.Code.UNAUTHENTICATED, failure.getStatus().getCode());
        assertEquals("certificate_revoked", failure.getStatus().getDescription());
    }

    @Test
    @DisplayName("Enrolment can be closed off after provisioning")
    void enrolmentCanBeDisabled(@TempDir Path pkiDir) throws Exception {
        SecurityPolicy closed = new SecurityPolicy(true, false,
                List.of("localhost", "127.0.0.1"), Duration.ofMinutes(60), RateLimitPolicy.defaults());
        ca = new CertificateAuthority(new PkiPaths(pkiDir), Clock.systemUTC());
        tokens = new EnrollmentTokenStore(Clock.systemUTC(), Duration.ofMinutes(60));
        server = ControlPlaneServer.buildGrpcServer(0,
                new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis),
                closed, ca, tokens).start();

        String token = tokens.mint("node1");
        ManagedChannel enrollmentChannel =
                channel(server.getPort(), TlsConfig.enrollmentClientContext(ca.caCertificatePem()));
        Metadata headers = new Metadata();
        headers.put(com.nextgen.controlplane.NodeEnrollmentServiceImpl.TOKEN_HEADER, token);

        // With enrolment closed the method is no longer on the anonymous allowlist, so policy
        // rejects it before the service is reached.
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                NodeEnrollmentGrpc.newBlockingStub(enrollmentChannel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .enroll(buildEnrollRequest("node1")));

        assertEquals(Status.Code.UNAUTHENTICATED, failure.getStatus().getCode());
        assertEquals("enrollment_disabled", failure.getStatus().getDescription());
    }
}
