package com.nextgen;

import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.agent.NodeAgent;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.PkiPaths;
import com.nextgen.security.SecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Unified entry point for the Next-Gen Control Plane application.
 * Behavior is determined by the ROLE environment variable:
 *   - "server"   → starts the ControlPlane gRPC server
 *   - "agent"    → starts a NodeAgent that registers and sends heartbeats
 *   - "pki-init" → one-shot: bootstraps the CA/server certificate, then exits
 */
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("ROLE", "server");
        LOG.info("=== Next-Gen Control Plane | Role: {} ===", role.toUpperCase());

        try {
            switch (role.toLowerCase()) {
                case "server" -> {
                    LOG.info("Starting ControlPlane server...");
                    ControlPlaneServer.start();
                }
                case "agent" -> {
                    LOG.info("Starting NodeAgent...");
                    NodeAgent.start();
                }
                case "pki-init" -> {
                    LOG.info("Bootstrapping PKI (one-shot)...");
                    runPkiInit();
                }
                default -> {
                    LOG.error("Unknown ROLE '{}'. Set ROLE=server, ROLE=agent, or ROLE=pki-init.", role);
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            LOG.error("Fatal error during startup", e);
            System.exit(1);
        }
    }

    /**
     * Bootstraps the CA and the server leaf certificate on the shared {@code pki} volume, then exits —
     * a Raft 3-replica cluster's {@code docker-compose.yml} runs this as a service every replica
     * {@code depends_on} with {@code condition: service_completed_successfully}, which is what actually
     * eliminates the race: three replica JVMs starting {@link ControlPlaneServer#start} simultaneously
     * and each independently racing {@link CertificateAuthority}'s own check-then-create bootstrap logic
     * against the SAME shared files. Running it here, once, before any replica starts, means every
     * replica's own {@code CertificateAuthority} construction is guaranteed to find the CA/server
     * material already on disk and simply load it — the ordinary single-node startup path, unchanged.
     *
     * <p>This does not, by itself, make ONGOING client-certificate issuance safe across replicas — that
     * is what {@code RaftLeaderRedirectInterceptor} gating {@code NodeEnrollmentServiceImpl} handles
     * (see {@link ControlPlaneServer#buildGrpcServer}'s Javadoc). This method only bootstraps the CA and
     * server certificate once, which happens exactly once regardless of Raft.
     */
    private static void runPkiInit() {
        SecurityPolicy policy = SecurityPolicy.fromEnvironment();
        PkiPaths pkiPaths = PkiPaths.fromEnvironment();
        CertificateAuthority ca = new CertificateAuthority(pkiPaths, Clock.systemUTC());
        if (policy.tlsEnabled()) {
            ca.ensureServerCertificate(policy.sanHosts());
            LOG.info("✅ PKI ready (CA + server certificate) — SANs: {}", policy.sanHosts());
        } else {
            LOG.info("✅ CA ready — TLS_ENABLED=false, so no server certificate was issued");
        }
    }
}
