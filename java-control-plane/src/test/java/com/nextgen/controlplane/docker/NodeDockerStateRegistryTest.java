package com.nextgen.controlplane.docker;

import com.nextgen.proto.ControlPlaneProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeDockerStateRegistryTest {

    @Test
    void snapshotIsEmptyWhenNothingHasEverReported() {
        NodeDockerStateRegistry registry = new NodeDockerStateRegistry();
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void updateMakesTheReportVisibleInTheSnapshot() {
        NodeDockerStateRegistry registry = new NodeDockerStateRegistry();
        ControlPlaneProto.DockerStateReport report = ControlPlaneProto.DockerStateReport.newBuilder()
                .addContainers(ControlPlaneProto.DockerContainerInfo.newBuilder().setName("web-1"))
                .build();

        registry.update("node1", report);

        var snapshot = registry.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals("node1", snapshot.get(0).getNodeId());
        assertEquals("web-1", snapshot.get(0).getReport().getContainers(0).getName());
    }

    @Test
    void aLaterUpdateReplacesTheEarlierOneRatherThanAccumulating() {
        NodeDockerStateRegistry registry = new NodeDockerStateRegistry();
        registry.update("node1", ControlPlaneProto.DockerStateReport.newBuilder()
                .addContainers(ControlPlaneProto.DockerContainerInfo.newBuilder().setName("old")).build());
        registry.update("node1", ControlPlaneProto.DockerStateReport.newBuilder()
                .addContainers(ControlPlaneProto.DockerContainerInfo.newBuilder().setName("new")).build());

        var snapshot = registry.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals(1, snapshot.get(0).getReport().getContainersList().size());
        assertEquals("new", snapshot.get(0).getReport().getContainers(0).getName());
    }

    @Test
    void removeTakesTheNodeOutOfTheSnapshotEntirely() {
        NodeDockerStateRegistry registry = new NodeDockerStateRegistry();
        registry.update("node1", ControlPlaneProto.DockerStateReport.getDefaultInstance());

        registry.remove("node1");

        assertTrue(registry.snapshot().isEmpty(),
                "a removed node must be absent, never present with a stale/empty report");
    }
}
