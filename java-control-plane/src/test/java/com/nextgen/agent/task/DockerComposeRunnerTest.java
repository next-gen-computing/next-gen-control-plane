package com.nextgen.agent.task;

import com.nextgen.agent.DockerCapabilityDetector;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, not mocked — invokes the actual {@code docker} binary against real containers. Self-skips
 * (rather than failing) whenever this machine's Docker daemon isn't reachable, matching
 * {@link com.nextgen.agent.DockerCapabilityDetectorTest}'s own established "self-skip and say why"
 * precedent — a Docker-less CI runner still sees a fully green suite, never a fabricated pass.
 */
class DockerComposeRunnerTest {

    @BeforeAll
    static void requireRealDockerAndPrefetchImages() {
        var docker = new DockerCapabilityDetector().detect();
        Assumptions.assumeTrue(docker.available(),
                "Docker daemon is not reachable on this machine — skipping, not failing");
        // Pulled once up front so the individual tests' own timing isn't dominated by a first-pull —
        // busybox is a ~5MB image, so this is fast even on a cold cache.
        pullImage("busybox");
    }

    @Test
    void aShortLivedContainerRunsToCompletionWithARealExitCodeAndOutput() throws Exception {
        DockerComposeRunner runner = new DockerComposeRunner(uniqueName("short-lived"));
        List<String> lines = new CopyOnWriteArrayList<>();

        int exitCode = runner.run(
                List.of("busybox", "sh", "-c", "echo hello-from-container; exit 0"),
                (line, stderr) -> lines.add(line));

        assertEquals(0, exitCode);
        assertFalse(runner.wasStopRequested());
        assertTrue(lines.stream().anyMatch(l -> l.contains("hello-from-container")),
                "expected real container stdout to be forwarded, got: " + lines);
    }

    @Test
    void aNonZeroExitCodeIsReportedHonestly() throws Exception {
        DockerComposeRunner runner = new DockerComposeRunner(uniqueName("nonzero-exit"));

        int exitCode = runner.run(List.of("busybox", "sh", "-c", "exit 7"), (line, stderr) -> { });

        assertEquals(7, exitCode);
        assertFalse(runner.wasStopRequested());
    }

    @Test
    void stopGracefullyEndsALongRunningContainerFromAnotherThread() throws Exception {
        DockerComposeRunner runner = new DockerComposeRunner(uniqueName("long-running"));
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger exitCodeHolder = new AtomicInteger(Integer.MIN_VALUE);

        Thread runnerThread = new Thread(() -> {
            try {
                int exitCode = runner.run(
                        List.of("busybox", "sh", "-c", "echo running; sleep 300"),
                        (line, stderr) -> {
                            if (line.contains("running")) {
                                started.countDown();
                            }
                        });
                exitCodeHolder.set(exitCode);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        runnerThread.start();

        assertTrue(started.await(20, TimeUnit.SECONDS), "container never reported it started running");
        runner.stop();
        runnerThread.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runnerThread.isAlive(), "run() never returned after stop() was called");
        assertTrue(runner.wasStopRequested());
        assertNotEquals(Integer.MIN_VALUE, exitCodeHolder.get(), "run() never actually returned an exit code");
    }

    private static String uniqueName(String label) {
        return "nx-test-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void pullImage(String image) {
        try {
            Process pull = new ProcessBuilder("docker", "pull", image)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            pull.waitFor(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best-effort — if the pull genuinely fails (e.g. no network), the tests below will fail
            // with a clear "docker run" error instead of a confusing setup-time one.
        }
    }
}
