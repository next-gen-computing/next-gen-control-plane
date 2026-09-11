package com.nextgen.controlplane.task;

import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the live, server-side outbound half of each connected node's {@code TaskChannel} stream.
 *
 * <p>This is the entire mechanism that lets the server reach a node without the node ever listening
 * on a socket: a node opens {@code TaskChannel} (a client-initiated bidirectional stream) once after
 * registering and keeps it open. The server-side handler for that RPC registers its
 * {@code StreamObserver<ServerTaskCommand>} here under the node's id; anything the server later wants
 * to push to that node ({@link ControlPlaneProto.TaskDispatch}, {@link ControlPlaneProto.TaskCancel})
 * goes out through the observer looked up here, at whatever time the server decides — not in response
 * to an inbound request from the node.
 */
public final class NodeTaskChannelRegistry {

    private final ConcurrentHashMap<String, StreamObserver<ControlPlaneProto.ServerTaskCommand>> channels =
            new ConcurrentHashMap<>();

    public void register(String nodeId, StreamObserver<ControlPlaneProto.ServerTaskCommand> outbound) {
        channels.put(nodeId, outbound);
    }

    /** Removes the channel, but only if {@code outbound} is still the currently-registered one for this
     * node — guards against a slow-closing old stream unregistering a newer reconnect's channel. */
    public void unregister(String nodeId, StreamObserver<ControlPlaneProto.ServerTaskCommand> outbound) {
        channels.remove(nodeId, outbound);
    }

    public Optional<StreamObserver<ControlPlaneProto.ServerTaskCommand>> get(String nodeId) {
        return Optional.ofNullable(channels.get(nodeId));
    }

    public boolean isConnected(String nodeId) {
        return channels.containsKey(nodeId);
    }

    public List<String> connectedNodeIds() {
        return List.copyOf(channels.keySet());
    }
}
