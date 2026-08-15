package com.nextgen.controlplane;

import com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.agent.DockerCapabilityDetector;
import com.nextgen.controlplane.raft.ApplyClock;
import com.nextgen.controlplane.raft.GrpcRaftTransport;
import com.nextgen.controlplane.raft.RaftConsensusServiceImpl;
import com.nextgen.controlplane.raft.RaftControlPlaneWriter;
import com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptor;
import com.nextgen.controlplane.raft.RaftLog;
import com.nextgen.controlplane.raft.RaftNode;
import com.nextgen.controlplane.raft.RaftPeer;
import com.nextgen.controlplane.raft.RaftStateMachine;
import com.nextgen.controlplane.raft.RaftTimings;
import com.nextgen.controlplane.risk.MLRiskScorer;
import com.nextgen.controlplane.risk.RiskScorer;
import com.nextgen.controlplane.risk.RuleBasedRiskScorer;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.ProactiveMigrator;
import com.nextgen.controlplane.task.QueuedTaskReconciler;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.training.JobOutcomeLogger;
import com.nextgen.controlplane.training.RiskOutcomeLogger;
import com.nextgen.controlplane.training.RiskSnapshotLogger;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.EnrollmentTokenInterceptor;
import com.nextgen.security.EnrollmentTokenStore;
import com.nextgen.security.MtlsPolicyInterceptor;
import com.nextgen.security.PeerIdentityInterceptor;
import com.nextgen.security.PkiPaths;
import com.nextgen.security.RateLimitInterceptor;
import com.nextgen.security.SecurityPolicy;
import com.nextgen.security.TlsConfig;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Starts the ControlPlane gRPC server, the Prometheus metrics exporter, and the Dashboard HTTP
 * server.
 *
 * <p>Every port and bind address is configurable so the control plane can run behind a public
 * address without code changes. See {@code docs/ARCHITECTURE.md} for the connectivity model.
 */
public class ControlPlaneServer {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneServer.class);

    private static final int DEFAULT_GRPC_PORT = 50051;
    private static final int DEFAULT_METRICS_PORT = 9090;
    private static final int DEFAULT_DASHBOARD_PORT = 8085;
    /** A separate port from {@code GRPC_PORT} — the {@code RaftConsensus} service binds here, outside
     * {@link MtlsPolicyInterceptor} entirely, since a peer replica presents no node certificate. */
    private static final int DEFAULT_RAFT_PORT = 50052;

    /** Guards against double-registering the registry collector if start() runs twice in one JVM. */
    private static volatile boolean collectorRegistered = false;

    public static void start() throws IOException, InterruptedException {
        int grpcPort = EnvConfig.intValue("GRPC_PORT", DEFAULT_GRPC_PORT);
        int metricsPort = EnvConfig.intValue("METRICS_PORT", DEFAULT_METRICS_PORT);
        int dashboardPort = EnvConfig.intValue("DASHBOARD_PORT", DEFAULT_DASHBOARD_PORT);
        long heartbeatTimeoutMs =
                EnvConfig.longValue("HEARTBEAT_TIMEOUT_MS", HeartbeatMonitor.DEFAULT_TIMEOUT_MS);
        long heartbeatCheckMs =
                EnvConfig.longValue("HEARTBEAT_CHECK_INTERVAL_MS", HeartbeatMonitor.DEFAULT_CHECK_INTERVAL_MS);
        long riskCheckMs =
                EnvConfig.longValue("RISK_CHECK_INTERVAL_MS", RiskMonitor.DEFAULT_CHECK_INTERVAL_MS);

        // ── Raft (default OFF — every existing single-node deployment is untouched until an operator
        // opts in, following the ML_RISK_SCORER_ENABLED precedent exactly) ───────────────────────────
        boolean raftEnabled = EnvConfig.booleanValue("RAFT_ENABLED", false);
        String raftNodeId = EnvConfig.stringValue("RAFT_NODE_ID", "");
        List<RaftPeer> raftMembers = RaftPeer.parse(EnvConfig.stringValue("RAFT_PEERS", ""));
        int raftPort = EnvConfig.intValue("RAFT_PORT", DEFAULT_RAFT_PORT);
        String raftAdvertisedAddress =
                EnvConfig.stringValue("RAFT_ADVERTISED_ADDRESS", "localhost:" + grpcPort);
        Path raftDir = Paths.get(EnvConfig.stringValue("RAFT_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "raft").toString()));
        RaftTimings raftTimings = new RaftTimings(
                EnvConfig.longValue("RAFT_HEARTBEAT_INTERVAL_MS", RaftTimings.DEFAULT_HEARTBEAT_INTERVAL_MS),
                EnvConfig.longValue("RAFT_ELECTION_TIMEOUT_MIN_MS", RaftTimings.DEFAULT_ELECTION_TIMEOUT_MIN_MS),
                EnvConfig.longValue("RAFT_ELECTION_TIMEOUT_MAX_MS", RaftTimings.DEFAULT_ELECTION_TIMEOUT_MAX_MS),
                EnvConfig.longValue("RAFT_PROPOSE_TIMEOUT_MS", RaftTimings.DEFAULT_PROPOSE_TIMEOUT_MS));
        if (raftEnabled && raftNodeId.isBlank()) {
            throw new IllegalStateException("RAFT_ENABLED=true requires RAFT_NODE_ID to be set");
        }
        if (raftEnabled && raftMembers.isEmpty()) {
            throw new IllegalStateException(
                    "RAFT_ENABLED=true requires RAFT_PEERS (\"id=host:port,...\", including self)");
        }

        // The registries stamp every timestamp from this clock. Under Raft it must return the LEADER's
        // proposal time while a command is being applied (see ApplyClock's Javadoc) so all replicas
        // stamp byte-identical values; otherwise it's just the real wall clock, exactly as before.
        ApplyClock applyClock = raftEnabled ? new ApplyClock() : null;
        LongSupplier registryClock = raftEnabled ? applyClock : System::currentTimeMillis;

        // Shared node registry. The backing map is kept accessible so collaborators constructed from
        // a raw map continue to observe the same state.
        NodeRegistry registry = new NodeRegistry(new ConcurrentHashMap<>(), registryClock);

        // Node counts are reported at scrape time from the registry itself, so they cannot drift out
        // of date the way an imperatively-updated gauge did.
        if (!collectorRegistered) {
            new NodeRegistryCollector(registry).register();
            collectorRegistered = true;
        }

        TaskRegistry taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), registryClock);
        JobRegistry jobRegistry = new JobRegistry(new ConcurrentHashMap<>(), registryClock);

        RaftComponents raft = raftEnabled
                ? startRaft(raftNodeId, raftMembers, raftAdvertisedAddress, raftDir, raftTimings, raftPort,
                        registry, taskRegistry, jobRegistry, applyClock)
                : RaftComponents.disabled();
        ControlPlaneWriter writer = raftEnabled
                ? new RaftControlPlaneWriter(raft.node(), raftTimings.proposeTimeoutMs())
                : null;

        // Real training data lives under one shared directory — (signal snapshot → eventual outcome)
        // for risk, and (node properties + allocated share → duration/outcome) for job splitting.
        Path dataDir = Paths.get(EnvConfig.stringValue("NEXTGEN_DATA_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "data").toString()));

        HeuristicNodeCapacityScorer capacityScorer = new HeuristicNodeCapacityScorer(
                EnvConfig.doubleValue("CAPACITY_CPU_CORE_WEIGHT", HeuristicNodeCapacityScorer.DEFAULT_CPU_CORE_WEIGHT),
                EnvConfig.doubleValue("CAPACITY_MEMORY_GB_WEIGHT", HeuristicNodeCapacityScorer.DEFAULT_MEMORY_GB_WEIGHT),
                EnvConfig.doubleValue("CAPACITY_HEADROOM_FLOOR", HeuristicNodeCapacityScorer.DEFAULT_HEADROOM_FLOOR),
                EnvConfig.doubleValue("CAPACITY_STALE_HEADROOM_FACTOR",
                        HeuristicNodeCapacityScorer.DEFAULT_STALE_HEADROOM_FACTOR),
                EnvConfig.doubleValue("CAPACITY_RISK_PENALTY_WEIGHT",
                        HeuristicNodeCapacityScorer.DEFAULT_RISK_PENALTY_WEIGHT),
                EnvConfig.doubleValue("CAPACITY_MIN_RISK_FACTOR", HeuristicNodeCapacityScorer.DEFAULT_MIN_RISK_FACTOR),
                EnvConfig.doubleValue("CAPACITY_MIN_WEIGHT_FLOOR", HeuristicNodeCapacityScorer.DEFAULT_MIN_WEIGHT_FLOOR));
        JobOutcomeLogger jobOutcomeLogger = JobOutcomeLogger.toFile(dataDir.resolve("job_outcomes.jsonl"));

        // Constructed here (not inside buildGrpcServer, and before the heartbeat monitor below) so
        // this same instance's TaskRegistry, NodeTaskChannelRegistry, NodeHistory and JobCoordinator
        // can also be wired into HeartbeatMonitor's outcome logging and into RiskMonitor/
        // ProactiveMigrator further down — they must all observe the one real, live state, not a
        // second, independently-empty copy. Always uses the full constructor now: writer/raftNode are
        // simply null in the non-Raft case, which is exactly what the old 6-arg convenience constructor
        // did internally — zero behavior change.
        ControlPlaneServiceImpl serviceImpl = new ControlPlaneServiceImpl(registry, new RoundRobinScheduler(),
                taskRegistry, new NodeTaskChannelRegistry(), capacityScorer, jobOutcomeLogger,
                jobRegistry, writer, raft.node());

        // Tracks when THIS replica most recently became leader — read by HeartbeatMonitor's grace
        // period below, written by the leadership listener registered further down (once the tokens/
        // reconciler it also drives exist). Long.MAX_VALUE ("never") until a real election happens.
        AtomicLong becameLeaderAtMillis = new AtomicLong(Long.MAX_VALUE);
        // A follower's registry receives zero heartbeats (heartbeats are deliberately leader-local,
        // never replicated) — sweeping there would mark every node dead for no reason. A freshly-
        // promoted leader also needs a grace period: its inherited NodeRecords have frozen
        // lastHeartbeatMillis a follower never advanced, so its very first sweep would instantly mark
        // everything dead without this. Neither concern exists when Raft is off.
        BooleanSupplier heartbeatShouldSweep = !raftEnabled ? () -> true
                : () -> raft.node().isLeader()
                        && (System.currentTimeMillis() - becameLeaderAtMillis.get()) > heartbeatTimeoutMs;
        BooleanSupplier riskShouldSweep = !raftEnabled ? () -> true : () -> raft.node().isLeader();

        RiskOutcomeLogger riskOutcomeLogger = new RiskOutcomeLogger(dataDir.resolve("risk_outcomes.jsonl"));

        // Start build-context TTL sweep daemon (Stage N) — evicts uploaded tarballs older than
        // BUILD_CONTEXT_TTL_MINUTES so they don't accumulate forever on disk.
        Thread buildContextSweepThread = new Thread(serviceImpl.buildContextStore(), "build-context-sweep");
        buildContextSweepThread.setDaemon(true);
        buildContextSweepThread.start();

        // Start heartbeat monitor daemon
        Thread monitorThread = new Thread(
                new HeartbeatMonitor(registry, heartbeatTimeoutMs, heartbeatCheckMs,
                        serviceImpl.nodeHistory(), riskOutcomeLogger, heartbeatShouldSweep),
                "heartbeat-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        RuleBasedRiskScorer ruleBasedScorer = new RuleBasedRiskScorer(
                EnvConfig.doubleValue("RISK_LOW_BATTERY_THRESHOLD_PERCENT",
                        RuleBasedRiskScorer.DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT),
                EnvConfig.doubleValue("RISK_MEMORY_CEILING_PERCENT",
                        RuleBasedRiskScorer.DEFAULT_MEMORY_CEILING_PERCENT),
                EnvConfig.intValue("RISK_TREND_WINDOW", RuleBasedRiskScorer.DEFAULT_TREND_WINDOW),
                EnvConfig.doubleValue("RISK_AT_RISK_THRESHOLD", RuleBasedRiskScorer.DEFAULT_AT_RISK_THRESHOLD));

        // Default OFF: the model starts genuinely untrained (every predictor response reports
        // model_trained=false until train_risk_model.py has run against real accumulated data), so
        // there is zero behavior change for any existing deployment until an operator opts in.
        RiskScorer riskScorer = EnvConfig.booleanValue("ML_RISK_SCORER_ENABLED", false)
                ? new MLRiskScorer(ruleBasedScorer,
                        System.getenv().getOrDefault("PREDICTOR_HOST", "predictor"),
                        EnvConfig.intValue("PREDICTOR_PORT", 50052),
                        EnvConfig.longValue("ML_RISK_PREDICTOR_DEADLINE_MS", MLRiskScorer.DEFAULT_PREDICTOR_DEADLINE_MS),
                        EnvConfig.doubleValue("ML_RISK_AT_RISK_THRESHOLD", MLRiskScorer.DEFAULT_AT_RISK_THRESHOLD))
                : ruleBasedScorer;

        // Opt-in, default OFF: continuous per-sweep logging has a real, ongoing disk-growth cost that
        // RiskOutcomeLogger's rare failure events don't — see RiskSnapshotLogger's Javadoc.
        RiskSnapshotLogger snapshotLogger = EnvConfig.booleanValue("RISK_SNAPSHOT_LOGGING_ENABLED", false)
                ? new RiskSnapshotLogger(dataDir.resolve("risk_snapshots.jsonl"))
                : null;

        ProactiveMigrator migrator = new ProactiveMigrator(
                serviceImpl.taskRegistry(), serviceImpl.taskDispatcher(), serviceImpl.channelRegistry(),
                registry, new RoundRobinScheduler(), serviceImpl.jobCoordinator());

        // Start risk monitor daemon — the predictive counterpart to the heartbeat monitor above.
        Thread riskMonitorThread = new Thread(
                new RiskMonitor(registry, serviceImpl.nodeHistory(), riskScorer, migrator, riskCheckMs,
                        snapshotLogger, riskShouldSweep),
                "risk-monitor");
        riskMonitorThread.setDaemon(true);
        riskMonitorThread.start();

        // Prometheus JVM metrics
        DefaultExports.initialize();
        HTTPServer metricsServer = new HTTPServer.Builder().withPort(metricsPort).build();
        LOG.info("📊 Prometheus metrics server started on port {}", metricsPort);

        // ── Dashboard HTTP server (JSON API only — no bundled frontend) ─────
        // The old web dashboard this used to also serve static files for is gone entirely (see
        // README.md's "no bundled static frontend ships anymore" note); the real UI today is the
        // desktop app's own embedded WebView, reached over gRPC, not this HTTP server. This endpoint
        // stays for anyone scripting against real node data or wiring up their own frontend.
        HttpServer dashboardServer = HttpServer.create(new InetSocketAddress(dashboardPort), 0);

        // API endpoint for live node data — 503s with a leader hint on a follower, see
        // DashboardApiHandler's Javadoc.
        dashboardServer.createContext("/api/nodes", new DashboardApiHandler(registry, raft.node()));

        dashboardServer.setExecutor(null); // default executor
        dashboardServer.start();
        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  📊 Node data API: http://localhost:{}/api/nodes  ", dashboardPort);
        LOG.info("══════════════════════════════════════════════════");

        // ── Security: PKI, enrolment, certificate policy ───
        SecurityPolicy securityPolicy = SecurityPolicy.fromEnvironment();
        PkiPaths pkiPaths = PkiPaths.fromEnvironment();
        CertificateAuthority ca = new CertificateAuthority(pkiPaths, Clock.systemUTC());
        EnrollmentTokenStore tokenStore =
                new EnrollmentTokenStore(Clock.systemUTC(), securityPolicy.enrollmentTokenTtl());
        registerPkiGauge();

        // Redispatches (or fails) any task orphaned QUEUED by a leader dying between accept and
        // dispatch — see QueuedTaskReconciler's Javadoc. Only ever invoked on a become-leader edge.
        QueuedTaskReconciler queuedTaskReconciler = new QueuedTaskReconciler(
                taskRegistry, registry, serviceImpl.taskDispatcher(), new RoundRobinScheduler(), writer);

        if (raftEnabled) {
            // A follower can't issue tokens (enrolment is leader-gated below, same as certificate
            // issuance) or accept its own dispatches — both re-run every time THIS replica newly wins
            // an election, not just once at process start. EnrollmentTokenStore is leader-local, not
            // Raft-replicated (a real, named scope cut — see NodeEnrollmentServiceImpl's leader gating
            // below): a token minted by a previous leader is not known to a new one, so a new leader
            // re-mints fresh tokens for the same configured node ids rather than silently leaving an
            // unenrolled node stuck.
            raft.node().onLeadershipChange(status -> {
                if (status.isLeader()) {
                    becameLeaderAtMillis.set(System.currentTimeMillis());
                    mintConfiguredTokens(tokenStore);
                    queuedTaskReconciler.reconcile();
                } else {
                    becameLeaderAtMillis.set(Long.MAX_VALUE);
                }
            });
        } else {
            mintConfiguredTokens(tokenStore);
        }

        Server grpcServer =
                buildGrpcServer(grpcPort, serviceImpl, securityPolicy, ca, tokenStore, raft.node()).start();

        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  🚀 ControlPlane gRPC server RUNNING on port {}  ", grpcPort);
        if (raftEnabled) {
            LOG.info("  🗳️ Raft consensus port {} (node '{}', peers: {})", raftPort, raftNodeId, raftMembers);
        }
        LOG.info("  🌐 LAN IPs (use these from other laptops):");
        printLanIps();
        LOG.info("══════════════════════════════════════════════════");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down ControlPlane server...");
            grpcServer.shutdown();
            if (raft.consensusServer() != null) {
                raft.consensusServer().shutdown();
            }
            if (raft.node() != null) {
                raft.node().close();
            }
            if (raft.transport() != null) {
                raft.transport().shutdown();
            }
            dashboardServer.stop(2);
            metricsServer.close();
            LOG.info("ControlPlane server stopped.");
        }));

        // Block main thread until server terminates
        grpcServer.awaitTermination();
    }

    /** Every live Raft collaborator {@code start()} needs a handle to later (shutdown, leadership
     * listener) — bundled so each can be assigned exactly once (a ternary, not a reassigned local),
     * keeping every field usable from the shutdown-hook lambda without extra indirection. */
    private record RaftComponents(RaftNode node, GrpcRaftTransport transport, Server consensusServer) {
        static RaftComponents disabled() {
            return new RaftComponents(null, null, null);
        }
    }

    /**
     * Builds and starts every Raft collaborator for one replica: the durable log, the state machine
     * wired to the three ALREADY-CONSTRUCTED registries (never a private second copy), the gRPC
     * transport, the node itself, and the {@code RaftConsensus} service bound to {@code raftPort} — a
     * separate port from {@code GRPC_PORT}, deliberately outside {@link MtlsPolicyInterceptor} since a
     * peer replica is not a node and presents no node certificate.
     */
    private static RaftComponents startRaft(String raftNodeId, List<RaftPeer> members, String advertisedAddress,
                                            Path raftDir, RaftTimings timings, int raftPort,
                                            NodeRegistry registry, TaskRegistry taskRegistry,
                                            JobRegistry jobRegistry, ApplyClock applyClock) throws IOException {
        RaftLog log = new RaftLog(raftDir.resolve(raftNodeId));
        RaftStateMachine stateMachine = new RaftStateMachine(registry, taskRegistry, jobRegistry, applyClock);
        GrpcRaftTransport transport = new GrpcRaftTransport();
        RaftNode node = new RaftNode(raftNodeId, advertisedAddress, members, log, stateMachine, transport, timings);

        Server consensusServer = NettyServerBuilder.forPort(raftPort)
                .addService(new RaftConsensusServiceImpl(node))
                .build()
                .start();

        node.start();
        return new RaftComponents(node, transport, consensusServer);
    }

    /**
     * Assembles the gRPC server against a fresh, internally-constructed {@link ControlPlaneServiceImpl}.
     *
     * <p>Kept for existing callers (tests) that only have a {@link NodeRegistry} and don't need to
     * reach the service instance afterward. {@link #start} needs the instance itself — to wire
     * {@code RiskMonitor}/{@code ProactiveMigrator} onto its live {@code TaskRegistry}/
     * {@code NodeHistory}/{@code JobCoordinator}, not a second, independently-empty copy — so it uses
     * the overload below instead.
     */
    public static Server buildGrpcServer(int grpcPort, NodeRegistry registry, SecurityPolicy policy,
                                         CertificateAuthority ca, EnrollmentTokenStore tokenStore)
            throws IOException {
        return buildGrpcServer(grpcPort, new ControlPlaneServiceImpl(registry, new RoundRobinScheduler()),
                policy, ca, tokenStore);
    }

    /**
     * Assembles the gRPC server around an already-constructed {@link ControlPlaneServiceImpl}.
     *
     * <p>With {@code TLS_ENABLED=true} this is a Netty server presenting the CA-signed server
     * certificate and requesting (not requiring) a client certificate; {@link MtlsPolicyInterceptor}
     * then enforces per-method policy. With TLS off it is a plaintext server and the interceptors run
     * in permissive audit mode, so an existing deployment keeps working while its readiness for
     * enforcement becomes observable.
     */
    public static Server buildGrpcServer(int grpcPort, ControlPlaneServiceImpl serviceImpl, SecurityPolicy policy,
                                         CertificateAuthority ca, EnrollmentTokenStore tokenStore)
            throws IOException {
        return buildGrpcServer(grpcPort, serviceImpl, policy, ca, tokenStore, null);
    }

    /**
     * As the 5-arg overload above, plus {@code raftNode}: when non-null, both services are additionally
     * gated to the current Raft leader via {@link RaftLeaderRedirectInterceptor} — a non-leader replica
     * answers {@code UNAVAILABLE} with a leader-hint trailer instead of serving the call.
     *
     * <p>Gating {@code NodeEnrollmentServiceImpl} this way (not just {@code ControlPlaneServiceImpl}) is
     * what makes a shared PKI volume across replicas safe: {@link CertificateAuthority#issueClientCertificate}
     * writes to {@code serial.txt}/{@code index.txt} under a JVM-local {@code synchronized} lock, which
     * three independent JVMs sharing those files would otherwise race on. Routing every issuance through
     * one replica at a time removes the race without needing any change to {@code CertificateAuthority}
     * itself. <b>Scope cut, stated plainly:</b> the enrolment TOKEN store itself is NOT Raft-replicated —
     * it stays leader-local, in-memory, exactly as today — so a token minted by a since-superseded leader
     * is not honoured by a new one; {@code ControlPlaneServer.start()} re-mints configured tokens on
     * every become-leader edge specifically to keep that gap from stranding an unenrolled node
     * indefinitely. Full token replication (mint/consume as a linearizable Raft command) is real,
     * named future work, not silently left inconsistent.
     *
     * <p>{@code null} (every call site except {@code ControlPlaneServer.start()} under Raft) preserves
     * the interceptor chain exactly as it was before this parameter existed — the strongest possible
     * signal that {@code RAFT_ENABLED=false} changes nothing.
     */
    public static Server buildGrpcServer(int grpcPort, ControlPlaneServiceImpl serviceImpl, SecurityPolicy policy,
                                         CertificateAuthority ca, EnrollmentTokenStore tokenStore, RaftNode raftNode)
            throws IOException {
        ServerBuilder<?> builder;
        if (policy.tlsEnabled()) {
            CertificateAuthority.ServerMaterial material = ca.ensureServerCertificate(policy.sanHosts());
            builder = NettyServerBuilder.forPort(grpcPort)
                    .sslContext(TlsConfig.serverContext(ca, material));
            LOG.info("🔐 Mutual TLS enabled (SANs: {})", policy.sanHosts());
        } else {
            builder = NettyServerBuilder.forPort(grpcPort);
            LOG.warn("⚠ TLS is DISABLED (TLS_ENABLED=false). Certificate policy runs in audit mode; "
                    + "see controlplane_mtls_would_deny_total for what enforcement would reject.");
        }
        applyKeepAlive(builder);

        MtlsPolicyInterceptor mtlsPolicy =
                new MtlsPolicyInterceptor(policy, ca.denylist(), Clock.systemUTC());
        PeerIdentityInterceptor peerIdentity = new PeerIdentityInterceptor();
        NodeEnrollmentServiceImpl enrollmentImpl =
                new NodeEnrollmentServiceImpl(ca, tokenStore, policy, serviceImpl.registry());

        if (raftNode == null) {
            builder.addService(ServerInterceptors.intercept(serviceImpl, mtlsPolicy, peerIdentity));
            // Rate limiting is attached to enrolment ONLY. Heartbeats run at one every two seconds per
            // node and must never share a policy sized for a once-per-node-lifetime operation.
            builder.addService(ServerInterceptors.intercept(enrollmentImpl,
                    mtlsPolicy, peerIdentity, new EnrollmentTokenInterceptor(),
                    new RateLimitInterceptor(policy.rateLimit(), System::nanoTime)));
        } else {
            // Chained FIRST here so it runs LAST — interceptors listed later in
            // ServerInterceptors.intercept(...) run EARLIER, so mtlsPolicy/peerIdentity authenticate the
            // caller before raftRedirect ever runs (see RaftLeaderRedirectInterceptor's own Javadoc).
            RaftLeaderRedirectInterceptor raftRedirect = new RaftLeaderRedirectInterceptor(raftNode::leadership);
            builder.addService(ServerInterceptors.intercept(serviceImpl, raftRedirect, mtlsPolicy, peerIdentity));
            builder.addService(ServerInterceptors.intercept(enrollmentImpl,
                    raftRedirect, mtlsPolicy, peerIdentity, new EnrollmentTokenInterceptor(),
                    new RateLimitInterceptor(policy.rateLimit(), System::nanoTime)));
        }

        // Stage Q: cloud/single-machine mode. Deliberately NEVER wrapped with raftRedirect — see
        // LocalDockerExecution's own proto Javadoc for why (tied to THIS host's Docker daemon, not
        // "whichever replica is leader"). Not registered at all unless both the operator opted in AND a
        // real Docker daemon is confirmed reachable right now — disabled/unavailable means this RPC is
        // UNIMPLEMENTED, never a silent no-op.
        if (EnvConfig.booleanValue("LOCAL_DOCKER_EXEC_ENABLED", false)) {
            DockerCapabilityDetector.DockerCapability docker = new DockerCapabilityDetector().detect();
            if (docker.available()) {
                builder.addService(ServerInterceptors.intercept(
                        new LocalDockerExecutionServiceImpl(), mtlsPolicy, peerIdentity));
                LOG.info("☁️ Local Docker execution (cloud mode) ENABLED — Docker {} confirmed on this host",
                        docker.dockerVersion());
            } else {
                LOG.warn("⚠ LOCAL_DOCKER_EXEC_ENABLED=true but Docker is not reachable on this host — "
                        + "LocalDockerExecution will NOT be registered (calls fail with UNIMPLEMENTED)");
            }
        }

        return builder.build();
    }

    /**
     * Keepalive tuned for an internet path rather than loopback.
     *
     * <p>Client and server settings must be tuned as a pair: if a client pings more often than
     * {@code permitKeepAliveTime} allows, the server answers GOAWAY with {@code ENHANCE_YOUR_CALM}
     * and kills the connection. The client defaults (30s ping, 10s timeout) sit comfortably inside
     * the 20s floor permitted here.
     */
    static void applyKeepAlive(ServerBuilder<?> builder) {
        if (!(builder instanceof NettyServerBuilder netty)) {
            return;
        }
        netty.keepAliveTime(EnvConfig.longValue("GRPC_KEEPALIVE_TIME_MS", 30_000L), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(EnvConfig.longValue("GRPC_KEEPALIVE_TIMEOUT_MS", 10_000L),
                        TimeUnit.MILLISECONDS)
                .permitKeepAliveTime(EnvConfig.longValue("GRPC_PERMIT_KEEPALIVE_TIME_MS", 20_000L),
                        TimeUnit.MILLISECONDS)
                // Agents are idle between heartbeats; without this the server would reject their
                // keepalive pings for having no active calls.
                .permitKeepAliveWithoutCalls(true)
                .maxConnectionIdle(EnvConfig.longValue("GRPC_MAX_CONNECTION_IDLE_MS", 300_000L),
                        TimeUnit.MILLISECONDS);
    }

    /** Exposes whether key-material permissions could actually be restricted on this filesystem. */
    private static void registerPkiGauge() {
        if (pkiGaugeRegistered) {
            return;
        }
        Gauge.build()
                .name("controlplane_pki_permissions_enforced")
                .help("1 when owner-only permissions were applied to key material, 0 when the "
                        + "filesystem could not express them, -1 before any attempt")
                .create()
                .setChild(new Gauge.Child() {
                    @Override
                    public double get() {
                        return PkiPaths.permissionsEnforced();
                    }
                })
                .register();
        pkiGaugeRegistered = true;
    }

    /**
     * Mints enrolment tokens for the node ids listed in {@code ENROLLMENT_TOKENS}.
     *
     * <p>The plaintext token is printed to the log exactly once, at startup, because an operator has
     * to be able to hand it to the node somehow. It is never persisted in plaintext — only its
     * SHA-256 is retained — and it is single-use.
     */
    private static void mintConfiguredTokens(EnrollmentTokenStore tokenStore) {
        String configured = EnvConfig.stringValue("ENROLLMENT_TOKENS", "");
        if (configured.isEmpty()) {
            return;
        }
        for (String nodeId : configured.split(",")) {
            String trimmed = nodeId.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String token = tokenStore.mint(trimmed);
            LOG.info("🎫 Enrolment token for '{}': {}", trimmed, token);
            LOG.info("   Pass it to the node as NEXTGEN_ENROLLMENT_TOKEN. It is single-use.");
        }
    }

    private static volatile boolean pkiGaugeRegistered = false;

    private static void printLanIps() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String displayName = ni.getDisplayName().toLowerCase();
                // Skip virtual adapters (Docker, WSL, Hyper-V, VirtualBox, VMware)
                if (displayName.contains("docker") || displayName.contains("wsl") ||
                    displayName.contains("hyper-v") || displayName.contains("vethernet") ||
                    displayName.contains("virtualbox") || displayName.contains("vmware") ||
                    displayName.contains("vmnet") || displayName.contains("virbr") ||
                    displayName.contains("vbox") || displayName.contains("virtual")) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getHostAddress().contains(":")) continue; // skip IPv6
                    LOG.info("     → {} on {}", addr.getHostAddress(), ni.getDisplayName());
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate network interfaces", e);
        }
    }
}
