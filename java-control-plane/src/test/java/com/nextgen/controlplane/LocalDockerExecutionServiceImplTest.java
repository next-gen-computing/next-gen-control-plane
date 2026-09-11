package com.nextgen.controlplane;

import com.nextgen.agent.DockerCapabilityDetector;
import com.nextgen.proto.ControlPlaneProto.ComposeCommand;
import com.nextgen.proto.ControlPlaneProto.ComposeDownRequest;
import com.nextgen.proto.ControlPlaneProto.ComposeEvent;
import com.nextgen.proto.ControlPlaneProto.ComposeUpRequest;
import com.nextgen.proto.LocalDockerExecutionGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, not mocked — see {@code DockerComposeRunnerTest}'s own Javadoc for the self-skip discipline
 * this class follows identically. Proves cloud mode's actual value proposition: a whole compose file,
 * sent as one YAML string over one RPC, really runs via {@code docker compose up} on this one machine —
 * zero {@code RegisterNode}/{@code TaskChannel} traffic, no cluster involved at all.
 */
class LocalDockerExecutionServiceImplTest {

    private Server server;
    private ManagedChannel channel;
    private LocalDockerExecutionGrpc.LocalDockerExecutionStub stub;

    @BeforeAll
    static void requireRealDocker() {
        var docker = new DockerCapabilityDetector().detect();
        Assumptions.assumeTrue(docker.available(),
                "Docker daemon is not reachable on this machine — skipping, not failing");
    }

    @BeforeEach
    void setUp(@TempDir Path workDir) throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(new LocalDockerExecutionServiceImpl(workDir)).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = LocalDockerExecutionGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void upStreamsRealLogsAndDownStopsItCleanly() throws Exception {
        String project = "nx-cloud-test-" + System.currentTimeMillis();
        String composeYaml = """
                services:
                  hello:
                    image: busybox
                    command: sh -c "echo cloud-mode-works; sleep 300"
                """;

        LinkedBlockingQueue<ComposeEvent> events = new LinkedBlockingQueue<>();
        StreamObserver<ComposeCommand> outbound = stub.runCompose(new StreamObserver<>() {
            @Override public void onNext(ComposeEvent value) { events.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });

        outbound.onNext(ComposeCommand.newBuilder()
                .setUp(ComposeUpRequest.newBuilder().setProjectName(project).setComposeYaml(composeYaml).build())
                .build());

        boolean sawExpectedOutput = awaitLogContaining(events, "cloud-mode-works", 60);
        assertTrue(sawExpectedOutput, "expected real 'docker compose up' output to stream back over the RPC");

        outbound.onNext(ComposeCommand.newBuilder()
                .setDown(ComposeDownRequest.newBuilder().setProjectName(project).build())
                .build());

        ComposeEvent finished = awaitFinished(events, 60);
        assertNotNull(finished, "compose down must eventually produce a finished event");
    }

    /** Stage X: previously, {@code handleUp} never stored its runner anywhere — a dropped client stream
     * (this test's stand-in for Ctrl-C/laptop sleep/a network blip) left the entire compose project,
     * and every container it started, orphaned on the host forever. Proves the real fix: the project is
     * actually torn down (verified via real {@code docker ps} filtered by compose's own project label)
     * once the stream disconnects WITHOUT an explicit {@code down} ever being sent. */
    @Test
    void aDroppedClientStreamActuallyTearsDownTheOrphanedComposeProject() throws Exception {
        String project = "nx-cloud-drop-test-" + System.currentTimeMillis();
        String composeYaml = """
                services:
                  hello:
                    image: busybox
                    command: sh -c "echo drop-test-started; sleep 300"
                """;

        LinkedBlockingQueue<ComposeEvent> events = new LinkedBlockingQueue<>();
        StreamObserver<ComposeCommand> outbound = stub.runCompose(new StreamObserver<>() {
            @Override public void onNext(ComposeEvent value) { events.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        outbound.onNext(ComposeCommand.newBuilder()
                .setUp(ComposeUpRequest.newBuilder().setProjectName(project).setComposeYaml(composeYaml).build())
                .build());
        assertTrue(awaitLogContaining(events, "drop-test-started", 60),
                "the real container must actually have started before we drop the stream");
        assertTrue(realContainerExistsForProject(project), "sanity check: the container must be real, on Docker");

        // Simulate a dropped client — Ctrl-C, laptop sleep, a network blip — WITHOUT ever sending `down`.
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 30_000;
        boolean cleanedUp = false;
        while (System.currentTimeMillis() < deadline) {
            if (!realContainerExistsForProject(project)) {
                cleanedUp = true;
                break;
            }
            Thread.sleep(500);
        }
        assertTrue(cleanedUp, "the orphaned compose project must actually be torn down after the stream drops, "
                + "not left running forever");
    }

    private static boolean realContainerExistsForProject(String project) throws Exception {
        Process ps = new ProcessBuilder("docker", "ps", "-q",
                "--filter", "label=com.docker.compose.project=" + project)
                .redirectErrorStream(true).start();
        String output;
        try (var reader = ps.inputReader()) {
            output = reader.lines().reduce("", (a, b) -> a + b);
        }
        ps.waitFor(10, TimeUnit.SECONDS);
        return !output.isBlank();
    }

    private static boolean awaitLogContaining(LinkedBlockingQueue<ComposeEvent> events, String needle,
                                              int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ComposeEvent event = events.poll(1, TimeUnit.SECONDS);
            if (event != null && event.hasLog() && event.getLog().getLine().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static ComposeEvent awaitFinished(LinkedBlockingQueue<ComposeEvent> events, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ComposeEvent event = events.poll(1, TimeUnit.SECONDS);
            if (event != null && event.hasFinished()) {
                return event;
            }
        }
        return null;
    }
}
