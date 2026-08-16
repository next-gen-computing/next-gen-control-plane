package com.nextgen.desktop.ui.server;

import com.nextgen.agent.AgentCredentials;
import com.nextgen.agent.DockerCapabilityDetector;
import com.nextgen.agent.MetricReading;
import com.nextgen.agent.NodeAgent;
import com.nextgen.agent.SystemMetricsReader;
import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.controlplane.EnvConfig;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.ConnectionDiagnostics;
import com.nextgen.desktop.ui.client.ErrorCategory;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.profile.DesktopProfile;
import com.nextgen.desktop.ui.profile.DesktopProfileStore;
import com.nextgen.desktop.ui.server.dto.ConnectResultDto;
import com.nextgen.desktop.ui.server.dto.DockerInfoDto;
import com.nextgen.desktop.ui.server.dto.ErrorDto;
import com.nextgen.desktop.ui.server.dto.JoinRequestDto;
import com.nextgen.desktop.ui.server.dto.OkResultDto;
import com.nextgen.desktop.ui.server.dto.ProbeResultDto;
import com.nextgen.desktop.ui.server.dto.ProfileDto;
import com.nextgen.desktop.ui.server.dto.ServerInfoDto;
import com.nextgen.desktop.ui.server.dto.ServerLaunchRequestDto;
import com.nextgen.desktop.ui.util.ServerIdCodec;
import com.nextgen.proto.ControlPlaneProto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * {@code /api/role/*} — carries the onboarding business logic that used to live directly in
 * {@code DesktopApp}'s JavaFX click handlers: launching the embedded control plane, joining an
 * existing one (including real mTLS enrolment), and the node-role heartbeat sender that starts once
 * joined. Moved here, not duplicated — {@code DesktopApp} now only reacts to the outcome via
 * {@link #onConnected}, which it uses to swap the {@code WebView} for the JavaFX dashboard.
 *
 * <p>Every method here runs on a {@code LocalUiServer} worker thread already off the JavaFX
 * application thread, so — unlike the JavaFX version — none of this needs its own background thread
 * just to avoid blocking a UI event handler. The HTTP client (a {@code fetch()} call) naturally waits
 * for the response.
 */
public class RoleRouteHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RoleRouteHandler.class);

    private final GrpcConnectionManager connectionManager;
    private final DesktopProfileStore profileStore;
    private final BiConsumer<String, String> onConnected;

    private volatile ScheduledExecutorService heartbeatScheduler;
    private volatile String registeredNodeId;

    /** @param onConnected called with (role, serverId) once onboarding succeeds. */
    public RoleRouteHandler(GrpcConnectionManager connectionManager, DesktopProfileStore profileStore,
            BiConsumer<String, String> onConnected) {
        this.connectionManager = connectionManager;
        this.profileStore = profileStore;
        this.onConnected = onConnected;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            // Stage DD: path-match-but-wrong-method previously fell through to the same bare 404 as an
            // unknown path entirely — indistinguishable from "this route doesn't exist" to a caller.
            // requireMethod resolves the path to its one real route FIRST, then checks the method, so a
            // wrong method on a real path gets 405 + Allow instead — mirrors AccountRouteHandler's
            // identical fix.
            if (path.endsWith("/server-info")) {
                requireMethod(exchange, method, "GET", () -> handleServerInfo(exchange));
            } else if (path.endsWith("/docker-info")) {
                requireMethod(exchange, method, "GET", () -> handleDockerInfo(exchange));
            } else if (path.endsWith("/profile/clear")) {
                requireMethod(exchange, method, "POST", () -> handleClearProfile(exchange));
            } else if (path.endsWith("/profile")) {
                requireMethod(exchange, method, "GET", () -> handleGetProfile(exchange));
            } else if (path.endsWith("/auto-connect")) {
                requireMethod(exchange, method, "POST", () -> handleAutoConnect(exchange));
            } else if (path.endsWith("/server/launch")) {
                requireMethod(exchange, method, "POST", () -> handleServerLaunch(exchange));
            } else if (path.endsWith("/probe")) {
                requireMethod(exchange, method, "GET", () -> handleProbe(exchange));
            } else if (path.endsWith("/node/join")) {
                requireMethod(exchange, method, "POST", () -> handleNodeJoin(exchange));
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (RuntimeException e) {
            LOG.error("Unhandled failure in role route {}", path, e);
            JsonSupport.sendJson(exchange, 500, ErrorDto.of(ErrorCategory.UNKNOWN, "Internal error"));
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private static void requireMethod(HttpExchange exchange, String actualMethod, String allowedMethod,
            Action action) throws IOException {
        if (allowedMethod.equals(actualMethod)) {
            action.run();
        } else {
            exchange.getResponseHeaders().set("Allow", allowedMethod);
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        }
    }

    // ── GET /api/role/profile, POST /api/role/profile/clear, POST /api/role/auto-connect ─────

    /** Backs the frontend's startup check: is there a remembered device setup to silently reconnect
     * to, or should this launch show the ordinary role-selection screen? */
    private void handleGetProfile(HttpExchange exchange) throws IOException {
        JsonSupport.sendJson(exchange, 200, profileStore.load().map(ProfileDto::from).orElse(ProfileDto.absent()));
    }

    /** The onboarding "forget this device" escape hatch — after this, the next launch (or an
     * immediate reload) goes back to ordinary role-selection instead of auto-connecting. */
    private void handleClearProfile(HttpExchange exchange) throws IOException {
        profileStore.clear();
        JsonSupport.sendJson(exchange, 200, OkResultDto.OK);
    }

    /**
     * Replays the remembered device setup — the same launch/join logic the interactive screens use,
     * just with saved parameters instead of ones just typed in. A failure here (e.g. the remembered
     * server is offline right now) does not clear the saved profile: the frontend offers a retry, and
     * the operator can still fall back to manual setup without losing the remembered configuration.
     */
    private void handleAutoConnect(HttpExchange exchange) throws IOException {
        Optional<DesktopProfile> saved = profileStore.load();
        if (saved.isEmpty()) {
            JsonSupport.sendJson(exchange, 404, ErrorDto.of(ErrorCategory.NOT_FOUND, "No saved device setup"));
            return;
        }
        DesktopProfile profile = saved.get();
        ConnectOutcome outcome = profile.isServerRole()
                ? (profile.isDockerLaunchMode() ? doDockerServerLaunch() : doNativeServerLaunch())
                : doNodeJoin(profile.serverAddress(), null, profile.enrollmentUsed());
        if (outcome.ok()) {
            saveProfile(profile.role(), profile.launchMode(), profile.serverAddress(),
                    profile.enrollmentUsed(), outcome.label());
        }
        writeOutcome(exchange, outcome);
    }

    private void saveProfile(String role, String launchMode, String serverAddress, boolean enrollmentUsed,
            String label) {
        profileStore.save(new DesktopProfile(role, launchMode, serverAddress, enrollmentUsed, label,
                System.currentTimeMillis()));
    }

    /** Outcome of an onboarding attempt, independent of how the response gets written — shared by the
     * interactive routes and {@link #handleAutoConnect}, which needs the same success/failure shape
     * without an inbound request to read parameters from. */
    private record ConnectOutcome(boolean ok, String role, String label, ErrorCategory errorCategory,
            String errorMessage) {
        static ConnectOutcome success(String role, String label) {
            return new ConnectOutcome(true, role, label, null, null);
        }

        static ConnectOutcome failure(ErrorCategory category, String message) {
            return new ConnectOutcome(false, null, null, category, message);
        }
    }

    private void writeOutcome(HttpExchange exchange, ConnectOutcome outcome) throws IOException {
        if (!outcome.ok()) {
            JsonSupport.sendJson(exchange, 502, ErrorDto.of(outcome.errorCategory(), outcome.errorMessage()));
            return;
        }
        JsonSupport.sendJson(exchange, 200, new ConnectResultDto(true, outcome.role(), outcome.label()));
        onConnected.accept(outcome.role(), outcome.label());
    }

    // ── GET /api/role/server-info ──────────────────────────────────────────

    private void handleServerInfo(HttpExchange exchange) throws IOException {
        String lanIp = ServerIdCodec.detectLanIp();
        int grpcPort = EnvConfig.intValue("GRPC_PORT", ServerIdCodec.DEFAULT_PORT);
        boolean tlsEnabled = EnvConfig.booleanValue("TLS_ENABLED", false);
        String serverId = ServerIdCodec.encode(lanIp);
        JsonSupport.sendJson(exchange, 200, new ServerInfoDto(lanIp, grpcPort, tlsEnabled, serverId));
    }

    // ── GET /api/role/docker-info ────────────────────────────────────────

    /** Backs the server-setup screen's honest Docker-mode toggle — real detection only (CLI presence
     * AND daemon reachability), matching {@link DockerCapabilityDetector}'s own "never fabricate a
     * capability" discipline. The frontend greys out Docker mode entirely when this reports false. */
    private void handleDockerInfo(HttpExchange exchange) throws IOException {
        var docker = new DockerCapabilityDetector().detect();
        JsonSupport.sendJson(exchange, 200,
                new DockerInfoDto(docker.available(), docker.dockerVersion(), docker.composeVersion()));
    }

    // ── POST /api/role/server/launch ───────────────────────────────────────

    /**
     * Launches the control plane in whichever mode the server-setup screen's operator chose (default
     * {@code native} if the request carries no body at all, preserving every pre-existing caller's
     * exact behavior), then waits for it to become reachable.
     */
    private void handleServerLaunch(HttpExchange exchange) throws IOException {
        boolean dockerMode = false;
        try {
            ServerLaunchRequestDto request =
                    JsonSupport.MAPPER.readValue(exchange.getRequestBody(), ServerLaunchRequestDto.class);
            dockerMode = request != null && request.isDockerMode();
        } catch (Exception e) {
            // No body, or a malformed one — default to native mode, the pre-existing behavior.
        }

        ConnectOutcome outcome = dockerMode ? doDockerServerLaunch() : doNativeServerLaunch();
        if (outcome.ok()) {
            saveProfile("server", dockerMode ? "docker" : "native", null, false, outcome.label());
        }
        writeOutcome(exchange, outcome);
    }

    /**
     * Starts the embedded ControlPlane gRPC server and waits for it to become reachable.
     *
     * <p>Ported from {@code DesktopApp.startGrpcServerAndShowDashboard}. That method needed its own
     * background thread specifically so a fixed-length wait wouldn't freeze the JavaFX UI thread —
     * unneeded here since this whole method already runs off it.
     */
    private ConnectOutcome doNativeServerLaunch() {
        String lanIp = ServerIdCodec.detectLanIp();
        String serverId = ServerIdCodec.encode(lanIp);

        CountDownLatch serverStarted = new CountDownLatch(1);
        AtomicReference<Exception> startupFailure = new AtomicReference<>();

        // ControlPlaneServer.start() blocks on awaitTermination, hence the daemon thread — this part
        // is unavoidable regardless of which thread requested the launch.
        Thread serverThread = new Thread(() -> {
            try {
                LOG.info("Starting embedded ControlPlane server...");
                serverStarted.countDown();
                ControlPlaneServer.start();
            } catch (Exception e) {
                startupFailure.set(e);
                serverStarted.countDown();
                LOG.error("Embedded ControlPlane server failed to start", e);
            }
        }, "control-plane-server");
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            serverStarted.await(5, TimeUnit.SECONDS);
            connectionManager.connectTo("localhost", ServerIdCodec.DEFAULT_PORT,
                    connectionManager.getCurrentPredictorPort());
            waitForControlPlane(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Exception failure = startupFailure.get();
        if (failure != null) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Could not start the control plane server: "
                            + (failure.getMessage() == null ? failure.toString() : failure.getMessage()));
        }

        LOG.info("Embedded control plane ready (server mode)");
        return ConnectOutcome.success("server", serverId);
    }

    /** How long a first-time {@code docker compose up --build} gets before this gives up — real image
     * builds (control-plane + predictor) can genuinely take a while on a cold layer cache. */
    private static final long DOCKER_COMPOSE_UP_TIMEOUT_SECONDS = 180;

    /**
     * Runs this project's own {@code docker-compose.yml} (the same file, unmodified, that
     * `DEVELOPMENT.md`'s Quick Start already documents running by hand) via a real {@code docker
     * compose up}, then connects to it exactly the way native mode connects to its embedded server —
     * from the operator's perspective, and from every other route handler downstream, a Docker-launched
     * control plane is indistinguishable from a native one once this method returns.
     */
    private ConnectOutcome doDockerServerLaunch() {
        var docker = new DockerCapabilityDetector().detect();
        if (!docker.available()) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Docker is not available on this machine (the CLI or the daemon isn't reachable) — "
                            + "install/start Docker Desktop, or choose Native mode instead.");
        }

        Path composeFile = locateComposeFile();
        if (composeFile == null) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Could not find docker-compose.yml near the running application — choose Native "
                            + "mode instead, or run 'docker compose up' by hand from the project root.");
        }

        LOG.info("Starting control plane via Docker Compose ({})...", composeFile);
        List<String> command = List.of("docker", "compose", "-f", composeFile.toString(), "up", "-d", "--build");
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(composeFile.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Could not start 'docker compose': " + e.getMessage());
        }

        String output;
        boolean finished;
        try {
            // Read to EOF before waitFor — the process's combined stdout/stderr pipe can fill and
            // deadlock the child if nothing drains it while a build's real output is flowing.
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            finished = process.waitFor(DOCKER_COMPOSE_UP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Interrupted while starting the Docker containers");
        } catch (IOException e) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Could not read 'docker compose' output: " + e.getMessage());
        }
        if (!finished) {
            process.destroyForcibly();
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Timed out waiting for 'docker compose up' to finish after "
                            + DOCKER_COMPOSE_UP_TIMEOUT_SECONDS + "s (a first-time image build can be "
                            + "slow — check 'docker compose logs', or try again once images are cached).");
        }
        if (process.exitValue() != 0) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "'docker compose up' failed (exit " + process.exitValue() + "): " + lastLines(output, 20));
        }

        String lanIp = ServerIdCodec.detectLanIp();
        String serverId = ServerIdCodec.encode(lanIp);
        connectionManager.connectTo("localhost", ServerIdCodec.DEFAULT_PORT,
                connectionManager.getCurrentPredictorPort());
        try {
            waitForControlPlane(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!connectionManager.isControlPlaneConnected(true)) {
            return ConnectOutcome.failure(ErrorCategory.SERVER_ERROR,
                    "Docker containers started, but the control plane never became reachable on "
                            + "localhost:" + ServerIdCodec.DEFAULT_PORT + " — check 'docker compose logs'.");
        }

        LOG.info("Control plane ready (docker mode)");
        return ConnectOutcome.success("server", serverId);
    }

    /** Tries {@code docker-compose.yml} relative to the process's own working directory, then one
     * level up — covers both {@code mvn javafx:run} (run from {@code desktop-ui/}, so the compose file
     * is one level up) and a packaged jar run from the repo root (right there already). */
    private static Path locateComposeFile() {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path direct = cwd.resolve("docker-compose.yml");
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve("docker-compose.yml").normalize();
        return Files.exists(parent) ? parent : null;
    }

    /** Keeps a failed build's error response readable instead of dumping an entire multi-hundred-line
     * Docker build log back to the UI. */
    private static String lastLines(String text, int n) {
        String[] lines = text.split("\\R");
        int start = Math.max(0, lines.length - n);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    /** Polls until the control-plane channel is READY, or the budget expires. */
    private void waitForControlPlane(long budgetMillis) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (connectionManager.isControlPlaneConnected(true)) {
                return;
            }
            Thread.sleep(200);
        }
        LOG.warn("Control plane did not become reachable within {}ms", budgetMillis);
    }

    // ── GET /api/role/probe?address=host:port ──────────────────────────────

    private void handleProbe(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String address = query.getOrDefault("address", "");
        var hostPort = ConnectionDiagnostics.parseAddress(address, ServerIdCodec.DEFAULT_PORT);
        var result = ConnectionDiagnostics.probe(hostPort.host(), hostPort.port(), 5000);
        JsonSupport.sendJson(exchange, 200, ProbeResultDto.from(result));
    }

    // ── POST /api/role/node/join ────────────────────────────────────────────

    /**
     * Joins an existing control plane and registers this machine as a node. Ported from
     * {@code DesktopApp.connectAndRegisterNode} + {@code registerNodeAndShowDashboard}.
     */
    private void handleNodeJoin(HttpExchange exchange) throws IOException {
        // Stage CC: the class-level `catch (RuntimeException e)` in handle() does NOT cover a
        // malformed-JSON-syntax body — readValue throws a CHECKED JsonProcessingException in that
        // case, which escapes handle() entirely (it only declares `throws IOException`) and gets
        // silently swallowed by the JDK's HttpServer internals. handleServerLaunch already gets this
        // right with its own local try/catch; this mirrors that exact pattern, but — unlike
        // handleServerLaunch's optional body — a missing/invalid address here is a real 400, not a
        // silently-defaulted value.
        JoinRequestDto request;
        try {
            request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), JoinRequestDto.class);
        } catch (Exception e) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Malformed request body"));
            return;
        }
        if (request == null || request.address() == null || request.address().isBlank()) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "address is required"));
            return;
        }
        ConnectOutcome outcome = doNodeJoin(request.address(), request.enrollmentToken(), request.wantsEnrollment());
        if (outcome.ok()) {
            saveProfile("node", null, request.address(), request.wantsEnrollment(), outcome.label());
        }
        writeOutcome(exchange, outcome);
    }

    /**
     * @param enrollmentToken may be null — a reconnect to an already-enrolled server ({@code
     *     wantsEnrollment} true, no fresh token) relies on {@link NodeAgent#ensureEnrolled} finding
     *     this node's existing certificate on disk rather than needing a brand-new token every time.
     */
    private ConnectOutcome doNodeJoin(String addressRaw, String enrollmentToken, boolean wantsEnrollment) {
        var address = ConnectionDiagnostics.parseAddress(addressRaw, ServerIdCodec.DEFAULT_PORT);
        LOG.info("Connecting to server at {}:{}", address.host(), address.port());

        SystemMetricsReader metricsReader = new SystemMetricsReader();
        ControlPlaneProto.NodeCapabilities capabilities = metricsReader.detectCapabilities();
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown-node";
        }
        String nodeId = hostname;

        if (wantsEnrollment) {
            AgentCredentials credentials = AgentCredentials.fromEnvironment();
            try {
                // Makes the token from this specific attempt visible to ensureEnrolled without
                // requiring the user to have set an environment variable before launching the app —
                // EnvConfig checks a same-named system property first for exactly this reason. A null
                // token (auto-reconnect) leaves the property unset; ensureEnrolled only needs one at
                // all if this node has no usable certificate on disk yet.
                if (enrollmentToken != null) {
                    System.setProperty("NEXTGEN_ENROLLMENT_TOKEN", enrollmentToken);
                }
                NodeAgent.ensureEnrolled(credentials, nodeId, address.host(), address.port(),
                        capabilities, "desktop-ui");
            } catch (RuntimeException e) {
                LOG.error("Enrolment failed", e);
                return ConnectOutcome.failure(ErrorCategory.AUTHENTICATION,
                        "Could not enrol with the server: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                System.clearProperty("NEXTGEN_ENROLLMENT_TOKEN");
            }

            try {
                connectionManager.connectSecurely(address.host(), address.port(),
                        connectionManager.getCurrentPredictorPort(),
                        credentials.bootstrapCaCertificate().orElseThrow(),
                        credentials.certificate().orElseThrow(), credentials.privateKey());
            } catch (Exception e) {
                LOG.error("Could not open a secure channel after enrolment", e);
                return ConnectOutcome.failure(ErrorCategory.AUTHENTICATION,
                        "Enrolled, but could not open a secure connection: " + e.getMessage());
            }
        } else {
            connectionManager.connectTo(address.host(), address.port(),
                    connectionManager.getCurrentPredictorPort());
        }

        var client = connectionManager.getControlPlaneClient();
        if (client == null) {
            return ConnectOutcome.failure(ErrorCategory.NOT_FOUND,
                    "No connection to the control plane is available.");
        }
        try {
            String myIp = ServerIdCodec.detectLanIp();
            var response = client.registerNode(nodeId, myIp, connectionManager.getCurrentControlPlanePort(),
                    hostname, capabilities, "desktop-ui");
            registeredNodeId = response.getAssignedId();
            LOG.info("Node registered: {} (id={}, resumed={})",
                    response.getStatus(), registeredNodeId, response.getResumedExisting());
        } catch (Exception e) {
            LOG.error("Failed to register node", e);
            ErrorCategory category = e instanceof ControlPlaneUnavailableException cpe
                    ? cpe.category() : ErrorCategory.UNKNOWN;
            String reason = e instanceof ControlPlaneUnavailableException cpe
                    ? cpe.shortReason() : String.valueOf(e.getMessage());
            return ConnectOutcome.failure(category, "Connected, but registration failed: " + reason);
        }

        startHeartbeats();
        return ConnectOutcome.success("node", address.toString());
    }

    // ── Heartbeat sender (node mode only) ──────────────────────────────────

    /**
     * Starts sending heartbeats with real CPU and memory readings. Ported verbatim from
     * {@code DesktopApp.startHeartbeats}.
     */
    private void startHeartbeats() {
        if (heartbeatScheduler != null) {
            return;
        }

        SystemMetricsReader metrics = new SystemMetricsReader();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-sender");
            t.setDaemon(true);
            return t;
        });

        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                var client = connectionManager.getControlPlaneClient();
                if (client == null || registeredNodeId == null) return;

                MetricReading cpu = metrics.readCpuPercent();
                MetricReading memory = metrics.readMemoryPercent();

                var response = client.sendHeartbeat(registeredNodeId,
                        cpu.value(), cpu.available(), memory.value(), memory.available());

                if (response.getReregistrationRequired()) {
                    LOG.warn("Control plane no longer recognises node '{}' — re-registering", registeredNodeId);
                    reRegisterNode();
                }
            } catch (ControlPlaneUnavailableException e) {
                LOG.warn("Heartbeat failed: {}", e.shortReason());
            } catch (Exception e) {
                LOG.warn("Heartbeat failed: {}", e.getMessage());
            }
        }, 1, 2, TimeUnit.SECONDS);

        LOG.info("Heartbeat sender started (every 2s) for node '{}'", registeredNodeId);
    }

    private void reRegisterNode() {
        try {
            var client = connectionManager.getControlPlaneClient();
            if (client == null) return;
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            var response = client.registerNode(hostname, ServerIdCodec.detectLanIp(),
                    ServerIdCodec.DEFAULT_PORT, hostname);
            registeredNodeId = response.getAssignedId();
            LOG.info("Re-registered as '{}'", registeredNodeId);
        } catch (Exception e) {
            LOG.error("Re-registration failed; will retry on the next heartbeat", e);
        }
    }

    /** Stops the heartbeat scheduler, if one was ever started. Called from {@code LocalUiServer.stop()}. */
    void stop() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            try {
                if (!heartbeatScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    heartbeatScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            heartbeatScheduler = null;
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new java.util.HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}
