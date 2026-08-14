package com.nextgen.agent;

import com.google.protobuf.ByteString;
import com.nextgen.agent.docker.DockerStateChannelClient;
import com.nextgen.agent.docker.DockerStateCollector;
import com.nextgen.agent.task.DockerComposeServiceExecutor;
import com.nextgen.agent.task.NodeBuildContextStore;
import com.nextgen.agent.task.PrimeRangeCounterExecutor;
import com.nextgen.agent.task.TaskChannelClient;
import com.nextgen.agent.task.TaskExecutor;
import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.EnvConfig;
import com.nextgen.controlplane.NodeEnrollmentServiceImpl;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.proto.ControlPlaneProto.*;
import com.nextgen.proto.NodeEnrollmentGrpc;
import com.nextgen.security.TlsConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NodeAgent — registers with the ControlPlane and sends real OS heartbeats.
 *
 * <p>Uses only real readings from {@link SystemMetricsReader}. When the platform cannot supply a
 * reading, the heartbeat says so explicitly via {@code cpu_available}/{@code memory_available}
 * instead of sending a substituted value.
 *
 * <p>The agent always dials out; it never listens. It recovers from a control-plane outage on its own
 * via {@link BackoffPolicy}, including the case where the control plane restarted and no longer knows
 * this node — the server answers {@code reregistration_required} and the agent re-registers rather
 * than heartbeating into the void forever.
 */
public class NodeAgent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeAgent.class);

    private static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 2_000;
    private static final int DEFAULT_CONTROL_PLANE_PORT = 50051;
    /**
     * Agents default to 9091 rather than 9090. The control plane's exporter owns 9090, and when both
     * roles run on one host the two collide and the second to start dies on a bound port.
     */
    private static final int DEFAULT_METRICS_PORT = 9091;

    // ── Prometheus Metrics ──────────────────────────
    private static final Counter HEARTBEAT_COUNT = Counter.build()
            .name("node_heartbeat_count_total")
            .help("Total heartbeats sent by this node agent")
            .register();

    private static final Counter HEARTBEAT_FAILURES = Counter.build()
            .name("node_heartbeat_failures_total")
            .help("Heartbeat attempts that failed to reach the control plane")
            .register();

    private static final Counter RECONNECTS = Counter.build()
            .name("node_reconnects_total")
            .help("Times this agent re-established its session with the control plane")
            .register();

    private static final Counter REREGISTRATIONS = Counter.build()
            .name("node_reregistrations_total")
            .help("Times the control plane asked this agent to register again")
            .register();

    private static final Counter CPU_UNAVAILABLE = Counter.build()
            .name("node_cpu_reading_unavailable_total")
            .help("Heartbeats where the OS could not supply a CPU reading")
            .register();

    private static final Counter MEMORY_UNAVAILABLE = Counter.build()
            .name("node_memory_reading_unavailable_total")
            .help("Heartbeats where the OS could not supply a memory reading")
            .register();

    private static final Gauge CPU_USAGE = Gauge.build()
            .name("node_cpu_usage")
            .help("Current CPU usage percentage (real OS reading; not updated when unavailable)")
            .register();

    private static final Gauge MEMORY_USAGE = Gauge.build()
            .name("node_memory_usage")
            .help("Current memory usage percentage (real OS reading; not updated when unavailable)")
            .register();

    /**
     * Heartbeat round-trip time, measured by the agent.
     *
     * <p>A histogram rather than a gauge: a gauge of the most recent RTT hides the tail, and the tail
     * is the only interesting part of a latency distribution. "Typical latency is 8ms" is compatible
     * with one request in twenty taking two seconds.
     *
     * <p>Measured with {@link System#nanoTime()}, which is monotonic — wall-clock time would produce
     * a negative or wildly wrong sample whenever NTP stepped the clock mid-request.
     *
     * <p>Note on what is NOT measured: absolute <i>one-way</i> latency between two machines with
     * unsynchronised clocks is not measurable. Subtracting the client's send timestamp from the
     * server's receive timestamp and calling the result "network latency" would be the same class of
     * fabrication as reporting an unavailable CPU reading as 0.0. Only agent-local RTT, and the
     * NTP-style skew estimate below, are real numbers.
     */
    private static final Histogram HEARTBEAT_RTT = Histogram.build()
            .name("node_heartbeat_rtt_seconds")
            .help("Agent-measured round-trip time of a heartbeat RPC")
            .buckets(.001, .005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5)
            .register();

    /**
     * Estimated offset between this node's clock and the control plane's, from the four timestamps
     * an exchange produces (client send, server receive, server send, client receive) — the same
     * estimator NTP uses. Exported so a skewed clock is diagnosable rather than mysterious.
     */
    private static final Gauge CLOCK_SKEW = Gauge.build()
            .name("node_clock_skew_seconds")
            .help("Estimated clock offset between this node and the control plane")
            .register();

    public static void start() throws IOException, InterruptedException {
        String nodeId = EnvConfig.stringValue("NODE_ID", "unknown");
        String controlPlaneHost = EnvConfig.stringValue("CONTROL_PLANE_HOST", "control-plane");
        int controlPlanePort = EnvConfig.intValue("CONTROL_PLANE_PORT", DEFAULT_CONTROL_PLANE_PORT);
        int metricsPort = EnvConfig.intValue("NODE_METRICS_PORT", DEFAULT_METRICS_PORT);
        int heartbeatIntervalMs = EnvConfig.intValue("HEARTBEAT_INTERVAL_MS", DEFAULT_HEARTBEAT_INTERVAL_MS);
        String agentVersion = EnvConfig.stringValue("AGENT_VERSION", "1.0.0");

        BackoffPolicy backoff = new BackoffPolicy(
                EnvConfig.longValue("RECONNECT_INITIAL_DELAY_MS", 1_000L),
                EnvConfig.longValue("RECONNECT_MAX_DELAY_MS", 30_000L),
                EnvConfig.doubleValue("RECONNECT_MULTIPLIER", 2.0),
                EnvConfig.doubleValue("RECONNECT_JITTER", 0.2));

        String hostname = InetAddress.getLocalHost().getHostName();
        String ip = InetAddress.getLocalHost().getHostAddress();

        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  🖥  NodeAgent '{}' starting...", nodeId);
        LOG.info("  Hostname: {}, IP: {}", hostname, ip);
        LOG.info("  ControlPlane: {}:{}", controlPlaneHost, controlPlanePort);
        LOG.info("══════════════════════════════════════════════════");

        // Prometheus
        DefaultExports.initialize();
        new HTTPServer.Builder().withPort(metricsPort).build();
        LOG.info("📊 Prometheus metrics on port {}", metricsPort);

        SystemMetricsReader metrics = new SystemMetricsReader();
        PowerMetricsReader powerMetrics = new PowerMetricsReader();
        NodeCapabilities capabilities = metrics.detectCapabilities();

        // Real detection only — see DockerCapabilityDetector's own Javadoc. A node without a working
        // Docker install stays fully usable for every other task kind; it just never gets selected for
        // TASK_KIND_DOCKER_COMPOSE_SERVICE work.
        DockerCapabilityDetector.DockerCapability docker = new DockerCapabilityDetector().detect();
        capabilities = capabilities.toBuilder()
                .setDockerAvailable(docker.available())
                .setDockerVersion(docker.dockerVersion())
                .setDockerComposeVersion(docker.composeVersion())
                .build();
        if (docker.available()) {
            LOG.info("🐳 Docker detected: {} / {}", docker.dockerVersion(), docker.composeVersion());
        }

        boolean tlsEnabled = EnvConfig.booleanValue("TLS_ENABLED", false);
        AgentCredentials credentials = AgentCredentials.fromEnvironment();
        if (tlsEnabled) {
            ensureEnrolled(credentials, nodeId, controlPlaneHost, controlPlanePort, capabilities, agentVersion);
        }

        // Every existing single-node deployment sets CONTROL_PLANE_HOST/PORT and never
        // CONTROL_PLANE_ENDPOINTS, so falling back to THIS method's own defaults (rather than
        // ControlPlaneEndpoints.fromEnvironment()'s "localhost") preserves today's "control-plane"
        // default exactly — only a Raft-aware deployment that sets CONTROL_PLANE_ENDPOINTS opts in to
        // redirect-following across multiple candidates.
        ControlPlaneEndpoints endpoints = controlPlaneEndpoints(controlPlaneHost, controlPlanePort);
        ControlPlaneConnection connection = new ControlPlaneConnection(endpoints, tlsEnabled, credentials);

        NodeInfo registration = NodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setIp(ip)
                .setPort(controlPlanePort)
                .setHostname(hostname)
                .setCapabilities(capabilities)
                .setAgentVersion(agentVersion)
                .build();

        // ── Register (retries until it succeeds) ────────────────────────────
        registerWithBackoff(connection, registration, backoff);

        // ── Heartbeat loop ──────────────────────────────────────────────────
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-sender");
            t.setDaemon(true);
            return t;
        });

        HeartbeatLoop loop = new HeartbeatLoop(
                scheduler, connection, registration, metrics, powerMetrics, backoff, heartbeatIntervalMs);
        loop.start();

        // ── Task channel — real dispatch/execution, on the SAME ControlPlaneConnection above ────
        // DockerComposeServiceExecutor is registered unconditionally, even when docker.available() is
        // false: the scheduler (not executor registration) is what's responsible for never assigning
        // Docker work to a Docker-less node. If one somehow arrives anyway (a scheduler bug, or a
        // manually-crafted dispatch), the executor still fails it with a real, honest error from the
        // actual `docker run` invocation, rather than the less specific "no executor registered".
        // Stage N: the SAME store TaskChannelClient buffers incoming build-context chunks into must be
        // what DockerComposeServiceExecutor reads from — two independent instances would mean a build's
        // tarball is buffered into one and looked up in the other, always "never received".
        NodeBuildContextStore buildContextStore = new NodeBuildContextStore();
        Map<TaskKindDomain, TaskExecutor> executors = Map.of(
                TaskKindDomain.PRIME_COUNT_RANGE, new PrimeRangeCounterExecutor(),
                TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                new DockerComposeServiceExecutor(buildContextStore, connection));
        TaskChannelClient taskChannelClient = new TaskChannelClient(
                connection, nodeId, executors, backoff, buildContextStore);
        taskChannelClient.start();

        // Stage T: real Docker inventory reporting + container control, over its own channel on the
        // same connection — a genuinely separate concern from task dispatch (Docker inventory exists
        // independently of whether this node has any task running). Never opened on a node that failed
        // Docker detection above — matching the "never fabricate a capability" rule everywhere else.
        DockerStateChannelClient dockerStateChannelClient = docker.available()
                ? new DockerStateChannelClient(connection, nodeId, new DockerStateCollector(), backoff)
                : null;
        if (dockerStateChannelClient != null) {
            dockerStateChannelClient.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down NodeAgent '{}'...", nodeId);
            scheduler.shutdownNow();
            taskChannelClient.shutdown();
            if (dockerStateChannelClient != null) {
                dockerStateChannelClient.shutdown();
            }
            connection.shutdown();
        }));

        // Keep main thread alive
        Thread.currentThread().join();
    }

    /**
     * Builds the operational channel to the control plane.
     *
     * <p>When TLS is enabled this MUST use {@link TlsConfig#mutualClientContext}, presenting this
     * node's own certificate — not the server-auth-only context enrollment uses. An earlier version
     * of this method used the enrollment context here too, which meant that even a node holding a
     * perfectly valid issued certificate never actually presented it on the operational connection;
     * every heartbeat would have been rejected by {@code MtlsPolicyInterceptor} the moment the server
     * enforced certificate policy, since {@code SendHeartbeat} is not on the anonymous allowlist.
     * {@link #ensureEnrolled} is what guarantees {@code credentials} holds a usable certificate by the
     * time this method runs.
     *
     * <p>Keepalive is tuned for an internet path, not loopback. Without it a connection severed by a
     * NAT timeout, a dropped link, or a machine going to sleep stays "open" from this side until the
     * next RPC eventually times out — which on a two-second heartbeat means the node looks healthy to
     * itself while the control plane has already given up on it.
     *
     * <p>These values must stay inside what the server permits. A client that pings more often than
     * the server's {@code permitKeepAliveTime} allows is answered with GOAWAY / ENHANCE_YOUR_CALM and
     * the connection is torn down — so the two sides are tuned as a pair, both defaulting from the
     * same environment variables.
     */
    static ManagedChannel buildChannel(String host, int port, boolean tlsEnabled,
                                       AgentCredentials credentials) {
        ManagedChannelBuilder<?> builder;
        if (tlsEnabled) {
            // ensureEnrolled() must have run first and either found or obtained a certificate; if it
            // didn't, that is a bug in the caller, not a condition to silently paper over here by
            // falling back to plaintext. Falling back would mean an operator who believes they turned
            // TLS_ENABLED=true is actually running unauthenticated — a security regression disguised
            // as a convenience.
            if (!credentials.hasCertificate()) {
                throw new IllegalStateException(
                        "TLS_ENABLED=true but this node has no certificate; ensureEnrolled() must "
                                + "run before buildChannel()");
            }
            String caCertificatePem = credentials.bootstrapCaCertificate().orElseThrow(() ->
                    new IllegalStateException("TLS_ENABLED=true but no CA certificate is available "
                            + "to verify the control plane (set NEXTGEN_CA_CERT)"));
            try {
                builder = NettyChannelBuilder.forAddress(host, port)
                        .sslContext(TlsConfig.mutualClientContext(caCertificatePem,
                                credentials.certificate().orElseThrow(), credentials.privateKey()));
            } catch (SSLException e) {
                throw new IllegalStateException("Could not build the mTLS context for " + host, e);
            }
        } else {
            builder = ManagedChannelBuilder.forAddress(host, port).usePlaintext();
        }

        return builder
                .keepAliveTime(EnvConfig.longValue("GRPC_KEEPALIVE_TIME_MS", 30_000L),
                        TimeUnit.MILLISECONDS)
                .keepAliveTimeout(EnvConfig.longValue("GRPC_KEEPALIVE_TIMEOUT_MS", 10_000L),
                        TimeUnit.MILLISECONDS)
                // Agents are idle between heartbeats, so keepalive has to be permitted with no
                // active call or it never fires when it is most needed.
                .keepAliveWithoutCalls(true)
                .idleTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    /**
     * Builds the candidate address list registration/heartbeats/the task channel all share, preferring
     * {@code CONTROL_PLANE_ENDPOINTS} ({@code "host1:port1,host2:port2,..."}) when set — a Raft-aware,
     * multi-replica deployment — and otherwise falling back to the single {@code defaultHost}/
     * {@code defaultPort} this method's caller already resolved from {@code CONTROL_PLANE_HOST}/
     * {@code CONTROL_PLANE_PORT}, so a plain single-node deployment's behavior is unchanged.
     */
    private static ControlPlaneEndpoints controlPlaneEndpoints(String defaultHost, int defaultPort) {
        String raw = EnvConfig.stringValue("CONTROL_PLANE_ENDPOINTS", "");
        return raw.isBlank() ? ControlPlaneEndpoints.single(defaultHost, defaultPort)
                : ControlPlaneEndpoints.fromEnvironment();
    }

    /**
     * Ensures {@code credentials} holds a valid, current client certificate before the operational
     * channel is built — performing enrollment against the control plane if it doesn't.
     *
     * <p>This is the piece that makes {@code NEXTGEN_ENROLLMENT_TOKEN} actually do something. Without
     * it, an operator could set the token and {@code TLS_ENABLED=true} and the agent would still have
     * no certificate to present — the token would simply be read and ignored.
     *
     * <p>Fails fast (throws) rather than degrading: a misconfigured or rejected enrollment must stop
     * the agent, not have it fall through to plaintext or hang retrying forever against a token that
     * was already consumed by a previous attempt (enrollment tokens are single-use by design).
     *
     * <p>Public so the desktop app's own node-mode join flow can perform the exact same enrolment
     * sequence rather than a second, divergent copy of it — see {@code DesktopApp}.
     */
    public static void ensureEnrolled(AgentCredentials credentials, String nodeId, String host, int port,
                                      NodeCapabilities capabilities, String agentVersion) {
        credentials.load();

        Duration renewWindow = Duration.ofMinutes(
                EnvConfig.longValue("CERT_RENEW_WINDOW_MINUTES", 60L * 24 * 7)); // 7 days
        if (credentials.hasCertificate() && !credentials.needsRenewal(Instant.now(), renewWindow)) {
            LOG.info("🔐 Using existing node certificate (serial {})",
                    credentials.certificate().orElseThrow().getSerialNumber().toString(16));
            return;
        }

        String caCertificatePem = credentials.bootstrapCaCertificate().orElseThrow(() ->
                new IllegalStateException("TLS_ENABLED=true but no CA certificate is available to "
                        + "verify the control plane during enrolment. Set NEXTGEN_CA_CERT to a copy "
                        + "of the control plane's ca.crt. Trust-on-first-use is deliberately not "
                        + "supported: it would expose the enrolment token to an active "
                        + "man-in-the-middle. See docs/ARCHITECTURE.md."));

        String token = EnvConfig.stringValue("NEXTGEN_ENROLLMENT_TOKEN", "");
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "TLS_ENABLED=true and this node has no certificate yet, but NEXTGEN_ENROLLMENT_TOKEN "
                            + "is not set. Mint a single-use token on the control plane "
                            + "(ENROLLMENT_TOKENS=" + nodeId + ") and pass it here to enrol.");
        }

        LOG.info("🎫 Enrolling with the control plane at {}:{}...", host, port);

        // A SEPARATE, short-lived channel for enrolment only, trusting the CA but presenting no
        // client certificate (this node doesn't have one yet). TLS client authentication happens once
        // per connection at the handshake, so this channel is discarded after enrolment rather than
        // reused for operational traffic — it structurally cannot be upgraded to carry the certificate
        // issued partway through its own handshake.
        ManagedChannel enrollmentChannel;
        try {
            enrollmentChannel = NettyChannelBuilder.forAddress(host, port)
                    .sslContext(TlsConfig.enrollmentClientContext(caCertificatePem))
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Could not build the enrolment TLS context for " + host, e);
        }

        try {
            String csrPem = credentials.createCsr(nodeId);

            Metadata headers = new Metadata();
            headers.put(NodeEnrollmentServiceImpl.TOKEN_HEADER, token);

            EnrollResponse response = NodeEnrollmentGrpc.newBlockingStub(enrollmentChannel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(15, TimeUnit.SECONDS)
                    .enroll(EnrollRequest.newBuilder()
                            .setNodeId(nodeId)
                            .setCsrPem(ByteString.copyFromUtf8(csrPem))
                            .setCapabilities(capabilities)
                            .setAgentVersion(agentVersion)
                            .build());

            if (response.getResult() != EnrollmentResult.ENROLLMENT_RESULT_ISSUED) {
                // Never retry automatically here: the token was single-use and is now very likely
                // already consumed even on a rejection, so looping would just burn the operator's next
                // token too. Surface the exact reason and stop.
                throw new IllegalStateException("Enrolment was rejected: " + response.getResult()
                        + (response.getMessage().isEmpty() ? "" : " — " + response.getMessage()));
            }

            credentials.store(response.getClientCertificatePem().toStringUtf8(),
                    response.getCaCertificatePem().toStringUtf8());
            LOG.info("✅ Enrolled — certificate serial {} valid until {}",
                    response.getCertificateSerial(),
                    Instant.ofEpochMilli(response.getNotAfterEpochMillis()));
        } finally {
            // Never reused for operational traffic — see the comment above.
            enrollmentChannel.shutdown();
        }
    }

    /**
     * Registers with the ControlPlane, retrying indefinitely with capped exponential backoff.
     *
     * <p>Retrying without limit is deliberate. An agent whose control plane is not yet up, or is
     * mid-restart, should join as soon as it can rather than exiting and requiring an operator to
     * restart it — which is what the previous "10 attempts then {@code System.exit(1)}" did.
     *
     * <p>A leader-redirect trailer is followed immediately, without consuming a backoff delay slot — a
     * redirect is a successful discovery ("the cluster is up, ask over there instead"), not a failure,
     * and treating it as one would make every registration against a multi-replica cluster pay an
     * unnecessary multi-second delay on its very first attempt.
     */
    static void registerWithBackoff(ControlPlaneConnection connection, NodeInfo registration,
                                    BackoffPolicy backoff) throws InterruptedException {
        int attempt = 1;
        int consecutiveHints = 0;
        for (;;) {
            try {
                RegisterResponse response = connection.blockingStub().registerNode(registration);
                LOG.info("✅ Registered with ControlPlane: status={}, assigned_id={}, resumed={}",
                        response.getStatus(), response.getAssignedId(), response.getResumedExisting());
                return;
            } catch (Exception e) {
                String hint = ControlPlaneConnection.leaderHint(e);
                if (hint != null && consecutiveHints < ControlPlaneConnection.MAX_LEADER_HOPS) {
                    consecutiveHints++;
                    LOG.info("↪ Registration redirected to leader hint '{}'", hint);
                    connection.onLeaderHint(hint);
                    continue;
                }
                consecutiveHints = 0;
                connection.onFailure();
                long delay = backoff.delayForAttempt(attempt++);
                LOG.warn("⚠ Registration attempt failed ({}); retrying in {}ms", e.getMessage(), delay);
                Thread.sleep(delay);
            }
        }
    }

    /**
     * Self-rescheduling heartbeat loop.
     *
     * <p>Uses {@code schedule()} rather than {@code scheduleAtFixedRate()} so that the delay before
     * the next attempt can grow while the control plane is unreachable. A fixed-rate loop would keep
     * firing every two seconds into a dead connection.
     */
    static final class HeartbeatLoop {
        private final ScheduledExecutorService scheduler;
        private final ControlPlaneConnection connection;
        private final NodeInfo registration;
        private final SystemMetricsReader metrics;
        private final PowerMetricsReader powerMetrics;
        private final BackoffPolicy backoff;
        private final int intervalMs;

        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger sequence = new AtomicInteger(0);
        private final long startedAtMillis = System.currentTimeMillis();

        /**
         * The RTT of the PREVIOUS heartbeat, relayed on the NEXT one (see {@link #sendOneHeartbeat}) —
         * a real, already-measured number reported one tick late, never a fabricated one-way latency.
         * Plain (non-atomic) fields are safe here: {@link #tick()} only ever runs on this loop's own
         * single-threaded scheduler, never concurrently with itself.
         */
        private double previousRttSeconds = 0.0;
        private boolean previousRttAvailable = false;

        HeartbeatLoop(ScheduledExecutorService scheduler,
                      ControlPlaneConnection connection,
                      NodeInfo registration, SystemMetricsReader metrics, PowerMetricsReader powerMetrics,
                      BackoffPolicy backoff, int intervalMs) {
            this.scheduler = scheduler;
            this.connection = connection;
            this.registration = registration;
            this.metrics = metrics;
            this.powerMetrics = powerMetrics;
            this.backoff = backoff;
            this.intervalMs = intervalMs;
        }

        void start() {
            scheduler.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
        }

        /** One heartbeat attempt, which always reschedules itself. Package-private for tests. */
        void tick() {
            long nextDelayMs = intervalMs;
            try {
                nextDelayMs = sendOneHeartbeat();
            } catch (Exception e) {
                int failures = consecutiveFailures.incrementAndGet();
                HEARTBEAT_FAILURES.inc();
                nextDelayMs = backoff.delayForAttempt(failures);
                LOG.error("❌ Heartbeat failed ({}); retrying in {}ms", e.getMessage(), nextDelayMs);
            } finally {
                if (!scheduler.isShutdown()) {
                    scheduler.schedule(this::tick, nextDelayMs, TimeUnit.MILLISECONDS);
                }
            }
        }

        /** @return the delay before the next attempt, in milliseconds. */
        long sendOneHeartbeat() {
            // Fresh readings every beat — nothing here is cached between ticks.
            MetricReading cpu = metrics.readCpuPercent();
            MetricReading memory = metrics.readMemoryPercent();
            PowerMetricsReader.PowerReading power = powerMetrics.readPowerStatus();

            // A gauge is only touched when the reading is real. Leaving the previous value in place
            // and counting the miss separately is honest; writing 0 would publish "idle" as fact.
            if (cpu.available()) {
                CPU_USAGE.set(cpu.value());
            } else {
                CPU_UNAVAILABLE.inc();
            }
            if (memory.available()) {
                MEMORY_USAGE.set(memory.value());
            } else {
                MEMORY_UNAVAILABLE.inc();
            }

            long clientSendMillis = System.currentTimeMillis();
            HeartbeatRequest request = HeartbeatRequest.newBuilder()
                    .setNodeId(registration.getNodeId())
                    .setCpu(cpu.value())
                    .setCpuAvailable(cpu.available())
                    .setMemory(memory.value())
                    .setMemoryAvailable(memory.available())
                    .setSequence(sequence.incrementAndGet())
                    .setClientSendEpochMillis(clientSendMillis)
                    .setAgentUptimeMillis(clientSendMillis - startedAtMillis)
                    .setBatteryPercent(power.batteryPercent().value())
                    .setBatteryAvailable(power.batteryPercent().available())
                    .setCharging(power.charging().value())
                    .setChargingKnown(power.charging().known())
                    .setOnAcPower(power.onAcPower().value())
                    .setOnAcPowerKnown(power.onAcPower().known())
                    .setPreviousRttSeconds(previousRttSeconds)
                    .setPreviousRttAvailable(previousRttAvailable)
                    .build();

            long startNanos = System.nanoTime();
            HeartbeatResponse response = sendWithRedirects(request);
            double rttSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            HEARTBEAT_RTT.observe(rttSeconds);
            // Reported on the NEXT heartbeat, not this one — see the field Javadoc above.
            previousRttSeconds = rttSeconds;
            previousRttAvailable = true;
            HEARTBEAT_COUNT.inc();
            recordClockSkew(clientSendMillis, response, System.currentTimeMillis());

            if (consecutiveFailures.getAndSet(0) > 0) {
                RECONNECTS.inc();
                LOG.info("🔄 Reconnected to the control plane");
            }

            if (response.getReregistrationRequired()) {
                // The control plane has no record of us — it restarted, or we were deregistered.
                // Re-register rather than continuing to heartbeat into a registry that will never
                // accept us.
                LOG.warn("⚠ Control plane does not recognise this node — re-registering");
                REREGISTRATIONS.inc();
                try {
                    registerWithBackoff(connection, registration, backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            LOG.info("💓 Heartbeat #{}: cpu={}, mem={} → {}",
                    (long) HEARTBEAT_COUNT.get(), cpu.describe(), memory.describe(), response.getStatus());

            int suggested = response.getSuggestedIntervalMillis();
            return suggested > 0 ? suggested : intervalMs;
        }

        /**
         * Sends {@code request}, following a leader-redirect trailer immediately rather than letting it
         * fall through to {@link #tick}'s own failure handling — a redirect is a successful discovery,
         * not a failure, so it must not increment {@link #consecutiveFailures} or consume a backoff
         * delay slot. A genuine transport failure (no hint) is rethrown for {@link #tick} to handle
         * exactly as before.
         */
        private HeartbeatResponse sendWithRedirects(HeartbeatRequest request) {
            int consecutiveHints = 0;
            for (;;) {
                try {
                    return connection.blockingStub().sendHeartbeat(request);
                } catch (StatusRuntimeException e) {
                    String hint = ControlPlaneConnection.leaderHint(e);
                    if (hint != null && consecutiveHints < ControlPlaneConnection.MAX_LEADER_HOPS) {
                        consecutiveHints++;
                        LOG.debug("↪ Heartbeat redirected to leader hint '{}'", hint);
                        connection.onLeaderHint(hint);
                        continue;
                    }
                    connection.onFailure();
                    throw e;
                }
            }
        }
    }

    /**
     * Estimates the clock offset between this node and the control plane.
     *
     * <p>{@code θ = ((T2 − T1) + (T3 − T4)) / 2}, where T1/T4 are the agent's send and receive
     * instants and T2/T3 are the server's. Averaging the two directions cancels the network delay,
     * leaving the offset — the standard NTP estimator.
     *
     * <p>Skipped when the server did not populate its timestamps, rather than computing a figure from
     * zeros.
     */
    static void recordClockSkew(long clientSendMillis, HeartbeatResponse response,
                                long clientReceiveMillis) {
        long serverReceive = response.getServerReceiveEpochMillis();
        long serverSend = response.getServerSendEpochMillis();
        if (serverReceive <= 0 || serverSend <= 0) {
            return;
        }
        double offsetSeconds =
                ((serverReceive - clientSendMillis) + (serverSend - clientReceiveMillis)) / 2000.0;
        CLOCK_SKEW.set(offsetSeconds);
    }
}
