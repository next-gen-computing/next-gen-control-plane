package com.nextgen.controlplane;

import com.nextgen.agent.task.DockerComposeRunner;
import com.nextgen.controlplane.task.SynchronizedStreamObserver;
import com.nextgen.proto.ControlPlaneProto.ComposeCommand;
import com.nextgen.proto.ControlPlaneProto.ComposeDownRequest;
import com.nextgen.proto.ControlPlaneProto.ComposeEvent;
import com.nextgen.proto.ControlPlaneProto.ComposeFinished;
import com.nextgen.proto.ControlPlaneProto.ComposeUpRequest;
import com.nextgen.proto.ControlPlaneProto.TaskLogEvent;
import com.nextgen.proto.LocalDockerExecutionGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage Q: single-machine "cloud" mode — {@code nx cloud up} talks to this directly, bypassing
 * {@code RegisterNode}/{@code TaskChannel}/{@code NodeRegistry}/{@code TaskRegistry}/{@code JobRegistry}/
 * Raft entirely. See {@code LocalDockerExecution}'s own proto Javadoc for why this is a separate
 * service, never gated by {@code RaftLeaderRedirectInterceptor}.
 *
 * <p><b>Zero duplication of Docker-invocation logic</b>: this calls the exact same
 * {@link DockerComposeRunner} the distributed node-side executor uses (see
 * {@code DockerComposeServiceExecutor}) — specifically its general {@link DockerComposeRunner#runCommand}
 * form, since cloud mode orchestrates a WHOLE compose file as one attached {@code docker compose up}
 * process rather than one container at a time the way distributed execution does.
 */
public final class LocalDockerExecutionServiceImpl extends LocalDockerExecutionGrpc.LocalDockerExecutionImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(LocalDockerExecutionServiceImpl.class);

    private final Path workDir;
    private final ExecutorService executorPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "local-docker-exec");
        t.setDaemon(true);
        return t;
    });

    public LocalDockerExecutionServiceImpl() {
        this(Path.of(System.getProperty("java.io.tmpdir", "."), "nextgen-cloud-compose"));
    }

    public LocalDockerExecutionServiceImpl(Path workDir) {
        this.workDir = workDir;
    }

    @Override
    public StreamObserver<ComposeCommand> runCompose(StreamObserver<ComposeEvent> responseObserver) {
        StreamObserver<ComposeEvent> safeObserver = new SynchronizedStreamObserver<>(responseObserver);
        return new Session(safeObserver);
    }

    /** One session per {@code RunCompose} stream — a fresh compose file per {@code up}, tracked so a
     * later {@code down} on the SAME stream knows what to tear down. */
    private final class Session implements StreamObserver<ComposeCommand> {
        private final StreamObserver<ComposeEvent> outbound;
        private final AtomicLong logSequence = new AtomicLong(0);
        private volatile Path composeFile;
        private volatile String projectName = "";
        /** Stage X: set for the duration of a real, currently-running compose project — null once it
         * finishes naturally OR was never started. Lets {@link #onError}/{@link #onCompleted} know
         * there's something real to tear down on a dropped client stream. */
        private volatile DockerComposeRunner activeRunner;

        Session(StreamObserver<ComposeEvent> outbound) {
            this.outbound = outbound;
        }

        @Override
        public void onNext(ComposeCommand command) {
            switch (command.getCommandCase()) {
                case UP -> executorPool.submit(() -> handleUp(command.getUp()));
                case DOWN -> executorPool.submit(() -> handleDown(command.getDown()));
                default -> { /* COMMAND_NOT_SET */ }
            }
        }

        @Override
        public void onError(Throwable t) {
            LOG.warn("⚠ RunCompose stream for '{}' closed with error: {}", projectName, t.getMessage());
            cleanUpOnDisconnect();
        }

        @Override
        public void onCompleted() {
            LOG.info("☁ RunCompose stream closed for '{}'", projectName);
            cleanUpOnDisconnect();
        }

        /** Stage X: a dropped client stream (Ctrl-C, laptop sleep, a network blip) previously left the
         * ENTIRE running compose project — and every container it started, plus its generated compose
         * YAML file — orphaned forever, since nothing on this path ever called anything but the happy,
         * explicit-{@code down}-command cleanup in {@link #handleDown}. Runs a REAL
         * {@code docker compose down} (not just killing the local blocked {@code up} process, which
         * doesn't reliably stop the containers it started) on a background pool thread so this callback
         * itself never blocks the gRPC event loop. */
        private void cleanUpOnDisconnect() {
            DockerComposeRunner runner = activeRunner;
            Path file = composeFile;
            if (runner == null || file == null) {
                return; // no compose project was ever actually started on this stream
            }
            String targetProject = projectName;
            executorPool.submit(() -> {
                runner.stop(); // unblocks handleUp's own blocking runCommand() call
                try {
                    Process down = new ProcessBuilder(
                            "docker", "compose", "-f", file.toString(), "-p", targetProject, "down")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    down.waitFor(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.warn("⚠ Could not run 'docker compose down' for orphaned project '{}' after "
                            + "disconnect: {}", targetProject, e.getMessage());
                }
                deleteQuietly(file);
            });
        }

        private void handleUp(ComposeUpRequest request) {
            projectName = request.getProjectName();
            Path file;
            try {
                file = writeComposeFile(projectName, request.getComposeYaml());
            } catch (IOException e) {
                sendFinished(false, "Could not write compose file: " + e.getMessage());
                return;
            }
            composeFile = file;

            List<String> command = new ArrayList<>(
                    List.of("docker", "compose", "-f", file.toString(), "-p", projectName, "up"));
            if (request.getBuild()) {
                command.add("--build");
            }

            LOG.info("☁ Starting compose project '{}' (build={})", projectName, request.getBuild());
            DockerComposeRunner runner = new DockerComposeRunner("nx-cloud-" + projectName);
            activeRunner = runner;
            try {
                int exitCode = runner.runCommand(command, this::sendLog);
                sendFinished(exitCode == 0,
                        exitCode == 0 ? "compose up exited cleanly" : "compose up exited with code " + exitCode);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                sendFinished(false, "compose up failed: " + e.getMessage());
            } finally {
                activeRunner = null;
            }
        }

        private void handleDown(ComposeDownRequest request) {
            String targetProject = !request.getProjectName().isBlank() ? request.getProjectName() : projectName;
            Path file = composeFile;
            if (file == null) {
                sendFinished(false, "FAILED — no compose file known for project '" + targetProject
                        + "' on this stream (was 'up' ever sent?)");
                return;
            }

            List<String> command = List.of("docker", "compose", "-f", file.toString(), "-p", targetProject, "down");
            LOG.info("☁ Stopping compose project '{}'", targetProject);
            DockerComposeRunner runner = new DockerComposeRunner("nx-cloud-down-" + targetProject);
            try {
                int exitCode = runner.runCommand(command, this::sendLog);
                sendFinished(exitCode == 0,
                        exitCode == 0 ? "compose down completed" : "compose down exited with code " + exitCode);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                sendFinished(false, "compose down failed: " + e.getMessage());
            } finally {
                deleteQuietly(file);
            }
        }

        private void sendLog(String line, boolean stderr) {
            try {
                outbound.onNext(ComposeEvent.newBuilder()
                        .setLog(TaskLogEvent.newBuilder()
                                .setLine(line)
                                .setStderr(stderr)
                                .setSequence(logSequence.incrementAndGet())
                                .setEmittedAtEpochMillis(System.currentTimeMillis())
                                .build())
                        .build());
            } catch (RuntimeException e) {
                LOG.debug("Could not send compose log line: {}", e.getMessage());
            }
        }

        private void sendFinished(boolean success, String message) {
            try {
                outbound.onNext(ComposeEvent.newBuilder()
                        .setFinished(ComposeFinished.newBuilder().setSuccess(success).setMessage(message).build())
                        .build());
            } catch (RuntimeException e) {
                LOG.debug("Could not send compose finished event: {}", e.getMessage());
            }
        }
    }

    private Path writeComposeFile(String projectName, String composeYaml) throws IOException {
        Files.createDirectories(workDir);
        Path file = workDir.resolve("nx-cloud-" + sanitize(projectName) + "-" + UUID.randomUUID() + ".yml");
        Files.writeString(file, composeYaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temp compose file is a disk-space nit, not a correctness issue.
        }
    }
}
