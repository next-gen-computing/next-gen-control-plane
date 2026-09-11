package com.nextgen.security;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for certificate policy enforcement and peer identity extraction.
 */
class MtlsPolicyInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static SecurityPolicy enforcing() {
        return new SecurityPolicy(true, true, List.of("localhost"),
                Duration.ofMinutes(60), RateLimitPolicy.defaults());
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> call(String method, AtomicReference<Status> closed) {
        ServerCall<Object, Object> serverCall = mock(ServerCall.class);
        MethodDescriptor<Object, Object> descriptor = mock(MethodDescriptor.class);
        when(descriptor.getFullMethodName()).thenReturn(method);
        when(serverCall.getMethodDescriptor()).thenReturn(descriptor);
        when(serverCall.getAttributes()).thenReturn(Attributes.EMPTY);
        if (closed != null) {
            doAnswer(invocation -> {
                closed.set(invocation.getArgument(0));
                return null;
            }).when(serverCall).close(any(Status.class), any(Metadata.class));
        }
        return serverCall;
    }

    @SuppressWarnings("unchecked")
    private static ServerCallHandler<Object, Object> handler(AtomicInteger allowed) {
        ServerCallHandler<Object, Object> h = mock(ServerCallHandler.class);
        when(h.startCall(any(), any())).thenAnswer(invocation -> {
            allowed.incrementAndGet();
            return mock(ServerCall.Listener.class);
        });
        return h;
    }

    private static MtlsPolicyInterceptor interceptor(SecurityPolicy policy, Path dir) {
        return new MtlsPolicyInterceptor(policy,
                new CertificateDenylist(dir.resolve("revoked.txt"), Duration.ofSeconds(5),
                        System::nanoTime),
                CLOCK);
    }

    // ── Policy evaluation ────────────────────────────────────────────────────

    @Test
    void enrolmentIsReachableWithoutAClientCertificate(@TempDir Path dir) {
        assertNull(interceptor(enforcing(), dir)
                .evaluate("nextgen.v1.NodeEnrollment/Enroll", PeerIdentity.ANONYMOUS));
    }

    @Test
    void heartbeatWithoutAClientCertificateIsDenied(@TempDir Path dir) {
        assertEquals("no_client_certificate", interceptor(enforcing(), dir)
                .evaluate("nextgen.v1.ControlPlaneService/SendHeartbeat", PeerIdentity.ANONYMOUS));
    }

    @Test
    void renewalRequiresAClientCertificate(@TempDir Path dir) {
        // Renewal is deliberately NOT on the anonymous allowlist: it is authenticated by the
        // certificate being replaced, so it needs no token and must not be reachable without one.
        assertEquals("no_client_certificate", interceptor(enforcing(), dir)
                .evaluate("nextgen.v1.NodeEnrollment/RenewCertificate", PeerIdentity.ANONYMOUS));
    }

    @Test
    void validCertificateIsAllowed(@TempDir Path dir) {
        PeerIdentity identity = new PeerIdentity("node1", BigInteger.ONE, NOW.plusSeconds(3600), false);

        assertNull(interceptor(enforcing(), dir)
                .evaluate("nextgen.v1.ControlPlaneService/SendHeartbeat", identity));
    }

    @Test
    void expiredCertificateIsDenied(@TempDir Path dir) {
        PeerIdentity expired = new PeerIdentity("node1", BigInteger.ONE, NOW.minusSeconds(1), false);

        assertEquals("certificate_expired", interceptor(enforcing(), dir)
                .evaluate("nextgen.v1.ControlPlaneService/SendHeartbeat", expired));
    }

    @Test
    void revokedCertificateIsDenied(@TempDir Path dir) {
        CertificateDenylist denylist = new CertificateDenylist(
                dir.resolve("revoked.txt"), Duration.ofSeconds(5), System::nanoTime);
        denylist.revoke(BigInteger.valueOf(9), NOW.plusSeconds(3600), "compromised");
        MtlsPolicyInterceptor policy = new MtlsPolicyInterceptor(enforcing(), denylist, CLOCK);

        PeerIdentity identity =
                new PeerIdentity("node1", BigInteger.valueOf(9), NOW.plusSeconds(3600), false);

        assertEquals("certificate_revoked",
                policy.evaluate("nextgen.v1.ControlPlaneService/SendHeartbeat", identity));
    }

    @Test
    void enrolmentIsDeniedWhenClosed(@TempDir Path dir) {
        SecurityPolicy closed = new SecurityPolicy(true, false, List.of("localhost"),
                Duration.ofMinutes(60), RateLimitPolicy.defaults());

        assertEquals("enrollment_disabled", interceptor(closed, dir)
                .evaluate("nextgen.v1.NodeEnrollment/Enroll", PeerIdentity.ANONYMOUS));
    }

    // ── Interception behaviour ───────────────────────────────────────────────

    @Test
    void deniedCallIsClosedWithUnauthenticatedAndANonNullListener(@TempDir Path dir) {
        AtomicReference<Status> closed = new AtomicReference<>();
        AtomicInteger allowed = new AtomicInteger();

        ServerCall.Listener<Object> listener = interceptor(enforcing(), dir).interceptCall(
                call("nextgen.v1.ControlPlaneService/SendHeartbeat", closed),
                new Metadata(), handler(allowed));

        assertEquals(0, allowed.get());
        assertEquals(Status.Code.UNAUTHENTICATED, closed.get().getCode());
        assertNotNull(listener, "gRPC NPEs on a null listener");
    }

    @Test
    void permissiveModeAllowsWhatItWouldOtherwiseDeny(@TempDir Path dir) {
        AtomicInteger allowed = new AtomicInteger();
        AtomicReference<Status> closed = new AtomicReference<>();
        MtlsPolicyInterceptor permissive = new MtlsPolicyInterceptor(
                SecurityPolicy.permissive(),
                new CertificateDenylist(dir.resolve("revoked.txt"), Duration.ofSeconds(5),
                        System::nanoTime),
                CLOCK);

        permissive.interceptCall(call("nextgen.v1.ControlPlaneService/SendHeartbeat", closed),
                new Metadata(), handler(allowed));

        // This is what keeps existing plaintext deployments and the in-process test suite working
        // while making their readiness for enforcement observable.
        assertEquals(1, allowed.get());
        assertNull(closed.get());
    }

    // ── Peer identity extraction ─────────────────────────────────────────────

    @Test
    void noSslSessionYieldsAnonymous() {
        assertTrue(PeerIdentityInterceptor.extract(call("any/Method", null)).anonymous());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unverifiedPeerYieldsAnonymous() throws Exception {
        ServerCall<Object, Object> serverCall = mock(ServerCall.class);
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("no cert"));
        when(serverCall.getAttributes()).thenReturn(Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());

        // The normal case for a client connecting without a certificate under ClientAuth.OPTIONAL.
        assertTrue(PeerIdentityInterceptor.extract(serverCall).anonymous());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyCertificateChainYieldsAnonymous() throws Exception {
        ServerCall<Object, Object> serverCall = mock(ServerCall.class);
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[0]);
        when(serverCall.getAttributes()).thenReturn(Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());

        assertTrue(PeerIdentityInterceptor.extract(serverCall).anonymous());
    }

    @Test
    void commonNameWithAnEscapedCommaIsParsedCorrectly(@TempDir Path dir) throws Exception {
        // Regression test for the LdapName decision: a DN is not naively comma-separated, and any
        // regex or split(",") implementation gets "node,1" wrong — which means one node can be
        // mistaken for another.
        CertificateAuthority ca = new CertificateAuthority(new PkiPaths(dir), Clock.systemUTC());
        var keys = CertificateAuthority.generateKeyPair();
        var csr = new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(
                new org.bouncycastle.asn1.x500.X500Name("CN=x"), keys.getPublic())
                .build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
                        .build(keys.getPrivate()));
        X509Certificate certificate = ca.issueClientCertificate("node,1", csr).certificate();

        assertEquals("node,1", PeerIdentityInterceptor.commonNameOf(certificate));
    }

    @Test
    void peerIdentityCurrentDefaultsToAnonymousOutsideAnyContext() {
        assertTrue(PeerIdentity.current().anonymous());
        assertTrue(PeerIdentity.current().nodeIdIfPresent().isEmpty());
    }

    @Test
    void expiryCheckHandlesAMissingNotAfter() {
        assertFalse(PeerIdentity.ANONYMOUS.isExpiredAt(NOW));
    }
}
