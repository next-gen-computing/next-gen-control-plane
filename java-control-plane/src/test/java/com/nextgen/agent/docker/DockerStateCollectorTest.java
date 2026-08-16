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

    /** Stage MM: a container with no HEALTHCHECK at all must report an empty (never fabricated)
     * health_status — the baseline every other health-status assertion is contrasted against. */
    @Test
    void aContainerWithNoHealthcheckReportsEmptyHealthStatus() {
        String name = runDetached();

        ControlPlaneProto.DockerContainerInfo info = awaitContainer(name).orElseThrow();

        assertEquals("", info.getHealthStatus());
    }

    /** Stage MM: a real {@code --health-cmd} that always succeeds must eventually report "healthy" —
     * proves the regex extraction against Docker's own real {@code Status} string, not a synthetic one. */
    @Test
    void aContainerWithARealPassingHealthcheckEventuallyReportsHealthy() {
        String name = "nx-test-collector-health-" + UUID.randomUUID().toString().substring(0, 8);
        startedContainerNames.add(name);
        try {
            Process run = new ProcessBuilder("docker", "run", "-d", "--name", name,
                    "--health-cmd", "true", "--health-interval", "1s", "--health-retries", "1",
                    "--health-start-period", "1s", "busybox", "sh", "-c", "sleep 300")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertTrue(run.waitFor(30, TimeUnit.SECONDS) && run.exitValue() == 0,
                    "could not start real health-checked test container '" + name + "'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        long deadline = System.currentTimeMillis() + 20_000;
        String lastSeen = "";
        while (System.currentTimeMillis() < deadline) {
            Optional<ControlPlaneProto.DockerContainerInfo> found = collector.listContainers().stream()
                    .filter(c -> c.getName().contains(name)).findFirst();
            if (found.isPresent()) {
                lastSeen = found.get().getHealthStatus();
                if ("healthy".equals(lastSeen)) {
                    return;
                }
            }
            sleep(300);
        }
        org.junit.jupiter.api.Assertions.fail(
                "container never reported healthy via listContainers(); last seen health_status: " + lastSeen);
    }

    @Test
    void controlActionAgainstANonexistentContainerFailsHonestly() {
        DockerStateCollector.ControlResult result = collector.stop("nx-test-does-not-exist-" + UUID.randomUUID());
        assertFalse(result.ok(), "docker has no such container — must be reported as a real failure");
    }

    // ── Stage FF: image/volume/network write actions ──────────────────

    @Test
    void tagImageReallyCreatesANewRealTagForTheSameImage() {
        String targetTag = "nx-test-tag-" + UUID.randomUUID().toString().substring(0, 8) + ":latest";

        DockerStateCollector.ControlResult result = collector.tagImage("busybox", targetTag);

        assertTrue(result.ok(), result.message());
        try {
            boolean found = collector.listImages().stream()
                    .anyMatch(i -> targetTag.startsWith(i.getRepository() + ":" + i.getTag()));
            assertTrue(found, "the newly-tagged image must actually appear in a real docker images listing");
        } finally {
            runQuietly("docker", "rmi", "-f", targetTag);
        }
    }

    @Test
    void createVolumeReallyCreatesARealDockerVolume() {
        String name = "nx-test-volume-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            DockerStateCollector.ControlResult result = collector.createVolume(name);

            assertTrue(result.ok(), result.message());
            assertTrue(collector.listVolumes().stream().anyMatch(v -> v.getName().equals(name)),
                    "the newly-created volume must actually appear in a real docker volume ls listing");
        } finally {
            runQuietly("docker", "volume", "rm", "-f", name);
        }
    }

    @Test
    void removeVolumeReallyDeletesARealDockerVolume() {
        String name = "nx-test-volume-rm-" + UUID.randomUUID().toString().substring(0, 8);
        collector.createVolume(name);

        DockerStateCollector.ControlResult result = collector.removeVolume(name);

        assertTrue(result.ok(), result.message());
        assertTrue(collector.listVolumes().stream().noneMatch(v -> v.getName().equals(name)),
                "the volume must actually be gone from a real docker volume ls listing");
    }

    @Test
    void createNetworkReallyCreatesARealDockerNetwork() {
        String name = "nx-test-network-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            DockerStateCollector.ControlResult result = collector.createNetwork(name);

            assertTrue(result.ok(), result.message());
            assertTrue(collector.listNetworks().stream().anyMatch(n -> n.getName().equals(name)),
                    "the newly-created network must actually appear in a real docker network ls listing");
        } finally {
            runQuietly("docker", "network", "rm", name);
        }
    }

    @Test
    void removeNetworkReallyDeletesARealDockerNetwork() {
        String name = "nx-test-network-rm-" + UUID.randomUUID().toString().substring(0, 8);
        collector.createNetwork(name);

        DockerStateCollector.ControlResult result = collector.removeNetwork(name);

        assertTrue(result.ok(), result.message());
        assertTrue(collector.listNetworks().stream().noneMatch(n -> n.getName().equals(name)),
                "the network must actually be gone from a real docker network ls listing");
    }

    /** Empirically confirmed against this environment's real Docker CLI: {@code docker rmi -f} on a
     * reference that doesn't exist still exits 0 — {@code -f} makes removal idempotent, mirroring
     * {@code rm -f}'s own "ensure this is gone" semantics, not a "must already exist" requirement. */
    @Test
    void removeImageAgainstANonexistentReferenceIsAnIdempotentSuccessMatchingRealDockerBehavior() {
        DockerStateCollector.ControlResult result =
                collector.removeImage("nx-test-does-not-exist-" + UUID.randomUUID() + ":latest");
        assertTrue(result.ok(), result.message());
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
