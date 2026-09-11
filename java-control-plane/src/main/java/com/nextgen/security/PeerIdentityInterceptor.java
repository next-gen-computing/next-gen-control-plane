package com.nextgen.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * Extracts the peer's TLS identity and attaches it to the gRPC {@link Context}.
 *
 * <p>Runs before {@link MtlsPolicyInterceptor}, which then decides whether that identity is allowed
 * to make the call.
 */
public class PeerIdentityInterceptor implements ServerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(PeerIdentityInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        PeerIdentity identity = extract(call);
        Context context = Context.current().withValue(PeerIdentity.CONTEXT_KEY, identity);
        return Contexts.interceptCall(context, call, headers, next);
    }

    static PeerIdentity extract(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            // No TLS at all: the in-process transport used by tests, or a plaintext deployment.
            return PeerIdentity.ANONYMOUS;
        }
        try {
            Certificate[] peers = session.getPeerCertificates();
            if (peers == null || peers.length == 0 || !(peers[0] instanceof X509Certificate cert)) {
                return PeerIdentity.ANONYMOUS;
            }
            String cn = commonNameOf(cert);
            return cn == null ? PeerIdentity.ANONYMOUS : PeerIdentity.authenticated(cn, cert);
        } catch (SSLPeerUnverifiedException e) {
            // Expected whenever a client connects without a certificate under ClientAuth.OPTIONAL.
            return PeerIdentity.ANONYMOUS;
        } catch (RuntimeException e) {
            LOG.warn("Could not read the peer certificate; treating the caller as anonymous", e);
            return PeerIdentity.ANONYMOUS;
        }
    }

    /**
     * Reads the CN from a certificate's subject.
     *
     * <p>Uses {@link LdapName} rather than splitting the DN string. A DN is not comma-separated in
     * the naive sense: a CN containing an escaped comma ({@code CN=node\,1,O=nextgen}) breaks every
     * regex or {@code split(",")} implementation, and getting it wrong means one node can be
     * mistaken for another.
     */
    static String commonNameOf(X509Certificate certificate) {
        try {
            LdapName name = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : name.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
            return null;
        } catch (InvalidNameException e) {
            LOG.warn("Peer certificate has an unparseable subject DN", e);
            return null;
        }
    }
}
