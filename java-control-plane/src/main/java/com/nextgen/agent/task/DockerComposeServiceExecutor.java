package com.nextgen.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.agent.ControlPlaneConnection;
import com.nextgen.controlplane.task.TaskKindDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs one service from a distributed docker-compose-style project — see {@code TaskKind.
 * TASK_KIND_DOCKER_COMPOSE_SERVICE}'s proto Javadoc for the payload JSON shape:
 * {@code {"project_name", "service_name", "image", "build": {"context_id", "dockerfile_path",
 * "sha256"}, "command", "environment": {...}, "ports": [...], "relay_ports": [...]}}. Exactly one of
 * {@code image}/{@code build} is expected — {@code image} for an already-built, pullable image
 * (Stage M); {@code build} to build from source on this node (Stage N — see
 * {@link #resolveAndBuildImage}). {@code relay_ports} (Stage O) lists already-{@code ports}-published
 * host ports that a peer service on another node may need to reach — see {@link PortTunnelClient}.
 * Delegates the actual container lifecycle to {@link DockerComposeRunner}; this class is the
 * {@link TaskExecutor}-facing adapter — parses the spec, builds the {@code docker run} arguments, and
 * wires real cancellation.
 *
 * <p><b>Redefines what {@code COMPLETED} means</b> for this task kind, deliberately: {@link #execute}
 * only returns once the container actually stops — whether that's a real crash ({@link #execute}
 * throws, task goes {@code FAILED}) or a requested stop via {@link #cancel} (real, e.g. from an
 * operator's {@code nx down}), which is a normal, honest completion, not a failure.
 */
public final class DockerComposeServiceExecutor implements TaskExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(DockerComposeServiceExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TAR_EXTRACT_TIMEOUT_SECONDS = 60;

    /** One runner per currently-executing task — this executor instance is shared across every
     * concurrently-dispatched {@code DOCKER_COMPOSE_SERVICE} task on this node (see
     * {@code TaskChannelClient}'s {@code Map<TaskKindDomain, TaskExecutor>}), so per-task state lives
     * here, keyed by task id, never assumed to be a single in-flight task. */
    private final Map<String, DockerComposeRunner> runnersByTaskId = new ConcurrentHashMap<>();
    /** One entry per open relay for a currently-executing task — a service can declare more than one
     * {@code relay_ports} entry, so this is a list, not a single client. Stage O. */
    private final Map<String, List<PortTunnelClient>> tunnelsByTaskId = new ConcurrentHashMap<>();
    private final NodeBuildContextStore buildContextStore;
    private final ControlPlaneConnection connection;
    private final NodeSecretStore secretStore;

    public DockerComposeServiceExecutor() {
        this(new NodeBuildContextStore(), null);
    }

    /** @param buildContextStore Stage N: where {@link TaskChannelClient} buffers a dispatched build's
     * tarball before this executor unpacks and builds it — see {@link #resolveAndBuildImage}.
     * Injectable so a test can point it at a {@code @TempDir}. */
    public DockerComposeServiceExecutor(NodeBuildContextStore buildContextStore) {
        this(buildContextStore, null);
    }

    /** @param connection Stage O: the SAME connection {@code NodeAgent} already dials, reused to open
     * a {@link PortTunnelClient} stream per {@code relay_ports} entry — never a second connection. Null
     * disables relaying (a spec with {@code relay_ports} still runs the container; it just logs a
     * warning and stays cross-node-unreachable), matching every constructor above used by tests that
     * don't exercise this feature. */
    public DockerComposeServiceExecutor(NodeBuildContextStore buildContextStore, ControlPlaneConnection connection) {
        this(buildContextStore, connection, new NodeSecretStore());
    }

    /** @param secretStore Stage NN: where {@link TaskChannelClient} buffers a dispatched secret's
     * plaintext before this executor writes it to a short-lived temp file and bind-mounts it — see
     * {@link #resolveSecretMounts}. Injectable so a test can pre-populate it directly. */
    public DockerComposeServiceExecutor(NodeBuildContextStore buildContextStore, ControlPlaneConnection connection,
                                        NodeSecretStore secretStore) {
        this.buildContextStore = buildContextStore;
        this.connection = connection;
        this.secretStore = secretStore;
    }

    @Override
    public TaskKindDomain kind() {
        return TaskKindDomain.DOCKER_COMPOSE_SERVICE;
    }

    @Override
    public String execute(String taskId, String payloadJson, TaskEventSink events) throws Exception {
        JsonNode spec = MAPPER.readTree(payloadJson);
        String projectName = textOrDefault(spec, "project_name", "project");
        String serviceName = textOrDefault(spec, "service_name", "service");
        String containerName = sanitizeContainerName(
                "nx-" + projectName + "-" + serviceName + "-" + shortId(taskId));
        RestartPolicySpec restartPolicy = parseRestartPolicy(spec);

        DockerComposeRunner runner = new DockerComposeRunner(containerName);
        runnersByTaskId.put(taskId, runner);
        Path extractedContextDir = null;
        Map<String, Path> secretFiles = Map.of();
        try {
            String image = spec.path("image").asText();
            if (image.isBlank()) {
                JsonNode build = spec.path("build");
                if (build.isMissingNode() || build.path("context_id").asText().isBlank()) {
                    throw new IllegalArgumentException(
                            "service spec has neither 'image' nor a 'build.context_id' — nothing to run");
                }
                String tag = sanitizeContainerName(
                        "nx-build-" + projectName + "-" + serviceName + "-" + shortId(taskId));
                extractedContextDir = resolveAndBuildImage(build, tag, taskId, runner, events);
                image = tag;
            }

            secretFiles = resolveSecretMounts(spec, taskId);
            List<String> args = buildRunArgs(spec, image, secretFiles);
            openRelayTunnels(spec, taskId, projectName, serviceName);
            boolean healthTriggersRestart = !spec.path("healthCheck").path("command").asText().isBlank()
                    && restartPolicy.configuredToRestart();
            long healthPollIntervalSeconds = Math.max(1,
                    spec.path("healthCheck").path("intervalSeconds").asInt(30));

            int exitCode = 0;
            boolean stoppedByRequest = false;
            int restartCount = 0;
            while (true) {
                LOG.info("🐳 Starting service '{}' (project '{}', task {}, attempt {}) as container '{}'",
                        serviceName, projectName, taskId, restartCount + 1, containerName);
                ScheduledExecutorService healthPoller = healthTriggersRestart
                        ? startHealthPoller(containerName, healthPollIntervalSeconds) : null;
                try {
                    exitCode = runner.run(args, events::logLine);
                } catch (InterruptedException e) {
                    // The executor pool is being shut down (e.g. the agent process is exiting) — stop
                    // the real container rather than abandoning it running on the host, then restore the
                    // interrupt and propagate, matching Stage M's explicit shutdown-correctness
                    // requirement.
                    LOG.warn("Interrupted while running container '{}' — stopping it before shutting down",
                            containerName);
                    runner.stop();
                    Thread.currentThread().interrupt();
                    throw e;
                } finally {
                    if (healthPoller != null) {
                        healthPoller.shutdownNow();
                    }
                }
                stoppedByRequest = runner.wasStopRequested();
                if (stoppedByRequest || !restartPolicy.shouldRestart(exitCode, restartCount)) {
                    break;
                }
                restartCount++;
                String logMessage = "service '" + serviceName + "' exited (code " + exitCode
                        + ") — restarting per policy '" + restartPolicy.policy() + "' (attempt "
                        + (restartCount + 1) + ")";
                events.logLine(logMessage, false);
                LOG.info("🔁 Restarting '{}' per policy '{}' (attempt {})", containerName,
                        restartPolicy.policy(), restartCount + 1);
                try {
                    Thread.sleep(backoffMillis(restartCount));
                } catch (InterruptedException e) {
                    runner.stop();
                    Thread.currentThread().interrupt();
                    throw e;
                }
                if (runner.wasStopRequested()) {
                    // A cancel() arrived while we were backing off between attempts — honor it instead
                    // of starting another attempt; the stale exitCode from the last real attempt is
                    // irrelevant once stoppedByRequest is true (see the check below).
                    stoppedByRequest = true;
                    break;
                }
            }

            if (exitCode != 0 && !stoppedByRequest) {
                throw new RuntimeException("container '" + containerName + "' exited with code " + exitCode
                        + " after " + restartCount + " restart(s)");
            }
            return MAPPER.createObjectNode()
                    .put("container_name", containerName)
                    .put("exit_code", exitCode)
                    .put("stopped_by_request", stoppedByRequest)
                    .put("restart_count", restartCount)
                    .toString();
        } finally {
            runnersByTaskId.remove(taskId);
            closeRelayTunnels(taskId);
            if (extractedContextDir != null) {
                deleteRecursively(extractedContextDir);
            }
            deleteSecretFiles(secretFiles);
        }
    }

    /**
     * Stage O: opens one {@link PortTunnelClient} per {@code relay_ports} entry, if any — see that
     * class's Javadoc for what it actually does. Non-blocking (each client's own {@code start()} only
     * opens the gRPC stream and sends a hello frame, neither of which waits on the container itself),
     * so this is safe to call right before {@code runner.run} blocks the calling thread for the
     * container's whole lifetime.
     */
    private void openRelayTunnels(JsonNode spec, String taskId, String projectName, String serviceName) {
        JsonNode relayPortsNode = spec.path("relay_ports");
        if (!relayPortsNode.isArray() || relayPortsNode.isEmpty()) {
            return;
        }
        if (connection == null) {
            LOG.warn("⚠ Service '{}/{}' declares relay_ports but this node has no control-plane "
                    + "connection wired for relaying — it will run but stay cross-node-unreachable",
                    projectName, serviceName);
            return;
        }
        List<PortTunnelClient> tunnels = new ArrayList<>();
        for (JsonNode portNode : relayPortsNode) {
            int port = portNode.asInt();
            PortTunnelClient tunnel = new PortTunnelClient(connection, projectName, serviceName, port);
            tunnel.start();
            tunnels.add(tunnel);
        }
        tunnelsByTaskId.put(taskId, tunnels);
    }

    private void closeRelayTunnels(String taskId) {
        List<PortTunnelClient> tunnels = tunnelsByTaskId.remove(taskId);
        if (tunnels != null) {
            for (PortTunnelClient tunnel : tunnels) {
                tunnel.close();
            }
        }
    }

    private List<String> buildRunArgs(JsonNode spec, String image, Map<String, Path> secretFiles) {
        List<String> args = new ArrayList<>();
        JsonNode envNode = spec.path("environment");
        for (Iterator<String> it = envNode.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            args.add("-e");
            args.add(key + "=" + envNode.path(key).asText());
        }
        JsonNode portsNode = spec.path("ports");
        if (portsNode.isArray()) {
            for (JsonNode port : portsNode) {
                args.add("-p");
                args.add(port.asText());
            }
        }
        addResourceArgs(args, spec.path("resources"));
        addHealthCheckArgs(args, spec.path("healthCheck"));
        addSecretMountArgs(args, secretFiles);
        args.add(image);
        String commandText = spec.path("command").asText();
        if (!commandText.isBlank()) {
            // A single whitespace-split command string — a documented v1 limitation, no shell-quoting
            // support. A caller needing real shell semantics should pass "command": "sh -c '...'" and
            // rely on the container's own shell to interpret quoting, not this split.
            for (String part : commandText.split("\\s+")) {
                args.add(part);
            }
        }
        return args;
    }

    /** Stage KK: {@code --cpus}/{@code --memory}/{@code --memory-reservation} — the subset of Compose's
     * {@code deploy.resources} that {@code docker run} itself directly supports on a single host. Values
     * ride through from {@link com.nextgen.cli.ComposeFileParser.ResourceLimits} as the raw strings
     * Docker already accepts (e.g. {@code "512m"}, {@code "1.5"}); a blank/absent field is simply
     * omitted, never defaulted to a guessed value. Must run before {@code args.add(image)} — Docker
     * requires every {@code docker run} option to precede the image argument. */
    private static void addResourceArgs(List<String> args, JsonNode resources) {
        addFlagIfPresent(args, "--cpus", resources, "cpuLimit");
        addFlagIfPresent(args, "--memory", resources, "memoryLimit");
        addFlagIfPresent(args, "--memory-reservation", resources, "memoryReservation");
    }

    private static void addFlagIfPresent(List<String> args, String flag, JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!value.isBlank()) {
            args.add(flag);
            args.add(value);
        }
    }

    /** Stage MM: reuses Docker's own health-check engine rather than hand-rolling one — {@code
     * --health-cmd} runs {@code healthCheck.command} through the container's shell on the configured
     * interval, and Docker itself exposes the result via {@code docker inspect}'s {@code State.Health.
     * Status} (read by {@link #startHealthPoller}) and embedded in {@code docker ps}'s own {@code Status}
     * string (read by {@code DockerStateCollector} for dashboard visibility). Interval/timeout/start
     * period are already whole-second integers from {@link com.nextgen.cli.ComposeFileParser}; Docker's
     * own {@code --health-*} flags want a duration string, hence the {@code "s"} suffix. */
    private static void addHealthCheckArgs(List<String> args, JsonNode healthCheck) {
        String command = healthCheck.path("command").asText();
        if (command.isBlank()) {
            return;
        }
        args.add("--health-cmd");
        args.add(command);
        addDurationFlag(args, "--health-interval", healthCheck, "intervalSeconds");
        addDurationFlag(args, "--health-timeout", healthCheck, "timeoutSeconds");
        addDurationFlag(args, "--health-start-period", healthCheck, "startPeriodSeconds");
        JsonNode retriesNode = healthCheck.path("retries");
        if (retriesNode.isInt()) {
            args.add("--health-retries");
            args.add(String.valueOf(retriesNode.asInt()));
        }
    }

    private static void addDurationFlag(List<String> args, String flag, JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isInt() || value.isLong()) {
            args.add(flag);
            args.add(value.asLong() + "s");
        }
    }

    /** Stage NN: bind-mounts each resolved secret file read-only at {@code /run/secrets/<name>} —
     * deliberately a file mount, never an environment variable, since {@code docker inspect}/{@code ps}
     * reveal env vars in plaintext but not the contents of a bind-mounted file. Matches Docker's own
     * {@code secrets:} convention exactly, so an application already written to read Docker/Compose
     * secrets from {@code /run/secrets/} needs no changes to work here. */
    private static void addSecretMountArgs(List<String> args, Map<String, Path> secretFiles) {
        for (Map.Entry<String, Path> entry : secretFiles.entrySet()) {
            args.add("-v");
            args.add(entry.getValue() + ":/run/secrets/" + entry.getKey() + ":ro");
        }
    }

    private static final int DEFAULT_MAX_RESTART_ATTEMPTS = 5;
    private static final long BASE_RESTART_BACKOFF_MILLIS = 500;
    private static final long MAX_RESTART_BACKOFF_MILLIS = 10_000;

    /** Stage LL: deliberately a Java-side retry loop, not Docker's native {@code --restart} — see the
     * class Javadoc for why (it would fight {@code --rm} and this class's {@code waitFor()}-based
     * lifecycle model). {@code maxAttempts} bounds every restarting policy uniformly, including
     * {@code always}/{@code unless-stopped} — real Docker restarts those forever for a long-lived daemon
     * container, but this is a bounded, one-shot task execution, so an unbounded retry loop would be a
     * real resource leak, not a feature. */
    private record RestartPolicySpec(String policy, int maxAttempts) {
        boolean shouldRestart(int exitCode, int restartsSoFar) {
            if (restartsSoFar >= maxAttempts) {
                return false;
            }
            return switch (policy) {
                case "always", "unless-stopped" -> true;
                case "on-failure" -> exitCode != 0;
                default -> false;
            };
        }

        /** Whether ANY restart could ever happen under this policy — gates the health poller (Stage
         * MM): killing a still-running-but-unhealthy container only to have it end up {@code FAILED}
         * with no reattempt (policy {@code "no"}) would be a pure downgrade versus just leaving it
         * running, so the poller only starts when a restart is actually possible. */
        boolean configuredToRestart() {
            return !"no".equals(policy);
        }
    }

    private static RestartPolicySpec parseRestartPolicy(JsonNode spec) {
        JsonNode restartNode = spec.path("restart");
        String policy = restartNode.path("policy").asText("no");
        int maxAttempts = restartNode.path("maxAttempts").asInt(DEFAULT_MAX_RESTART_ATTEMPTS);
        return new RestartPolicySpec(policy, maxAttempts);
    }

    /** Exponential backoff between restart attempts, capped — {@code attempt} is the 1-indexed restart
     * count (the attempt about to be made, not the one that just failed). */
    private static long backoffMillis(int attempt) {
        long backoff = BASE_RESTART_BACKOFF_MILLIS * (1L << Math.min(attempt - 1, 10));
        return Math.min(backoff, MAX_RESTART_BACKOFF_MILLIS);
    }

    /** Stage MM: the payoff of a health check — Docker already runs the check itself (via the
     * {@code --health-*} flags {@link #addHealthCheckArgs} passed) and records the result in
     * {@code docker inspect}'s {@code State.Health.Status}; this thread just polls that result and, on
     * a transition to {@code "unhealthy"}, kills the container so {@code runner.run}'s blocking
     * {@code waitFor()} unblocks with a non-zero exit — which the restart loop then evaluates through
     * the normal {@link RestartPolicySpec#shouldRestart} path, exactly as if the process had crashed on
     * its own. Deliberately does NOT go through {@link DockerComposeRunner#stop()} — that sets
     * {@code stopRequested}, which the restart loop treats as an intentional cancel (never restart);
     * an unhealthy container should be RETRIED, not treated as cleanly stopped. */
    private ScheduledExecutorService startHealthPoller(String containerName, long intervalSeconds) {
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-poll-" + containerName);
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            if ("unhealthy".equals(inspectHealthStatus(containerName))) {
                LOG.warn("🩺 Container '{}' reported unhealthy — killing it so the restart policy can "
                        + "reattempt", containerName);
                killContainer(containerName);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        return poller;
    }

    private static String inspectHealthStatus(String containerName) {
        try {
            Process process = new ProcessBuilder("docker", "inspect", "--format",
                    "{{.State.Health.Status}}", containerName).redirectErrorStream(true).start();
            String line;
            try (var reader = process.inputReader()) {
                line = reader.readLine();
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            // The container may already be gone (exited between polls) or docker may be transiently
            // unreachable — either way, "no status" is the honest answer, not a crash of the poller.
            return "";
        }
    }

    private static void killContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "kill", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debug("docker kill for unhealthy container '{}' could not run: {}", containerName,
                    e.getMessage());
        }
    }

    /**
     * Stage NN: resolves this service's declared {@code secrets} list into real, short-lived temp files
     * — each name is {@link NodeSecretStore#consume consumed} (the plaintext was already shipped and
     * buffered here BEFORE this dispatch arrived, same ordering guarantee {@link #resolveAndBuildImage}
     * relies on for build contexts), written to an owner-only-permission file under the system temp
     * directory, and bind-mounted read-only by {@link #addSecretMountArgs}. Deleted again in
     * {@code execute}'s {@code finally} block via {@link #deleteSecretFiles} regardless of outcome.
     *
     * @throws IllegalArgumentException if a referenced secret was never received on this node — fails
     *         the task honestly before ever invoking Docker, matching {@link #resolveAndBuildImage}'s
     *         own precedent for a missing build context.
     */
    private Map<String, Path> resolveSecretMounts(JsonNode spec, String taskId) throws IOException {
        JsonNode secretsNode = spec.path("secrets");
        if (!secretsNode.isArray() || secretsNode.isEmpty()) {
            return Map.of();
        }
        Map<String, Path> files = new java.util.LinkedHashMap<>();
        Path secretsDir = Path.of(System.getProperty("java.io.tmpdir", "."), "nextgen-secrets");
        Files.createDirectories(secretsDir);
        for (JsonNode nameNode : secretsNode) {
            String name = nameNode.asText();
            if (name.isBlank()) {
                continue;
            }
            var value = secretStore.consume(name);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "FAILED — secret '" + name + "' was never received on this node");
            }
            Path file = secretsDir.resolve(sanitizeContainerName(taskId + "-" + name));
            com.nextgen.security.PkiPaths.writeSecret(file, value.get());
            files.put(name, file);
        }
        return files;
    }

    /** Best-effort — a leftover secret temp file is a disk-space nit, not a task-correctness issue,
     * matching {@link #deleteRecursively}'s own precedent for extracted build contexts. */
    private static void deleteSecretFiles(Map<String, Path> secretFiles) {
        for (Path file : secretFiles.values()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.debug("Could not clean up secret file '{}': {}", file, e.getMessage());
            }
        }
    }

    /**
     * Stage N: verifies, unpacks, and builds a service's image from source. The tarball itself was
     * already streamed down this node's {@code TaskChannel} and buffered by {@link #buildContextStore}
     * BEFORE this method ever runs — see {@code TaskDispatcher.shipBuildContext} and
     * {@link NodeBuildContextStore}'s own Javadoc for why that ordering is guaranteed. This method's
     * job is purely: confirm what arrived is really what the control plane meant to send (a hash
     * mismatch fails honestly, never a silent best-effort build of possibly-corrupt bytes), then hand
     * it to Docker.
     *
     * @return the directory the context was unpacked into, so the caller can clean it up afterward.
     */
    private Path resolveAndBuildImage(JsonNode build, String tag, String taskId, DockerComposeRunner runner,
                                      TaskEventSink events) throws Exception {
        String contextId = build.path("context_id").asText();
        String expectedSha256 = build.path("sha256").asText();
        String dockerfilePath = build.path("dockerfile_path").asText();

        Path tarball = buildContextStore.pathFor(contextId);
        if (!Files.exists(tarball)) {
            throw new IllegalArgumentException(
                    "FAILED — build context '" + contextId + "' was never received on this node");
        }
        if (!expectedSha256.isBlank()) {
            String actualSha256 = sha256Hex(tarball);
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException("FAILED — build context integrity check failed "
                        + "(expected sha256 " + expectedSha256 + ", got " + actualSha256 + ")");
            }
        }

        Path extractedDir = tarball.resolveSibling(contextId + "-extracted-" + shortId(taskId));
        extractTarGz(tarball, extractedDir);

        LOG.info("🔨 Building image '{}' from context '{}' (task {})", tag, contextId, taskId);
        int exitCode;
        try {
            exitCode = runner.build(dockerfilePath.isBlank() ? null : dockerfilePath, extractedDir, tag,
                    events::logLine);
        } catch (InterruptedException e) {
            runner.stop();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (exitCode != 0) {
            throw new RuntimeException("docker build for '" + tag + "' exited with code " + exitCode);
        }
        return extractedDir;
    }

    @Override
    public void cancel(String taskId) {
        DockerComposeRunner runner = runnersByTaskId.get(taskId);
        if (runner != null) {
            runner.stop();
        }
    }

    /** Docker container names must match {@code [a-zA-Z0-9][a-zA-Z0-9_.-]*} — project/service names
     * come from operator-supplied compose files and could contain anything, so this replaces every
     * other character rather than letting {@code docker run} reject the name at the worst possible
     * moment (mid-dispatch, after everything else has already succeeded). */
    private static String sanitizeContainerName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    private static String shortId(String taskId) {
        return taskId.substring(0, Math.min(8, taskId.length()));
    }

    /** {@code JsonNode.asText(String)} — the version with a default value — is deprecated in the
     * Jackson version this project pins; this replaces it without losing the fallback behavior for a
     * genuinely missing/blank field. */
    private static String textOrDefault(JsonNode spec, String fieldName, String defaultValue) {
        String value = spec.path(fieldName).asText();
        return value.isBlank() ? defaultValue : value;
    }

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm (JLS-guaranteed) — this can never actually happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Shells out to the real {@code tar} binary rather than adding a TAR-handling dependency — matches
     * this project's established "the CLI the user already has is enough" precedent (see
     * {@link DockerComposeRunner}'s own Javadoc). Both Windows (bsdtar, bundled since Windows 10
     * 1803/Server 2019) and Linux/macOS ship a real {@code tar}. */
    private static void extractTarGz(Path tarball, Path destDir) throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Process process = new ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", destDir.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = process.inputReader()) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(TAR_EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("tar extraction of '" + tarball + "' timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("tar extraction of '" + tarball + "' failed (exit " + process.exitValue()
                    + "): " + output);
        }
    }

    /** Best-effort cleanup of an unpacked build context after use — never lets a cleanup failure fail
     * an otherwise-successful task. */
    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Leftover temp files are a disk-space nit, not a task-correctness issue.
                }
            });
        } catch (IOException e) {
            LOG.debug("Could not clean up extracted build context '{}': {}", dir, e.getMessage());
        }
    }
}
