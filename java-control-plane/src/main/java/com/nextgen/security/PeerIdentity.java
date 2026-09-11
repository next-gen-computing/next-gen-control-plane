package com.nextgen.security;

import io.grpc.Context;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;

/**
 * The authenticated identity behind an RPC, derived from the peer's TLS certificate.
 *
 * @param nodeId     the certificate's common name, or null when the caller is anonymous
 * @param serial     certificate serial, for revocation checks
 * @param notAfter   certificate expiry
 * @param anonymous  true when no client certificate was presented (enrolment, or in-process tests)
 */
public record PeerIdentity(String nodeId, BigInteger serial, Instant notAfter, boolean anonymous) {

    /** Context key carrying the identity into service implementations. */
    public static final Context.Key<PeerIdentity> CONTEXT_KEY = Context.key("nextgen-peer-identity");

    public static final PeerIdentity ANONYMOUS = new PeerIdentity(null, null, null, true);

    public static PeerIdentity authenticated(String nodeId, X509Certificate certificate) {
        return new PeerIdentity(nodeId, certificate.getSerialNumber(),
                certificate.getNotAfter().toInstant(), false);
    }

    /** The identity on the current gRPC context, never null. */
    public static PeerIdentity current() {
        PeerIdentity identity = CONTEXT_KEY.get();
        return identity == null ? ANONYMOUS : identity;
    }

    public Optional<String> nodeIdIfPresent() {
        return Optional.ofNullable(nodeId);
    }

    public boolean isExpiredAt(Instant now) {
        return notAfter != null && now.isAfter(notAfter);
    }
}
