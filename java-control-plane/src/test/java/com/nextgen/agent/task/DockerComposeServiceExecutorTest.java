package com.nextgen.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.agent.DockerCapabilityDetector;
import com.nextgen.controlplane.task.TaskKindDomain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, not mocked — see {@link DockerComposeRunnerTest}'s own Javadoc for the self-skip discipline
 * this class follows identically. Covers the {@link TaskExecutor}-facing behavior on top of
 * {@link DockerComposeRunner}: spec parsing, the real {@code COMPLETED}-means-"stopped" redefinition,
 * and real cancellation routing.
 *
 * <p>Exit-code tests use the single-word busybox applets {@code true}/{@code false}, never
 * {@code sh -c "exit N"} — {@link DockerComposeServiceExecutor#buildRunArgs}'s own documented v1
 * limitation is a plain {@code commandText.split("\\s+")} with no shell-quoting support, so a quoted
 * multi-word command string splits into broken tokens (a stray embedded {@code "} character) and the
 * container's own {@code sh} then exits 2 on a genuine syntax error, not the intended code. This was a
 * real, previously-latent bug: on Windows, {@code ProcessBuilder} reconstructs the argument array back
 * into a single command line before invoking the native process, which happened to paper over the
 * broken tokens; on Linux, argv is passed through literally, so `sh` sees the malformed script directly
 * — confirmed live when this exact test failed on a real Ubuntu GitHub Actions runner (exit code 2)
 * after passing locally on Windows. Two of the three affected tests didn't notice because they only
 * assert the *restart count* in the thrown message, not the specific exit code value — only the test
 * asserting {@code exit_code == 0} exactly actually caught it.
 */
class DockerComposeServiceExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TaskEventSink NO_OP_SINK = (line, stderr) -> { };

    @BeforeAll
    static void requireRealDockerAndPrefetchImages() {
        var docker = new DockerCapabilityDetector().detect();
        Assumptions.assumeTrue(docker.available(),
                "Docker daemon is not reachable on this machine — skipping, not failing");
        pullImage("busybox");
        pullImage("hello-world");
    }

    @Test
    void reportsItsKind() {
        assertEquals(TaskKindDomain.DOCKER_COMPOSE_SERVICE, new DockerComposeServiceExecutor().kind());
    }

    @Test
    void rejectsASpecWithNoImageBeforeTouchingDocker() {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(
                "t1", "{\"project_name\":\"p\",\"service_name\":\"s\"}", NO_OP_SINK));
    }

    @Test
    void executeRunsARealContainerToCompletionAndReturnsResultJson() throws Exception {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String taskId = UUID.randomUUID().toString();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "hello")
                .put("image", "hello-world")
                .toString();

        String resultJson = executor.execute(taskId, payload, NO_OP_SINK);

        JsonNode result = MAPPER.readTree(resultJson);
        assertEquals(0, result.get("exit_code").asInt());
        assertFalse(result.get("stopped_by_request").asBoolean());
        assertTrue(result.get("container_name").asText().startsWith("nx-nxtest-hello-"),
                result.get("container_name").asText());
    }

    /** Stage V: a migrated task keeps the SAME taskId when redispatched (ProactiveMigrator reassigns,
     * never mints a new id) — two real, separate {@code execute()} calls for the exact same taskId
     * (simulating "migrated away and back") must never collide on container name, and both must
     * actually run to completion rather than one failing with "name already in use". */
    @Test
    void twoExecuteCallsForTheSameTaskIdNeverCollideOnContainerName() throws Exception {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String taskId = UUID.randomUUID().toString();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "migrated")
                .put("image", "hello-world")
                .toString();

        String firstResult = executor.execute(taskId, payload, NO_OP_SINK);
        String secondResult = executor.execute(taskId, payload, NO_OP_SINK);

        JsonNode first = MAPPER.readTree(firstResult);
        JsonNode second = MAPPER.readTree(secondResult);
        assertEquals(0, first.get("exit_code").asInt());
        assertEquals(0, second.get("exit_code").asInt());
        assertNotEquals(first.get("container_name").asText(), second.get("container_name").asText(),
                "two separate execute() calls for the same taskId must never derive the same container name");
    }

    @Test
    void cancelStopsARunningServiceCleanlyAndCompletesRatherThanFails() throws Exception {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String taskId = UUID.randomUUID().toString();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "sleeper")
                .put("image", "busybox")
                .put("command", "sleep 300")
                .toString();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = pool.submit(() -> executor.execute(taskId, payload, NO_OP_SINK));

            // No incremental output to synchronize on (busybox's builtin `sleep` prints nothing) — a
            // short fixed wait after submission is enough for `docker run` to have actually created and
            // started the container, since the image was already pre-pulled in @BeforeAll.
            Thread.sleep(2000);
            executor.cancel(taskId);

            String resultJson = future.get(20, TimeUnit.SECONDS);
            JsonNode result = MAPPER.readTree(resultJson);
            assertTrue(result.get("stopped_by_request").asBoolean());
        } finally {
            pool.shutdown();
        }
    }

    /** Stage KK: real Docker, real limit — proves the flags built in {@code buildRunArgs} actually reach
     * the container, not just that the right JSON shape was constructed. */
    @Test
    void executeAppliesRealCpuAndMemoryLimits() throws Exception {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String taskId = UUID.randomUUID().toString();
        String containerNamePrefix = "nx-nxtest-limited-" + taskId.substring(0, 8);
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "limited")
                .put("image", "busybox")
                .put("command", "sleep 20")
                .set("resources", MAPPER.createObjectNode()
                        .put("cpuLimit", "1.5")
                        .put("memoryLimit", "134217728")) // 128MB, in bytes so docker inspect's numeric
                                                            // field is directly comparable without parsing
                                                            // a unit suffix back out.
                .toString();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = pool.submit(() -> executor.execute(taskId, payload, NO_OP_SINK));
            Thread.sleep(2000);

            // Stage V appends a per-execute() random attemptId suffix to the real container name, so the
            // exact name can no longer be predicted ahead of time — discover it via `docker ps` instead
            // of assuming equality with the prefix this test itself constructed.
            String realContainerName = discoverContainerName(containerNamePrefix);
            Process inspect = new ProcessBuilder("docker", "inspect", "--format",
                    "{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}", realContainerName)
                    .redirectErrorStream(true).start();
            String inspectOutput;
            try (var reader = inspect.inputReader()) {
                inspectOutput = reader.readLine();
            }
            assertTrue(inspect.waitFor(10, TimeUnit.SECONDS), "docker inspect timed out");
            assertTrue(inspectOutput != null && inspectOutput.contains("1500000000"),
                    "expected NanoCpus for 1.5 CPUs (1500000000), got: " + inspectOutput);
            assertTrue(inspectOutput.contains("134217728"),
                    "expected Memory limit of 134217728 bytes, got: " + inspectOutput);

            executor.cancel(taskId);
            future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
    }

    /** Stage LL: {@code restart: "no"} (the default) — a real crashing container fails immediately,
     * zero restarts, matching pre-Stage-LL behavior exactly. */
    @Test
    void policyNoFailsImmediatelyWithoutRestarting() {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "crasher")
                .put("image", "busybox")
                .put("command", "false")
                .toString();

        Exception e = assertThrows(RuntimeException.class,
                () -> executor.execute(UUID.randomUUID().toString(), payload, NO_OP_SINK));
        assertTrue(e.getMessage().contains("after 0 restart(s)"), e.getMessage());
    }

    /** Stage LL: {@code restart: "on-failure:2"} against a container that always exits 1 — restarts
     * exactly up to the cap, then fails honestly rather than looping forever. */
    @Test
    void onFailurePolicyRestartsUpToMaxAttemptsThenFails() {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "alwaysfails")
                .put("image", "busybox")
                .put("command", "false")
                .set("restart", MAPPER.createObjectNode().put("policy", "on-failure").put("maxAttempts", 2))
                .toString();

        Exception e = assertThrows(RuntimeException.class,
                () -> executor.execute(UUID.randomUUID().toString(), payload, NO_OP_SINK));
        assertTrue(e.getMessage().contains("after 2 restart(s)"), e.getMessage());
    }

    /** Stage LL: {@code restart: "always"} restarts even a cleanly-exiting container — real Docker
     * semantics for a long-lived daemon, bounded here by {@code maxAttempts} since this is one-shot task
     * execution, not a daemon. Proves the task ends up {@code COMPLETED} (no exception), not
     * {@code FAILED}, once the cap is hit on a policy that isn't {@code on-failure}. */
    @Test
    void alwaysPolicyKeepsRestartingAfterACleanExitUntilTheCap() throws Exception {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "alwaysexits0")
                .put("image", "busybox")
                .put("command", "true")
                .set("restart", MAPPER.createObjectNode().put("policy", "always").put("maxAttempts", 2))
                .toString();

        String resultJson = executor.execute(UUID.randomUUID().toString(), payload, NO_OP_SINK);

        JsonNode result = MAPPER.readTree(resultJson);
        assertEquals(0, result.get("exit_code").asInt());
        assertFalse(result.get("stopped_by_request").asBoolean());
        assertEquals(2, result.get("restart_count").asInt());
    }

    /** Stage MM end-to-end: a real {@code healthCheck} that always fails, on a container that would
     * otherwise run forever ({@code sleep 300}) — the ONLY way this task ever terminates is the health
     * poller detecting real "unhealthy" via {@code docker inspect} and killing the container, which then
     * flows through the exact same Stage LL restart-loop machinery as a real crash. Proves the health
     * flags reached the real {@code docker run} AND that an unhealthy container gets killed and retried,
     * not just left running and ignored. */
    @Test
    void anAlwaysUnhealthyContainerGetsKilledAndRetriedByTheRestartLoop() throws Exception {
        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "unhealthy")
                .put("image", "busybox")
                .put("command", "sleep 300")
                .set("healthCheck", MAPPER.createObjectNode()
                        .put("command", "exit 1")
                        .put("intervalSeconds", 1)
                        .put("retries", 1))
                .toString();
        com.fasterxml.jackson.databind.node.ObjectNode payloadNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(payload);
        payloadNode.set("restart", MAPPER.createObjectNode().put("policy", "on-failure").put("maxAttempts", 1));

        long start = System.currentTimeMillis();
        Exception e = assertThrows(RuntimeException.class,
                () -> executor.execute(UUID.randomUUID().toString(), payloadNode.toString(), NO_OP_SINK));
        assertTrue(e.getMessage().contains("after 1 restart(s)"), e.getMessage());
        assertTrue(System.currentTimeMillis() - start < 60_000,
                "should fail well within the health-check + restart-backoff window, not hang");
    }

    /** Stage NN end-to-end: a real secret, pre-populated in a {@link NodeSecretStore} exactly as
     * {@link TaskChannelClient} would have buffered it after a real {@code SecretMaterial} message,
     * must actually be readable from {@code /run/secrets/<name>} inside the container, AND must never
     * appear in {@code docker inspect}'s {@code Config.Env} — the whole reason it's a file mount and not
     * an environment variable. */
    @Test
    void aSecretIsMountedAsAFileAndNeverAppearsAsAnEnvVar() throws Exception {
        NodeSecretStore nodeSecretStore = new NodeSecretStore();
        String secretValue = "sup3r-s3cr3t-" + UUID.randomUUID();
        nodeSecretStore.put("db-password", secretValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DockerComposeServiceExecutor executor =
                new DockerComposeServiceExecutor(new NodeBuildContextStore(), null, nodeSecretStore);
        String taskId = UUID.randomUUID().toString();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "secretreader")
                .put("image", "busybox")
                .put("command", "cat /run/secrets/db-password")
                .set("secrets", MAPPER.createArrayNode().add("db-password"))
                .toString();

        StringBuilder capturedOutput = new StringBuilder();
        String resultJson = executor.execute(taskId, payload,
                (line, stderr) -> capturedOutput.append(line).append('\n'));

        JsonNode result = MAPPER.readTree(resultJson);
        assertEquals(0, result.get("exit_code").asInt());
        assertTrue(capturedOutput.toString().contains(secretValue),
                "container must have actually read the real secret from /run/secrets/db-password");
    }

    /** Companion to the test above: proves the negative directly against a REAL running container's
     * {@code docker inspect} output, rather than only checking this process's own args construction. */
    @Test
    void aSecretNeverAppearsInDockerInspectsEnvOnARunningContainer() throws Exception {
        NodeSecretStore nodeSecretStore = new NodeSecretStore();
        String secretValue = "sup3r-s3cr3t-" + UUID.randomUUID();
        nodeSecretStore.put("api-key", secretValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DockerComposeServiceExecutor executor =
                new DockerComposeServiceExecutor(new NodeBuildContextStore(), null, nodeSecretStore);
        String taskId = UUID.randomUUID().toString();
        String containerName = "nx-nxtest-secretlive-" + taskId.substring(0, 8);
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "secretlive")
                .put("image", "busybox")
                .put("command", "sleep 20")
                .set("secrets", MAPPER.createArrayNode().add("api-key"))
                .toString();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = pool.submit(() -> executor.execute(taskId, payload, NO_OP_SINK));
            Thread.sleep(2000);

            Process inspect = new ProcessBuilder("docker", "inspect", "--format", "{{.Config.Env}}",
                    containerName).redirectErrorStream(true).start();
            String inspectOutput;
            try (var reader = inspect.inputReader()) {
                inspectOutput = reader.readLine();
            }
            inspect.waitFor(10, TimeUnit.SECONDS);
            assertTrue(inspectOutput != null && !inspectOutput.contains(secretValue),
                    "secret value must never appear in docker inspect's Config.Env; got: " + inspectOutput);

            executor.cancel(taskId);
            future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Stage N end-to-end: a real build context (a tiny Dockerfile, tar.gz'd via the actual {@code tar}
     * binary — the same tool {@link DockerComposeServiceExecutor} itself shells out to for extraction)
     * is placed exactly where {@link NodeBuildContextStore#pathFor} says {@link TaskChannelClient} would
     * have buffered it, so this exercises verify → extract → {@code docker build} → {@code docker run}
     * for real, without needing an actual gRPC transfer.
     */
    @Test
    void executeBuildsFromSourceWhenNoImageIsGiven(@TempDir Path tempDir) throws Exception {
        Path buildContextSource = tempDir.resolve("source-context");
        Files.createDirectories(buildContextSource);
        Files.writeString(buildContextSource.resolve("Dockerfile"),
                "FROM busybox\nCMD [\"echo\", \"built-from-source-ok\"]\n", StandardCharsets.UTF_8);

        Path tarball = tempDir.resolve("context.tar.gz");
        Process tarProcess = new ProcessBuilder("tar", "-czf", tarball.toString(), "-C",
                buildContextSource.toString(), ".").redirectErrorStream(true).start();
        assertTrue(tarProcess.waitFor(30, TimeUnit.SECONDS), "tar creation timed out");
        assertEquals(0, tarProcess.exitValue(), "failed to create the test's own tar.gz fixture");

        String sha256 = sha256Hex(tarball);
        NodeBuildContextStore buildContextStore = new NodeBuildContextStore(tempDir.resolve("node-store"));
        String contextId = UUID.randomUUID().toString();
        Files.createDirectories(buildContextStore.pathFor(contextId).getParent());
        Files.copy(tarball, buildContextStore.pathFor(contextId));

        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor(buildContextStore);
        String taskId = UUID.randomUUID().toString();
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "built")
                .set("build", MAPPER.createObjectNode()
                        .put("context_id", contextId)
                        .put("dockerfile_path", "Dockerfile")
                        .put("sha256", sha256))
                .toString();

        StringBuilder capturedOutput = new StringBuilder();
        String resultJson = executor.execute(taskId, payload, (line, stderr) -> capturedOutput.append(line).append('\n'));

        JsonNode result = MAPPER.readTree(resultJson);
        assertEquals(0, result.get("exit_code").asInt());
        assertTrue(capturedOutput.toString().contains("built-from-source-ok"),
                "expected the freshly built image's own output, got: " + capturedOutput);
    }

    @Test
    void executeFailsHonestlyWhenTheReceivedContextHashDoesNotMatch(@TempDir Path tempDir) throws Exception {
        Path tarball = tempDir.resolve("context.tar.gz");
        Files.writeString(tarball, "not actually a tarball", StandardCharsets.UTF_8);

        NodeBuildContextStore buildContextStore = new NodeBuildContextStore(tempDir.resolve("node-store"));
        String contextId = UUID.randomUUID().toString();
        Files.createDirectories(buildContextStore.pathFor(contextId).getParent());
        Files.copy(tarball, buildContextStore.pathFor(contextId));

        DockerComposeServiceExecutor executor = new DockerComposeServiceExecutor(buildContextStore);
        String payload = MAPPER.createObjectNode()
                .put("project_name", "nxtest")
                .put("service_name", "built")
                .set("build", MAPPER.createObjectNode()
                        .put("context_id", contextId)
                        .put("dockerfile_path", "Dockerfile")
                        .put("sha256", "0000000000000000000000000000000000000000000000000000000000000000"))
                .toString();

        Exception e = assertThrows(IllegalStateException.class,
                () -> executor.execute(UUID.randomUUID().toString(), payload, NO_OP_SINK));
        assertTrue(e.getMessage().contains("integrity check failed"), e.getMessage());
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Stage V appends a per-{@code execute()} random {@code attemptId} suffix to every real container
     * name, so a test can no longer predict the exact name ahead of time — this discovers the real,
     * currently-running name via a live {@code docker ps} filter on the still-stable prefix instead. */
    private static String discoverContainerName(String namePrefix) throws Exception {
        Process ps = new ProcessBuilder("docker", "ps", "--filter", "name=" + namePrefix,
                "--format", "{{.Names}}").redirectErrorStream(true).start();
        String name;
        try (var reader = ps.inputReader()) {
            name = reader.readLine();
        }
        assertTrue(ps.waitFor(10, TimeUnit.SECONDS), "docker ps timed out");
        assertTrue(name != null && !name.isBlank(),
                "no running container found with name prefix '" + namePrefix + "'");
        return name;
    }

    private static void pullImage(String image) {
        try {
            Process pull = new ProcessBuilder("docker", "pull", image)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            pull.waitFor(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best-effort — if the pull genuinely fails, the tests below fail with a clear "docker run"
            // error instead of a confusing setup-time one.
        }
    }
}
