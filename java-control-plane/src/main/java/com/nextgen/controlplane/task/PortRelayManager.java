package com.nextgen.controlplane.task;

import com.google.protobuf.ByteString;
import com.nextgen.proto.ControlPlaneProto.TunnelFrame;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of Stage O's cross-node service networking relay — see {@code TunnelFrame}'s own proto
 * Javadoc for the full picture. Two-phase, to resolve an ordering problem: a consumer service needs
 * its peer's relay port injected into its environment BEFORE either service is even dispatched (see
 * {@code JobCoordinator}, Stage P), but the provider's real {@code TunnelPort} stream only exists once
 * ITS container has actually started — well after dispatch. So:
 *
 * <ol>
 *   <li>{@link #reservePort} — called synchronously at job-split time, binds a real {@link ServerSocket}
 *       immediately (claiming the port for real, not just picking a number that could race with another
 *       reservation) and returns it. No relaying happens yet — nothing is listening for the node's
 *       stream.</li>
 *   <li>{@link #attachStream} — called once the provider's node actually opens {@code TunnelPort} and
 *       sends its hello frame. Starts accepting real consumer connections on the already-bound socket.</li>
 * </ol>
 *
 * <p>Each accepted TCP connection gets its own {@code tunnel_id} and its own pair of forwarding
 * threads — one node-side stream serves arbitrarily many concurrent consumers, multiplexed.
 */
public final class PortRelayManager {
    private static final Logger LOG = LoggerFactory.getLogger(PortRelayManager.class);

    private final int rangeStart;
    private final int rangeEnd;
    private final Map<String, RelayEntry> entries = new ConcurrentHashMap<>();

    public PortRelayManager(int rangeStart, int rangeEnd) {
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
    }

    public static String key(String projectName, String serviceName) {
        return projectName + "/" + serviceName;
    }

    /** Binds and claims a real port immediately — see the class Javadoc for why this can't just hand
     * back a number and bind later. */
    public int reservePort(String projectName, String serviceName) throws IOException {
        String key = key(projectName, serviceName);
        IOException lastFailure = null;
        for (int port = rangeStart; port <= rangeEnd; port++) {
            try {
                ServerSocket socket = new ServerSocket(port);
                RelayEntry entry = new RelayEntry(key, socket);
                entries.put(key, entry);
                LOG.info("🔀 Reserved relay port {} for '{}'", port, key);
                return port;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new IOException("no free relay port in [" + rangeStart + ", " + rangeEnd + "]", lastFailure);
    }

    /** @return true if a reservation existed and accepting has started; false if no port was ever
     * reserved for this (project, service) — the caller must fail the stream honestly rather than
     * silently accept a stream with nowhere to route traffic. */
    public boolean attachStream(String projectName, String serviceName, StreamObserver<TunnelFrame> nodeStream) {
        RelayEntry entry = entries.get(key(projectName, serviceName));
        if (entry == null) {
            return false;
        }
        entry.attach(nodeStream);
        return true;
    }

    public void onFrameFromNode(String projectName, String serviceName, TunnelFrame frame) {
        RelayEntry entry = entries.get(key(projectName, serviceName));
        if (entry != null) {
            entry.onFrameFromNode(frame);
        }
    }

    /** Closes the listener and every open tunnel for this (project, service) — called once the backing
     * service task goes terminal, so a finished service's port doesn't stay claimed forever. */
    public void release(String projectName, String serviceName) {
        RelayEntry entry = entries.remove(key(projectName, serviceName));
        if (entry != null) {
            entry.close();
        }
    }

    private static final class RelayEntry {
        private final String key;
        private final ServerSocket serverSocket;
        private final Map<String, Socket> socketsByTunnelId = new ConcurrentHashMap<>();
        private volatile StreamObserver<TunnelFrame> nodeStream;
        private volatile boolean closed = false;

        RelayEntry(String key, ServerSocket serverSocket) {
            this.key = key;
            this.serverSocket = serverSocket;
        }

        void attach(StreamObserver<TunnelFrame> nodeStream) {
            this.nodeStream = nodeStream;
            Thread acceptThread = new Thread(this::acceptLoop, "relay-accept-" + key);
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private void acceptLoop() {
            while (!closed) {
                Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    if (!closed) {
                        LOG.debug("Relay accept loop for '{}' ending: {}", key, e.getMessage());
                    }
                    return;
                }
                String tunnelId = UUID.randomUUID().toString();
                socketsByTunnelId.put(tunnelId, socket);
                Thread forwardThread = new Thread(() -> forwardSocketToNode(tunnelId, socket),
                        "relay-forward-" + tunnelId);
                forwardThread.setDaemon(true);
                forwardThread.start();
            }
        }

        private void forwardSocketToNode(String tunnelId, Socket socket) {
            try (InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    nodeStream.onNext(TunnelFrame.newBuilder()
                            .setTunnelId(tunnelId)
                            .setData(ByteString.copyFrom(buffer, 0, read))
                            .build());
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("Relay tunnel '{}' for '{}' ending: {}", tunnelId, key, e.getMessage());
            } finally {
                closeTunnel(tunnelId, true);
            }
        }

        void onFrameFromNode(TunnelFrame frame) {
            Socket socket = socketsByTunnelId.get(frame.getTunnelId());
            if (socket == null) {
                return; // unknown or already-closed tunnel — a benign race on close, not an error
            }
            if (frame.getClosed()) {
                closeTunnel(frame.getTunnelId(), false);
                return;
            }
            try {
                socket.getOutputStream().write(frame.getData().toByteArray());
            } catch (IOException e) {
                closeTunnel(frame.getTunnelId(), true);
            }
        }

        private void closeTunnel(String tunnelId, boolean notifyNode) {
            Socket socket = socketsByTunnelId.remove(tunnelId);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Already tearing this tunnel down — nothing more useful to do.
                }
            }
            if (notifyNode && nodeStream != null) {
                try {
                    nodeStream.onNext(TunnelFrame.newBuilder().setTunnelId(tunnelId).setClosed(true).build());
                } catch (RuntimeException ignored) {
                    // The node stream may already be gone — closing the local socket is what matters.
                }
            }
        }

        void close() {
            closed = true;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Best-effort — the listener is being torn down regardless.
            }
            for (String tunnelId : List.copyOf(socketsByTunnelId.keySet())) {
                closeTunnel(tunnelId, false);
            }
        }
    }
}
