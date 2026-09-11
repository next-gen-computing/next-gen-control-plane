package com.nextgen.controlplane.docker;

import com.nextgen.proto.ControlPlaneProto;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each Docker-capable node's latest reported {@link ControlPlaneProto.DockerStateReport} — real
 * {@code docker ps -a}/{@code images}/{@code volume ls}/{@code network ls} output, reported fresh on
 * every {@code DockerStateChannel} tick (see {@code DockerStateCollector}'s own Javadoc: never a diff).
 *
 * <p>A node is removed entirely on disconnect ({@link #remove}), not left showing its last-known state
 * — presenting a disconnected node's stale inventory as current would violate this project's own
 * "unavailable means unavailable, never a guessed value" rule (see {@code NodeDto}/{@code
 * ClusterSummaryDto}'s identical discipline). {@link #snapshot()} is what {@code GetDockerResources}
 * reads; a node that has never reported, or isn't Docker-capable, is simply absent from it.
 */
public final class NodeDockerStateRegistry {

    private final ConcurrentHashMap<String, ControlPlaneProto.DockerStateReport> reportsByNodeId =
            new ConcurrentHashMap<>();

    public void update(String nodeId, ControlPlaneProto.DockerStateReport report) {
        reportsByNodeId.put(nodeId, report);
    }

    public void remove(String nodeId) {
        reportsByNodeId.remove(nodeId);
    }

    public List<ControlPlaneProto.NodeDockerState> snapshot() {
        List<ControlPlaneProto.NodeDockerState> result = new ArrayList<>();
        reportsByNodeId.forEach((nodeId, report) -> result.add(ControlPlaneProto.NodeDockerState.newBuilder()
                .setNodeId(nodeId)
                .setReport(report)
                .build()));
        return result;
    }
}
