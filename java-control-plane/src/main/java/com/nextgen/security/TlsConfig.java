package com.nextgen.security;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Builds the {@link SslContext}s for both ends of the mTLS connection.
 *
 * <h2>The bootstrap problem, and how it is resolved</h2>
 *
 * A node needs to reach the control plane in order to obtain a client certificate, but mutual TLS
 * requires it to have one already. Three separate problems hide inside that:
 *
 * <ol>
 *   <li><b>The agent must verify the server.</b> Not actually circular — the CA certificate is public
 *       data, distributed out of band ({@code NEXTGEN_CA_CERT} or a mounted file).</li>
 *   <li><b>The server must verify the agent.</b> Solved with {@link ClientAuth#OPTIONAL} at the
 *       transport plus {@link MtlsPolicyInterceptor}, which allows exactly the enrolment method
 *       without a client certificate and requires one everywhere else.</li>
 *   <li><b>Enrolment itself must be authenticated by something.</b> A single-use, TTL-bounded,
 *       256-bit enrolment token in a gRPC metadata header.</li>
 * </ol>
 *
 * <p>A second port for enrolment was considered and rejected: the interceptor is required regardless
 * (otherwise node A can send a heartbeat claiming to be node B over its own perfectly valid
 * connection), so a second socket buys nothing but another lifecycle to manage. Setting
 * {@code ENROLLMENT_ENABLED=false} after provisioning gives the same "close the door" property.
 *
 * <p><b>Note on TOFU:</b> trust-on-first-use was rejected. It moves the trust decision to precisely
 * the moment an active man-in-the-middle can steal the enrolment token, and buys nothing over
 * shipping a 1 KB public file.
 */
public final class TlsConfig {
    private static final Logger LOG = LoggerFactory.getLogger(TlsConfig.class);

    private TlsConfig() {
    }

    /**
     * Server context: presents the server certificate, and requests — but does not require — a client
     * certificate.
     *
     * <p>{@code OPTIONAL} rather than {@code REQUIRE} is what lets an unenrolled node reach the
     * enrolment RPC at all. Enforcement is per-method, in the interceptor, not at the transport.
     */
    public static SslContext serverContext(CertificateAuthority ca,
                                           CertificateAuthority.ServerMaterial material)
            throws SSLException {
        SslContextBuilder builder = SslContextBuilder
                .forServer(material.privateKey(), material.certificate())
                .trustManager(pemStream(ca.caCertificatePem()))
                .clientAuth(ClientAuth.OPTIONAL)
                .sslProvider(provider());
        // The two-argument overload is required: GrpcSslContexts.configure(builder) picks the
        // provider itself and silently discards any earlier .sslProvider(...) call.
        return GrpcSslContexts.configure(builder, provider()).build();
    }

    /**
     * Client context for the enrolment phase: verifies the server, presents no client certificate
     * (the node does not have one yet).
     */
    public static SslContext enrollmentClientContext(String caCertificatePem) throws SSLException {
        SslContextBuilder builder = SslContextBuilder.forClient()
                .trustManager(pemStream(caCertificatePem))
                .sslProvider(provider());
        // The two-argument overload is required: GrpcSslContexts.configure(builder) picks the
        // provider itself and silently discards any earlier .sslProvider(...) call.
        return GrpcSslContexts.configure(builder, provider()).build();
    }

    /**
     * Client context for normal operation: full mutual TLS.
     *
     * <p><b>The constraint most likely to be missed:</b> TLS client authentication happens once per
     * connection, during the handshake. gRPC/Netty exposes no TLS 1.3 post-handshake client auth, so
     * an enrolment channel <i>cannot be upgraded in place</i>. After {@code Enroll} returns, the agent
     * must shut down the enrolment channel and build a new one with this context. Skipping that step
     * fails as a confusing {@code UNAUTHENTICATED} on the first heartbeat rather than as an obvious
     * error.
     */
    public static SslContext mutualClientContext(String caCertificatePem,
                                                 X509Certificate clientCertificate,
                                                 PrivateKey clientKey) throws SSLException {
        SslContextBuilder builder = SslContextBuilder.forClient()
                .trustManager(pemStream(caCertificatePem))
                .keyManager(clientKey, clientCertificate)
                .sslProvider(provider());
        // The two-argument overload is required: GrpcSslContexts.configure(builder) picks the
        // provider itself and silently discards any earlier .sslProvider(...) call.
        return GrpcSslContexts.configure(builder, provider()).build();
    }

    /**
     * Chooses the TLS provider. Defaults to {@link SslProvider#JDK}.
     *
     * <p><b>Why not the bundled native.</b> grpc-netty-shaded ships a BoringSSL native, and it loads
     * successfully here — but with this server configuration ({@code ClientAuth.OPTIONAL} plus an EC
     * P-256 server key and a CA trust manager) it aborts every handshake with
     * {@code TLSV1_ALERT_INTERNAL_ERROR} sent from the server side. The identical configuration
     * completes normally on the JDK stack, which has supported ALPN — the only thing gRPC strictly
     * needs from the provider — since Java 9.
     *
     * <p>Rather than leave a default that fails on the machine it was developed on, the JDK provider
     * is the default and OpenSSL is opt-in via {@code TLS_SSL_PROVIDER=OPENSSL} for anyone who has
     * verified it on their platform and wants the throughput.
     */
    static SslProvider provider() {
        String configured = com.nextgen.controlplane.EnvConfig.stringValue("TLS_SSL_PROVIDER", "JDK");
        if ("OPENSSL".equalsIgnoreCase(configured)) {
            if (!io.grpc.netty.shaded.io.netty.handler.ssl.OpenSsl.isAvailable()) {
                LOG.warn("TLS_SSL_PROVIDER=OPENSSL requested but the native library is unavailable; "
                        + "falling back to the JDK provider");
                return SslProvider.JDK;
            }
            return SslProvider.OPENSSL;
        }
        return SslProvider.JDK;
    }

    private static ByteArrayInputStream pemStream(String pem) {
        return new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    }
}
