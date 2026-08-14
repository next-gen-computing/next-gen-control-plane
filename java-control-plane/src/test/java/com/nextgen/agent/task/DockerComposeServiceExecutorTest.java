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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, not mocked — see {@link DockerComposeRunnerTest}'s own Javadoc for the self-skip discipline
 * this class follows identically. Covers the {@link TaskExecutor}-facing behavior on top of
 * {@link DockerComposeRunner}: spec parsing, the real {@code COMPLETED}-means-"stopped" redefinition,
 * and real cancellation routing.
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
