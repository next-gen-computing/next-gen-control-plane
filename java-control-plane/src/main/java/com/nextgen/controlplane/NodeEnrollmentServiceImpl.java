package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto.*;
import com.nextgen.proto.NodeEnrollmentGrpc;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.EnrollmentTokenStore;
import com.nextgen.security.PeerIdentity;
import com.nextgen.security.PkiException;
import com.nextgen.security.SecurityPolicy;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Counter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Token-bootstrapped certificate enrolment.
 *
 * <p>A node presents a single-use enrolment token once and receives a CA-signed client certificate in
 * exchange. Tokens bootstrap trust; certificates carry it thereafter.
 *
 * <p>The token arrives in gRPC metadata rather than in the request message. A bearer secret in a
 * proto field eventually lands in a log file, because {@code toString()}/{@code TextFormat} dumps of
 * request messages appear in gRPC debug logging and in exception messages.
 */
public class NodeEnrollmentServiceImpl extends NodeEnrollmentGrpc.NodeEnrollmentImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(NodeEnrollmentServiceImpl.class);

    /** Header carrying the single-use enrolment token. */
    public static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-nextgen-enrollment-token", Metadata.ASCII_STRING_MARSHALLER);

    /** Context key the interceptor uses to hand the token to this service. */
    public static final Context.Key<String> TOKEN_CONTEXT_KEY = Context.key("nextgen-enrollment-token");

    private static final Counter ENROLLMENTS = Counter.build()
            .name("controlplane_enrollments_total")
            .help("Enrolment attempts by outcome")
            .labelNames("result")
            .register();

    private final CertificateAuthority ca;
    private final EnrollmentTokenStore tokens;
    private final SecurityPolicy policy;
    private final NodeRegistry registry;

    public NodeEnrollmentServiceImpl(CertificateAuthority ca, EnrollmentTokenStore tokens,
                                     SecurityPolicy policy, NodeRegistry registry) {
        this.ca = ca;
        this.tokens = tokens;
        this.policy = policy;
        this.registry = registry;
    }

    @Override
    public void enroll(EnrollRequest request, StreamObserver<EnrollResponse> responseObserver) {
        if (!policy.enrollmentEnabled()) {
            respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_DISABLED,
                    "Enrolment is closed on this control plane");
            return;
        }

        EnrollmentTokenStore.Consumption consumption = tokens.consume(TOKEN_CONTEXT_KEY.get());
        switch (consumption.status()) {
            case INVALID -> {
                respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_INVALID_TOKEN,
                        "Enrolment token is not recognised");
                return;
            }
            case EXPIRED -> {
                respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_TOKEN_EXPIRED,
                        "Enrolment token has expired");
                return;
            }
            default -> { /* accepted */ }
        }

        // The node id comes from the CONSUMED TOKEN, never from the request and never from the CSR
        // subject. This is the impersonation boundary: a node that asks to be called "admin" gets a
        // certificate for whatever identity its token was issued against.
        String boundNodeId = consumption.nodeId();
        if (!request.getNodeId().isEmpty() && !request.getNodeId().equals(boundNodeId)) {
            LOG.warn("Enrolment requested id '{}' but its token is bound to '{}'; using the bound id",
                    request.getNodeId(), boundNodeId);
        }

        PKCS10CertificationRequest csr;
        try {
            csr = parseCsr(request.getCsrPem().toStringUtf8());
        } catch (Exception e) {
            respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_CSR_INVALID,
                    "Certificate signing request could not be parsed");
            return;
        }

        try {
            CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(boundNodeId, csr);

            if (registry != null) {
                registry.get(boundNodeId).ifPresent(record ->
                        registry.attachCertificate(boundNodeId, CertificateInfo.newBuilder()
                                .setSerial(issued.serial().toString(16))
                                .setSubjectCn(boundNodeId)
                                .setNotAfterEpochMillis(issued.notAfter().toEpochMilli())
                                .build()));
            }

            ENROLLMENTS.labels("issued").inc();
            LOG.info("Issued client certificate serial {} to node '{}'",
                    issued.serial().toString(16), boundNodeId);

            responseObserver.onNext(EnrollResponse.newBuilder()
                    .setResult(EnrollmentResult.ENROLLMENT_RESULT_ISSUED)
                    .setClientCertificatePem(ByteString.copyFrom(issued.pem(), StandardCharsets.UTF_8))
                    .setCaCertificatePem(ByteString.copyFrom(ca.caCertificatePem(), StandardCharsets.UTF_8))
                    .setCertificateSerial(issued.serial().toString(16))
                    .setNotBeforeEpochMillis(issued.notBefore().toEpochMilli())
                    .setNotAfterEpochMillis(issued.notAfter().toEpochMilli())
                    .setRenewAfterEpochMillis(issued.renewAfter().toEpochMilli())
                    .setMessage("Certificate issued")
                    .build());
            responseObserver.onCompleted();
        } catch (PkiException e) {
            LOG.warn("Enrolment for '{}' failed: {}", boundNodeId, e.getMessage());
            respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_CSR_INVALID, e.getMessage());
        }
    }

    /**
     * Reissues a certificate for an already-enrolled node.
     *
     * <p>Authenticated by the node's current client certificate over mTLS — no token is involved, so
     * routine rotation never requires an operator to mint anything.
     */
    @Override
    public void renewCertificate(RenewRequest request, StreamObserver<EnrollResponse> responseObserver) {
        PeerIdentity identity = PeerIdentity.current();
        if (identity.anonymous()) {
            respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_INVALID_TOKEN,
                    "Renewal requires an existing client certificate");
            return;
        }

        // Renew for the caller's own identity, whatever the request body claims.
        String nodeId = identity.nodeId();
        try {
            PKCS10CertificationRequest csr = parseCsr(request.getCsrPem().toStringUtf8());
            CertificateAuthority.IssuedCertificate issued = ca.issueClientCertificate(nodeId, csr);
            ENROLLMENTS.labels("renewed").inc();
            LOG.info("Renewed certificate for node '{}' (serial {})", nodeId, issued.serial().toString(16));

            responseObserver.onNext(EnrollResponse.newBuilder()
                    .setResult(EnrollmentResult.ENROLLMENT_RESULT_ISSUED)
                    .setClientCertificatePem(ByteString.copyFrom(issued.pem(), StandardCharsets.UTF_8))
                    .setCaCertificatePem(ByteString.copyFrom(ca.caCertificatePem(), StandardCharsets.UTF_8))
                    .setCertificateSerial(issued.serial().toString(16))
                    .setNotBeforeEpochMillis(issued.notBefore().toEpochMilli())
                    .setNotAfterEpochMillis(issued.notAfter().toEpochMilli())
                    .setRenewAfterEpochMillis(issued.renewAfter().toEpochMilli())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            respond(responseObserver, EnrollmentResult.ENROLLMENT_RESULT_CSR_INVALID,
                    "Certificate signing request could not be parsed");
        }
    }

    public static PKCS10CertificationRequest parseCsr(String pem) throws Exception {
        try (PemReader reader = new PemReader(new StringReader(pem))) {
            PemObject object = reader.readPemObject();
            if (object == null) {
                throw new IllegalArgumentException("no PEM object found");
            }
            return new PKCS10CertificationRequest(object.getContent());
        }
    }

    private void respond(StreamObserver<EnrollResponse> observer, EnrollmentResult result,
                         String message) {
        ENROLLMENTS.labels(result.name().toLowerCase(java.util.Locale.ROOT)).inc();
        // Never echo the presented token back — it would land in the caller's logs.
        observer.onNext(EnrollResponse.newBuilder()
                .setResult(result)
                .setMessage(message == null ? "" : message)
                .build());
        observer.onCompleted();
    }
}
