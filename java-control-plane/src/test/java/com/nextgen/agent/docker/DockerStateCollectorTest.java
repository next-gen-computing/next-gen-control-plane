package com.nextgen.agent.docker;

import com.nextgen.agent.DockerCapabilityDetector;
import com.nextgen.proto.ControlPlaneProto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, not mocked — invokes the actual {@code docker} binary against real containers/images. Self-
 * skips (rather than failing) whenever this machine's Docker daemon isn't reachable, matching {@link
 * com.nextgen.agent.task.DockerComposeRunnerTest}'s own established "self-skip and say why" precedent.
 */
class DockerStateCollectorTest {

    private final DockerStateCollector collector = new DockerStateCollector();
    private final List<String> startedContainerNames = new ArrayList<>();

    @BeforeAll
    static void requireRealDocker() {
        var docker = new DockerCapabilityDetector().detect();
        Assumptions.assumeTrue(docker.available(),
                "Docker daemon is not reachable on this machine — skipping, not failing");
        pullImage("busybox");
    }

    @AfterEach
    void cleanUpAnyContainersThisTestStarted() {
        for (String name : startedContainerNames) {
            runQuietly("docker", "rm", "-f", name);
        }
        startedContainerNames.clear();
    }

    @Test
    void listContainersIncludesARealRunningContainerWithRealFields() {
        String name = runDetached();

        Optional<ControlPlaneProto.DockerContainerInfo> found = awaitContainer(name);

        assertTrue(found.isPresent(), "the real container this test just started must actually be listed");
        ControlPlaneProto.DockerContainerInfo info = found.get();
        assertFalse(info.getContainerId().isBlank(), "must be Docker's real container id, never fabricated");
        assertTrue(info.getImage().contains("busybox"), info.getImage());
        assertEquals("running", info.getStatus().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void listImagesIncludesTheRealPulledBusyboxImage() {
        boolean found = collector.listImages().stream()
                .anyMatch(img -> img.getRepository().contains("busybox"));
        assertTrue(found, "busybox was pulled in @BeforeAll and must appear in the real image list");
    }

    @Test
    void stopThenStartRoundTripsARealContainersActualDockerState() {
        String name = runDetached();
        String containerId = awaitContainer(name).orElseThrow().getContainerId();

        DockerStateCollector.ControlResult stopResult = collector.stop(containerId);
        assertTrue(stopResult.ok(), stopResult.message());
        Optional<ControlPlaneProto.DockerContainerInfo> afterStop = awaitContainerWithStatus(name, "exited");
        assertTrue(afterStop.isPresent(), "container must really be exited after a real docker stop");

        DockerStateCollector.ControlResult startResult = collector.start(containerId);
        assertTrue(startResult.ok(), startResult.message());
        Optional<ControlPlaneProto.DockerContainerInfo> afterStart = awaitContainerWithStatus(name, "running");
        assertTrue(afterStart.isPresent(), "container must really be running again after a real docker start");
    }

    @Test
    void removeReallyDeletesTheContainerFromDocker() {
        String name = runDetached();
        String containerId = awaitContainer(name).orElseThrow().getContainerId();
        collector.stop(containerId);

        DockerStateCollector.ControlResult result = collector.remove(containerId);

        assertTrue(result.ok(), result.message());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (collector.listContainers().stream().noneMatch(c -> c.getName().contains(name))) {
                return;
            }
            sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("container was not actually removed from docker");
    }

    @Test
    void controlActionAgainstANonexistentContainerFailsHonestly() {
        DockerStateCollector.ControlResult result = collector.stop("nx-test-does-not-exist-" + UUID.randomUUID());
        assertFalse(result.ok(), "docker has no such container — must be reported as a real failure");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String runDetached() {
        String name = "nx-test-collector-" + UUID.randomUUID().toString().substring(0, 8);
        startedContainerNames.add(name);
        try {
            Process run = new ProcessBuilder("docker", "run", "-d", "--name", name,
                    "busybox", "sh", "-c", "sleep 300")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = run.waitFor(30, TimeUnit.SECONDS);
            if (!finished || run.exitValue() != 0) {
                throw new IllegalStateException("could not start real test container '" + name + "'");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return name;
    }

    private Optional<ControlPlaneProto.DockerContainerInfo> awaitContainer(String name) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<ControlPlaneProto.DockerContainerInfo> found = collector.listContainers().stream()
                    .filter(c -> c.getName().contains(name)).findFirst();
            if (found.isPresent()) {
                return found;
            }
            sleep(100);
        }
        return Optional.empty();
    }

    private Optional<ControlPlaneProto.DockerContainerInfo> awaitContainerWithStatus(String name, String status) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<ControlPlaneProto.DockerContainerInfo> found = collector.listContainers().stream()
                    .filter(c -> c.getName().contains(name)
                            && c.getStatus().equalsIgnoreCase(status))
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
            sleep(150);
        }
        return Optional.empty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pullImage(String image) {
        runQuietly("docker", "pull", image);
    }

    private static void runQuietly(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.waitFor(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best-effort setup/cleanup — a genuine failure surfaces via the actual test assertions.
        }
    }
}
