package com.nextgen.controlplane.docker;

import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the live, server-side outbound half of each connected node's {@code DockerStateChannel}
 * stream — the Stage T sibling of {@link com.nextgen.controlplane.task.NodeTaskChannelRegistry}, kept
 * genuinely separate rather than reused, since Docker-state reporting is a different concern from task
 * dispatch and a node can have one channel open without the other.
 *
 * <p>This is the entire mechanism that lets the server push a {@link
 * ControlPlaneProto.DockerControlCommand} to a node without the node ever listening on a socket: the
 * server-side handler for {@code DockerStateChannel} registers its {@code
 * StreamObserver<ServerDockerCommand>} here under the node's id; {@code controlDockerContainer} looks
 * it up here to deliver a start/stop/restart/rm request.
 */
public final class NodeDockerCommandRegistry {

    private final ConcurrentHashMap<String, StreamObserver<ControlPlaneProto.ServerDockerCommand>> channels =
            new ConcurrentHashMap<>();

    public void register(String nodeId, StreamObserver<ControlPlaneProto.ServerDockerCommand> outbound) {
        channels.put(nodeId, outbound);
    }

    /** Removes the channel, but only if {@code outbound} is still the currently-registered one for this
     * node — guards against a slow-closing old stream unregistering a newer reconnect's channel. */
    public void unregister(String nodeId, StreamObserver<ControlPlaneProto.ServerDockerCommand> outbound) {
        channels.remove(nodeId, outbound);
    }

    public Optional<StreamObserver<ControlPlaneProto.ServerDockerCommand>> get(String nodeId) {
        return Optional.ofNullable(channels.get(nodeId));
    }
}
