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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   <li>{@link #attachStream} — called once a provider node actually opens {@code TunnelPort} and sends
 *       its hello frame. Starts accepting real consumer connections on the already-bound socket.</li>
 * </ol>
 *
 * <p><b>Stage OO — load-balanced multiple backends.</b> A service declaring {@code replicas > 1} means
 * more than one node independently opens a {@code TunnelPort} stream for the SAME (project, service)
 * key. {@link #attachStream} appends rather than overwrites, so every replica's stream becomes an
 * additional backend; each newly-accepted consumer connection is routed to one backend via round-robin,
 * chosen once at accept time and remembered for that tunnel's whole lifetime (never re-routed mid-
 * connection). A backend's disconnect ({@link #detachStream}) removes just that one backend and closes
 * only the tunnels it owned — no connection draining, an explicit, named simplification consistent with
 * this class's existing relay-hop-cost tradeoffs. The listener itself, and the (project, service) entry,
 * are only torn down once the LAST backend detaches.
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

    /** Registers {@code nodeStream} as ONE backend for this (project, service) — the first attach for a
     * key starts real TCP accepting; every attach after that just adds another load-balanced backend to
     * the same already-running listener (Stage OO).
     *
     * @return true if a reservation existed and this stream is now a registered backend; false if no
     *         port was ever reserved for this (project, service) — the caller must fail the stream
     *         honestly rather than silently accept a stream with nowhere to route traffic. */
    public boolean attachStream(String projectName, String serviceName, StreamObserver<TunnelFrame> nodeStream) {
        RelayEntry entry = entries.get(key(projectName, serviceName));
        if (entry == null) {
            return false;
        }
        entry.attach(nodeStream);
        return true;
    }

    /** Removes just ONE backend from a (project, service)'s pool — called when a single replica's
     * {@code TunnelPort} stream disconnects (see {@code ControlPlaneServiceImpl.tunnelPort}'s
     * {@code onError}/{@code onCompleted}). The listener and any remaining backends stay up; only once
     * the LAST backend detaches does the whole entry (listener + every remaining tunnel) actually close
     * — see {@link RelayEntry#detach}. A no-op if no entry exists for this key at all, or if this
     * particular stream was never a registered backend (both benign races on shutdown). */
    public void detachStream(String projectName, String serviceName, StreamObserver<TunnelFrame> nodeStream) {
        String key = key(projectName, serviceName);
        RelayEntry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        if (entry.detach(nodeStream)) {
            entries.remove(key, entry);
        }
    }

    public void onFrameFromNode(String projectName, String serviceName, TunnelFrame frame) {
        RelayEntry entry = entries.get(key(projectName, serviceName));
        if (entry != null) {
            entry.onFrameFromNode(frame);
        }
    }

    /** Closes the listener and every open tunnel for this (project, service), regardless of how many
     * backends are currently attached — the full, unconditional teardown (as opposed to
     * {@link #detachStream}'s one-backend-at-a-time removal), used once the backing service task(s) go
     * terminal or by a TTL sweep for a port that never got a first attach at all, so a finished/never-
     * used service's port doesn't stay claimed forever. */
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
        private final Map<String, StreamObserver<TunnelFrame>> tunnelToBackend = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<StreamObserver<TunnelFrame>> backends = new CopyOnWriteArrayList<>();
        private final AtomicInteger cursor = new AtomicInteger(0);
        private volatile boolean closed = false;

        RelayEntry(String key, ServerSocket serverSocket) {
            this.key = key;
            this.serverSocket = serverSocket;
        }

        void attach(StreamObserver<TunnelFrame> nodeStream) {
            boolean first = backends.isEmpty();
            backends.add(nodeStream);
            if (first) {
                Thread acceptThread = new Thread(this::acceptLoop, "relay-accept-" + key);
                acceptThread.setDaemon(true);
                acceptThread.start();
            }
        }

        /** @return true if removing this backend left NO backends at all — the entry has already been
         * fully closed (listener + every remaining tunnel) as a side effect, and the caller must remove
         * it from the outer registry. */
        boolean detach(StreamObserver<TunnelFrame> nodeStream) {
            boolean removed = backends.remove(nodeStream);
            if (!removed) {
                return false;
            }
            for (Map.Entry<String, StreamObserver<TunnelFrame>> tunnel : tunnelToBackend.entrySet()) {
                if (tunnel.getValue() == nodeStream) {
                    closeTunnel(tunnel.getKey(), false);
                }
            }
            if (backends.isEmpty()) {
                close();
                return true;
            }
            return false;
        }

        private StreamObserver<TunnelFrame> nextBackend() {
            int size = backends.size();
            if (size == 0) {
                return null;
            }
            int index = Math.floorMod(cursor.getAndIncrement(), size);
            try {
                return backends.get(index);
            } catch (IndexOutOfBoundsException e) {
                // A backend detached concurrently between the size check and this get — benign under
                // CopyOnWriteArrayList's snapshot semantics; just skip this accept rather than route to
                // a stale index.
                return null;
            }
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
                StreamObserver<TunnelFrame> backend = nextBackend();
                if (backend == null) {
                    // Every backend disconnected in the gap between accept() unblocking and this line —
                    // nothing to route to; drop the connection rather than leaking it.
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Already dropping this connection.
                    }
                    continue;
                }
                String tunnelId = UUID.randomUUID().toString();
                socketsByTunnelId.put(tunnelId, socket);
                tunnelToBackend.put(tunnelId, backend);
                Thread forwardThread = new Thread(() -> forwardSocketToNode(tunnelId, socket, backend),
                        "relay-forward-" + tunnelId);
                forwardThread.setDaemon(true);
                forwardThread.start();
            }
        }

        private void forwardSocketToNode(String tunnelId, Socket socket, StreamObserver<TunnelFrame> backend) {
            try (InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    backend.onNext(TunnelFrame.newBuilder()
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
            StreamObserver<TunnelFrame> backend = tunnelToBackend.remove(tunnelId);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Already tearing this tunnel down — nothing more useful to do.
                }
            }
            if (notifyNode && backend != null) {
                try {
                    backend.onNext(TunnelFrame.newBuilder().setTunnelId(tunnelId).setClosed(true).build());
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
            backends.clear();
        }
    }
}
