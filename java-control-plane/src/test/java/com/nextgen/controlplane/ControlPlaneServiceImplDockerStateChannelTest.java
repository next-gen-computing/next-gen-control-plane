package com.nextgen.controlplane;

import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneProto.DockerChannelHello;
import com.nextgen.proto.ControlPlaneProto.DockerContainerInfo;
import com.nextgen.proto.ControlPlaneProto.DockerControlAction;
import com.nextgen.proto.ControlPlaneProto.DockerControlCommand;
import com.nextgen.proto.ControlPlaneProto.DockerControlResult;
import com.nextgen.proto.ControlPlaneProto.DockerResourcesSnapshot;
import com.nextgen.proto.ControlPlaneProto.DockerStateReport;
import com.nextgen.proto.ControlPlaneProto.NodeDockerEvent;
import com.nextgen.proto.ControlPlaneProto.ServerDockerCommand;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage T: proves the real report → registry → GetDockerResources path, and the real
 * ControlDockerContainer → push-down-the-channel → await-result path, over an actual (in-process) gRPC
 * DockerStateChannel stream — mirrors {@link ControlPlaneServiceImplTaskChannelTest}'s established
 * style exactly, using a fake node that plays both sides.
 */
class ControlPlaneServiceImplDockerStateChannelTest {

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub;
    private ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        NodeRegistry nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);
        ControlPlaneServiceImpl service = new ControlPlaneServiceImpl(nodeRegistry, new RoundRobinScheduler(),
                new TaskRegistry(), new NodeTaskChannelRegistry());

        server = InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        blockingStub = ControlPlaneServiceGrpc.newBlockingStub(channel);
        asyncStub = ControlPlaneServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private StreamObserver<NodeDockerEvent> lastOpenedOutbound;

    /** Opens a fake node's DockerStateChannel and returns the queue of commands the server pushes. */
    private LinkedBlockingQueue<ServerDockerCommand> openDockerStateChannel(String nodeId) {
        LinkedBlockingQueue<ServerDockerCommand> incoming = new LinkedBlockingQueue<>();
        StreamObserver<NodeDockerEvent> outbound = asyncStub.dockerStateChannel(new StreamObserver<>() {
            @Override public void onNext(ServerDockerCommand value) { incoming.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        outbound.onNext(NodeDockerEvent.newBuilder()
                .setHello(DockerChannelHello.newBuilder().setNodeId(nodeId).build())
                .build());
        this.lastOpenedOutbound = outbound;
        return incoming;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aRealReportedContainerAppearsInGetDockerResources() {
        openDockerStateChannel("node1");

        DockerStateReport report = DockerStateReport.newBuilder()
                .addContainers(DockerContainerInfo.newBuilder()
                        .setContainerId("abc123").setName("web-1").setImage("nginx:latest")
                        .setStatus("running").setStateText("Up 5 minutes"))
                .setReportedAtEpochMillis(System.currentTimeMillis())
                .build();
        lastOpenedOutbound.onNext(NodeDockerEvent.newBuilder().setReport(report).build());

        DockerResourcesSnapshot snapshot = awaitNonEmptySnapshot();
        assertEquals(1, snapshot.getNodesList().size());
        assertEquals("node1", snapshot.getNodes(0).getNodeId());
        assertEquals(1, snapshot.getNodes(0).getReport().getContainersList().size());
        assertEquals("web-1", snapshot.getNodes(0).getReport().getContainers(0).getName());
    }

    @Test
    void aNodeThatDisconnectsIsRemovedFromTheSnapshotNotLeftStale() {
        openDockerStateChannel("node1");
        lastOpenedOutbound.onNext(NodeDockerEvent.newBuilder()
                .setReport(DockerStateReport.newBuilder()
                        .addContainers(DockerContainerInfo.newBuilder().setContainerId("abc").setName("web-1"))
                        .build())
                .build());
        awaitNonEmptySnapshot();

        lastOpenedOutbound.onCompleted();
        sleep(200);

        DockerResourcesSnapshot snapshot = blockingStub.getDockerResources(ControlPlaneProto.Empty.getDefaultInstance());
        assertTrue(snapshot.getNodesList().isEmpty(),
                "a disconnected node's last-known inventory must not be presented as current");
    }

    @Test
    void controlDockerContainerPushesTheCommandDownTheRealChannelAndReturnsTheNodesRealResult() throws InterruptedException {
        LinkedBlockingQueue<ServerDockerCommand> node1Commands = openDockerStateChannel("node1");

        Thread caller = new Thread(() -> {
            DockerControlResult result = blockingStub.controlDockerContainer(DockerControlCommand.newBuilder()
                    .setNodeId("node1")
                    .setContainerId("abc123")
                    .setAction(DockerControlAction.DOCKER_CONTROL_ACTION_STOP)
                    .build());
            this.callResult = result;
        });
        caller.start();

        ServerDockerCommand pushed = node1Commands.poll(5, TimeUnit.SECONDS);
        assertNotNull(pushed, "the server must actually push the control command down the real stream");
        assertTrue(pushed.hasControl());
        assertEquals("abc123", pushed.getControl().getContainerId());
        assertEquals(DockerControlAction.DOCKER_CONTROL_ACTION_STOP, pushed.getControl().getAction());

        // The fake node "stops the real container" and reports back over the real stream.
        lastOpenedOutbound.onNext(NodeDockerEvent.newBuilder()
                .setResult(DockerControlResult.newBuilder()
                        .setCommandId(pushed.getControl().getCommandId())
                        .setOk(true)
                        .setMessage("stopped"))
                .build());

        caller.join(5_000);
        assertNotNull(callResult, "controlDockerContainer must have returned by now");
        assertTrue(callResult.getOk());
        assertEquals("stopped", callResult.getMessage());
    }

    private volatile DockerControlResult callResult;

    @Test
    void controlDockerContainerAgainstANodeWithNoOpenChannelFailsHonestly() {
        DockerControlResult result = blockingStub.controlDockerContainer(DockerControlCommand.newBuilder()
                .setNodeId("ghost-node")
                .setContainerId("abc123")
                .setAction(DockerControlAction.DOCKER_CONTROL_ACTION_START)
                .build());
        assertFalse(result.getOk());
        assertTrue(result.getMessage().contains("no open Docker-state channel"), result.getMessage());
    }

    private DockerResourcesSnapshot awaitNonEmptySnapshot() {
        long deadline = System.currentTimeMillis() + 5_000;
        DockerResourcesSnapshot last = DockerResourcesSnapshot.getDefaultInstance();
        while (System.currentTimeMillis() < deadline) {
            last = blockingStub.getDockerResources(ControlPlaneProto.Empty.getDefaultInstance());
            if (!last.getNodesList().isEmpty()) {
                return last;
            }
            sleep(20);
        }
        fail("GetDockerResources never reflected the reported state");
        return last;
    }
}
