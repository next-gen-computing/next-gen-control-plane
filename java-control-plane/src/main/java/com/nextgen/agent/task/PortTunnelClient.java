package com.nextgen.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.nextgen.agent.ControlPlaneConnection;
import com.nextgen.proto.ControlPlaneProto.TunnelFrame;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node side of Stage O's cross-node service networking relay — opens ONE {@code TunnelPort} stream for
 * one of this node's own service's ports that a peer service on another node may need to reach, and
 * forwards real TCP bytes between a local socket (the already-running container's published port) and
 * the server's {@code PortRelayManager}, multiplexed by {@code tunnel_id} over this one stream — see
 * {@code TunnelFrame}'s own proto Javadoc for the full hello/multiplexing protocol.
 *
 * <p>One instance backs exactly one (project, service, local port) triple for its whole lifetime —
 * created and closed alongside the service task it belongs to (see {@code DockerComposeServiceExecutor}).
 * Deliberately does NOT reconnect on a dropped stream, unlike {@link TaskChannelClient}'s persistent
 * reconnect: a lost relay connection means cross-node reachability for this one service pauses until
 * its task is retried/redispatched — a real, bounded degradation, never silent data loss, and a
 * deliberately smaller scope than the always-on TaskChannel.
 */
public final class PortTunnelClient {
    private static final Logger LOG = LoggerFactory.getLogger(PortTunnelClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ControlPlaneConnection connection;
    private final String projectName;
    private final String serviceName;
    private final int localPort;
    private final Map<String, Socket> socketsByTunnelId = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile StreamObserver<TunnelFrame> outbound;
    private volatile boolean closed = false;

    public PortTunnelClient(ControlPlaneConnection connection, String projectName, String serviceName,
                            int localPort) {
        this.connection = connection;
        this.projectName = projectName;
        this.serviceName = serviceName;
        this.localPort = localPort;
    }

    /** Opens the stream and sends the hello frame. Non-blocking — safe to call from the same thread
     * that's about to block inside {@code DockerComposeRunner.run}. */
    public void start() {
        StreamObserver<TunnelFrame> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(TunnelFrame frame) {
                handleFrame(frame);
            }

            @Override
            public void onError(Throwable t) {
                LOG.warn("⚠ Tunnel for '{}/{}' (localhost:{}) closed with error: {}",
                        projectName, serviceName, localPort, t.getMessage());
                closeAllTunnels();
            }

            @Override
            public void onCompleted() {
                LOG.info("🔀 Tunnel for '{}/{}' (localhost:{}) closed by control plane",
                        projectName, serviceName, localPort);
                closeAllTunnels();
            }
        };

        outbound = connection.asyncStub().tunnelPort(responseObserver);
        String helloJson = MAPPER.createObjectNode()
                .put("project_name", projectName)
                .put("service_name", serviceName)
                .toString();
        synchronized (writeLock) {
            outbound.onNext(TunnelFrame.newBuilder().setData(ByteString.copyFromUtf8(helloJson)).build());
        }
        LOG.info("🔀 Tunnel opened for '{}/{}' → localhost:{}", projectName, serviceName, localPort);
    }

    private void handleFrame(TunnelFrame frame) {
        String tunnelId = frame.getTunnelId();
        if (frame.getClosed()) {
            closeTunnel(tunnelId, false);
            return;
        }
        Socket socket = socketsByTunnelId.get(tunnelId);
        if (socket == null) {
            try {
                socket = new Socket("localhost", localPort);
            } catch (IOException e) {
                LOG.warn("⚠ Could not reach local relay target localhost:{} for tunnel {}: {}",
                        localPort, tunnelId, e.getMessage());
                sendClose(tunnelId);
                return;
            }
            socketsByTunnelId.put(tunnelId, socket);
            startForwardingSocketToServer(tunnelId, socket);
        }
        try {
            socket.getOutputStream().write(frame.getData().toByteArray());
        } catch (IOException e) {
            closeTunnel(tunnelId, true);
        }
    }

    private void startForwardingSocketToServer(String tunnelId, Socket socket) {
        Thread t = new Thread(() -> {
            try (InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    StreamObserver<TunnelFrame> channel = outbound;
                    if (channel == null) {
                        return;
                    }
                    synchronized (writeLock) {
                        channel.onNext(TunnelFrame.newBuilder()
                                .setTunnelId(tunnelId)
                                .setData(ByteString.copyFrom(buffer, 0, read))
                                .build());
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // The socket closed or the outbound stream failed — closeTunnel below tears down
                // whichever side is still standing either way.
            } finally {
                closeTunnel(tunnelId, true);
            }
        }, "tunnel-forward-" + tunnelId);
        t.setDaemon(true);
        t.start();
    }

    private void sendClose(String tunnelId) {
        StreamObserver<TunnelFrame> channel = outbound;
        if (channel == null) {
            return;
        }
        try {
            synchronized (writeLock) {
                channel.onNext(TunnelFrame.newBuilder().setTunnelId(tunnelId).setClosed(true).build());
            }
        } catch (RuntimeException ignored) {
            // Best-effort — the stream may already be gone.
        }
    }

    private void closeTunnel(String tunnelId, boolean notifyServer) {
        Socket socket = socketsByTunnelId.remove(tunnelId);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already tearing this tunnel down — nothing more useful to do.
            }
        }
        if (notifyServer) {
            sendClose(tunnelId);
        }
    }

    private void closeAllTunnels() {
        for (String tunnelId : List.copyOf(socketsByTunnelId.keySet())) {
            closeTunnel(tunnelId, false);
        }
    }

    /** Closes every open local tunnel socket and the stream itself — called when the service task this
     * tunnel backs ends, whether normally or via cancellation. Safe to call more than once. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAllTunnels();
        StreamObserver<TunnelFrame> channel = outbound;
        if (channel != null) {
            try {
                channel.onCompleted();
            } catch (RuntimeException ignored) {
                // Best-effort close — the connection may already be gone.
            }
        }
    }
}
