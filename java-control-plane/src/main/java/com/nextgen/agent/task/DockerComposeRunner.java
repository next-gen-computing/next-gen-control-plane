package com.nextgen.agent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runs ONE container via the real {@code docker} CLI — not the full {@code docker compose} tool. Each
 * task this class backs represents exactly one already-resolved service from a project (see
 * {@link DockerComposeServiceExecutor}), so a direct {@code docker run} is simpler and more directly
 * controllable (attaching to real stdout/stderr, stopping cleanly by container name) than synthesizing
 * a one-service compose file just to invoke {@code docker compose up} on it.
 *
 * <p>One instance backs exactly one container's lifetime — {@link DockerComposeServiceExecutor} creates
 * a fresh one per task.
 */
public final class DockerComposeRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DockerComposeRunner.class);

    private final String containerName;
    private volatile Process process;
    private volatile boolean stopRequested;

    public DockerComposeRunner(String containerName) {
        this.containerName = containerName;
    }

    /**
     * Runs the container attached, blocking the calling thread until it exits (whether on its own, or
     * because {@link #stop} was called from another thread). Real stdout/stderr lines are forwarded to
     * {@code onLine} — {@code (line, isStderr)} — as they arrive, from two dedicated reader threads (not
     * the calling thread), so a chatty container can't deadlock on a full pipe buffer while this thread
     * is blocked in {@link Process#waitFor()}.
     *
     * @return the container's real exit code — including whatever code a {@link #stop}-triggered
     *         termination produces; the caller decides how to interpret that (see
     *         {@link #wasStopRequested()}).
     */
    public int run(List<String> dockerRunArgs, BiConsumer<String, Boolean> onLine)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--name");
        command.add(containerName);
        command.add("--rm");
        command.addAll(dockerRunArgs);

        LOG.debug("Starting container '{}': {}", containerName, command);
        return executeProcess(command, onLine);
    }

    /**
     * Stage N: {@code docker build}s an image from a build context already unpacked to local disk (see
     * {@link DockerComposeServiceExecutor} for how the tarball got there and was verified). Blocks the
     * calling thread until the build finishes, streaming real stdout/stderr the same way {@link #run}
     * does — a build can be just as long-running and chatty as a container's own output.
     *
     * @param dockerfilePath relative to {@code contextDir}; {@code null}/blank uses Docker's own
     *                       default of {@code Dockerfile} at the context root.
     * @return the real exit code of the {@code docker build} process — a non-zero code means the image
     *         was never produced; the caller must not attempt to {@code docker run} {@code tag}.
     */
    public int build(String dockerfilePath, Path contextDir, String tag, BiConsumer<String, Boolean> onLine)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("build");
        if (dockerfilePath != null && !dockerfilePath.isBlank()) {
            command.add("-f");
            command.add(contextDir.resolve(dockerfilePath).toString());
        }
        command.add("-t");
        command.add(tag);
        command.add(contextDir.toString());

        LOG.debug("Building image '{}' from '{}': {}", tag, contextDir, command);
        return executeProcess(command, onLine);
    }

    /**
     * Stage Q: runs an arbitrary {@code docker}/{@code docker compose} command line — e.g.
     * {@code docker compose -f <file> -p <project> up --build} — the general form {@link #run}/
     * {@link #build} are themselves thin convenience wrappers around. Cloud mode
     * ({@code LocalDockerExecutionServiceImpl}) uses this directly to orchestrate a WHOLE compose file
     * as one attached process, rather than one container at a time the way distributed execution does.
     * Same streaming/{@link #stop} semantics as {@link #run}.
     */
    public int runCommand(List<String> command, BiConsumer<String, Boolean> onLine)
            throws IOException, InterruptedException {
        LOG.debug("Running: {}", command);
        return executeProcess(command, onLine);
    }

    /** Shared by {@link #run}, {@link #build}, and {@link #runCommand}: spawns {@code command},
     * forwards real stdout/stderr to {@code onLine} from two dedicated reader threads (never the
     * calling thread — see {@link #run}'s own Javadoc for why), and blocks until the process exits or
     * {@link #stop} destroys it. */
    private int executeProcess(List<String> command, BiConsumer<String, Boolean> onLine)
            throws IOException, InterruptedException {
        process = new ProcessBuilder(command).start();

        Thread stdoutReader = streamLines(process.getInputStream(), line -> onLine.accept(line, false));
        Thread stderrReader = streamLines(process.getErrorStream(), line -> onLine.accept(line, true));

        int exitCode = process.waitFor();
        stdoutReader.join(2_000);
        stderrReader.join(2_000);
        return exitCode;
    }

    /**
     * Best-effort graceful stop: {@code docker stop} first (lets the container's own shutdown handling
     * run, e.g. SIGTERM before SIGKILL), falling back to destroying the local process handle if that
     * doesn't work. Safe to call from a different thread than {@link #run} (that is, in fact, the whole
     * point — a cancellation arrives on the gRPC callback thread while {@code run} blocks the task
     * executor thread), and safe to call more than once.
     */
    public void stop() {
        stopRequested = true;
        try {
            Process stopProcess = new ProcessBuilder("docker", "stop", "--time", "10", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!stopProcess.waitFor(15, TimeUnit.SECONDS)) {
                stopProcess.destroyForcibly();
            }
        } catch (IOException e) {
            LOG.debug("docker stop for '{}' could not run: {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    /** True once {@link #stop} has been called — lets a caller distinguish a requested stop (expected,
     * not a failure) from the container crashing on its own. */
    public boolean wasStopRequested() {
        return stopRequested;
    }

    private static Thread streamLines(InputStream stream, Consumer<String> onLine) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // The stream closed — e.g. the process exited or was destroyed. Not an error to report.
            }
        }, "docker-output-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
