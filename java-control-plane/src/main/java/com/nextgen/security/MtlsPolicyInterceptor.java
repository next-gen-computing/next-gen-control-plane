package com.nextgen.security;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.prometheus.client.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Set;

/**
 * Enforces per-method certificate policy.
 *
 * <p>The transport uses {@code ClientAuth.OPTIONAL}, so this interceptor is what actually decides
 * who may call what: the enrolment methods are reachable without a client certificate, and
 * everything else requires a valid, unexpired, unrevoked one.
 *
 * <p>An interceptor is required even if the transport demanded certificates on every connection.
 * Without it, node A could send {@code HeartbeatRequest{node_id: "node-B"}} over its own perfectly
 * valid connection and poison node B's state — authentication alone does not give authorisation.
 *
 * <p>When {@link SecurityPolicy#enforcing()} is false, violations are counted in
 * {@code controlplane_mtls_would_deny_total} and allowed through. That makes it possible to see
 * whether a running cluster is ready for enforcement before turning it on.
 */
public class MtlsPolicyInterceptor implements ServerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(MtlsPolicyInterceptor.class);

    /** Fully-qualified methods callable without a client certificate. */
    private static final Set<String> ANONYMOUS_METHODS = Set.of(
            "nextgen.v1.NodeEnrollment/Enroll");

    private static final Counter WOULD_DENY = Counter.build()
            .name("controlplane_mtls_would_deny_total")
            .help("Calls that certificate policy would have rejected while running in permissive mode")
            .labelNames("reason")
            .register();

    private static final Counter DENIED = Counter.build()
            .name("controlplane_mtls_denied_total")
            .help("Calls rejected by certificate policy")
            .labelNames("reason")
            .register();

    private final SecurityPolicy policy;
    private final CertificateDenylist denylist;
    private final Clock clock;

    public MtlsPolicyInterceptor(SecurityPolicy policy, CertificateDenylist denylist, Clock clock) {
        this.policy = policy;
        this.denylist = denylist;
        this.clock = clock;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        PeerIdentity identity = PeerIdentity.CONTEXT_KEY.get();
        if (identity == null) {
            identity = PeerIdentity.ANONYMOUS;
        }

        String denialReason = evaluate(method, identity);
        if (denialReason == null) {
            return next.startCall(call, headers);
        }

        if (!policy.enforcing()) {
            WOULD_DENY.labels(denialReason).inc();
            LOG.debug("Permissive mode: would have denied {} ({})", method, denialReason);
            return next.startCall(call, headers);
        }

        DENIED.labels(denialReason).inc();
        LOG.warn("Rejected {} — {}", method, denialReason);
        call.close(Status.UNAUTHENTICATED.withDescription(denialReason), new Metadata());
        // MUST NOT be null: gRPC dereferences the returned listener and NPEs if it is.
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                new ServerCall.Listener<ReqT>() {
                }) {
        };
    }

    /** @return null when the call is allowed, otherwise a short machine-friendly reason. */
    String evaluate(String method, PeerIdentity identity) {
        if (ANONYMOUS_METHODS.contains(method)) {
            if (!policy.enrollmentEnabled()) {
                return "enrollment_disabled";
            }
            return null;
        }
        if (identity.anonymous()) {
            return "no_client_certificate";
        }
        if (identity.isExpiredAt(clock.instant())) {
            return "certificate_expired";
        }
        if (denylist != null && denylist.isRevoked(identity.serial())) {
            return "certificate_revoked";
        }
        return null;
    }
}
