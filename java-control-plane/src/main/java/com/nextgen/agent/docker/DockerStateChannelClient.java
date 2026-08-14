package com.nextgen.agent.docker;

import com.nextgen.agent.BackoffPolicy;
import com.nextgen.agent.ControlPlaneConnection;
import com.nextgen.controlplane.EnvConfig;
import com.nextgen.proto.ControlPlaneProto.DockerChannelHello;
import com.nextgen.proto.ControlPlaneProto.DockerControlCommand;
import com.nextgen.proto.ControlPlaneProto.DockerControlResult;
import com.nextgen.proto.ControlPlaneProto.NodeDockerEvent;
import com.nextgen.proto.ControlPlaneProto.ServerDockerCommand;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage T: opens and keeps open the one long-lived {@code DockerStateChannel} stream this node uses to
 * report its real Docker inventory and receive container control commands — on the SAME {@link
 * ControlPlaneConnection} {@link com.nextgen.agent.NodeAgent} already uses for registration/heartbeats/
 * {@link com.nextgen.agent.task.TaskChannelClient}, never a separate connection or inbound port.
 * Structurally a near-exact mirror of {@code TaskChannelClient} (hello handshake, leader-redirect-aware
 * reconnect via the same {@link BackoffPolicy} shape, "read {@link #outbound} fresh at send time, never
 * capture it" discipline) — this is a genuinely separate concern from task dispatch, not a reuse of that
 * class, since Docker inventory exists independently of whether this node currently has any task
 * running at all.
 *
 * <p>Only ever constructed/started on a node {@link com.nextgen.agent.DockerCapabilityDetector} has
 * confirmed has a reachable Docker daemon — a non-Docker node never opens this channel.
 */
public final class DockerStateChannelClient {
    private static final Logger LOG = LoggerFactory.getLogger(DockerStateChannelClient.class);

    private final ControlPlaneConnection connection;
    private final String nodeId;
    private final DockerStateCollector collector;
    private final BackoffPolicy backoff;
    private final long reportIntervalMillis;
    private final ScheduledExecutorService reportScheduler;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private volatile boolean shutdown = false;
    private volatile StreamObserver<NodeDockerEvent> outbound;

    /** Serializes every write to {@link #outbound} — same reasoning as {@code TaskChannelClient}'s own
     * {@code writeLock}: a report tick and a control-result send can race from different threads
     * (scheduler thread vs. gRPC callback thread), and gRPC's {@code StreamObserver} contract forbids
     * concurrent unsynchronized {@code onNext} calls. */
    private final Object writeLock = new Object();

    public DockerStateChannelClient(ControlPlaneConnection connection, String nodeId,
            DockerStateCollector collector, BackoffPolicy backoff) {
        this.connection = connection;
        this.nodeId = nodeId;
        this.collector = collector;
        this.backoff = backoff;
        this.reportIntervalMillis = EnvConfig.longValue("DOCKER_STATE_REPORT_INTERVAL_MS", 5_000L);
        this.reportScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docker-state-reporter");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        connect();
        reportScheduler.scheduleAtFixedRate(this::reportNow, reportIntervalMillis, reportIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        shutdown = true;
        reportScheduler.shutdownNow();
        StreamObserver<NodeDockerEvent> current = outbound;
        if (current != null) {
            try {
                current.onCompleted();
            } catch (RuntimeException ignored) {
                // Best-effort close — the connection may already be gone.
            }
        }
    }

    private void connect() {
        if (shutdown) {
            return;
        }
        StreamObserver<ServerDockerCommand> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServerDockerCommand command) {
                reconnectAttempt.set(0);
                handleCommand(command);
            }

            @Override
            public void onError(Throwable t) {
                String hint = ControlPlaneConnection.leaderHint(t);
                if (hint != null) {
                    LOG.info("↪ Docker-state channel redirected to leader hint '{}'; reconnecting", hint);
                    connection.onLeaderHint(hint);
                    reconnectAttempt.set(0);
                    reconnectImmediately();
                    return;
                }
                LOG.warn("⚠ Docker-state channel error: {}; reconnecting", t.getMessage());
                connection.onFailure();
                scheduleReconnect();
            }

            @Override
            public void onCompleted() {
                LOG.warn("⚠ Docker-state channel closed by the control plane; reconnecting");
                scheduleReconnect();
            }
        };

        StreamObserver<NodeDockerEvent> newOutbound = connection.asyncStub().dockerStateChannel(responseObserver);
        outbound = newOutbound;
        synchronized (writeLock) {
            newOutbound.onNext(NodeDockerEvent.newBuilder()
                    .setHello(DockerChannelHello.newBuilder().setNodeId(nodeId).build())
                    .build());
        }
        LOG.info("🐳 Docker-state channel opened for node '{}'", nodeId);
        // A fresh connection has nothing to report yet on the server side — send an immediate snapshot
        // rather than waiting up to reportIntervalMillis for the first scheduled tick.
        reportNow();
    }

    private void scheduleReconnect() {
        if (shutdown) {
            return;
        }
        long delay = backoff.delayForAttempt(reconnectAttempt.incrementAndGet());
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            connect();
        }, "docker-state-channel-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void reconnectImmediately() {
        if (shutdown) {
            return;
        }
        Thread reconnectThread = new Thread(this::connect, "docker-state-channel-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void handleCommand(ServerDockerCommand command) {
        if (command.getCommandCase() != ServerDockerCommand.CommandCase.CONTROL) {
            return; // COMMAND_NOT_SET
        }
        DockerControlCommand control = command.getControl();
        DockerStateCollector.ControlResult result = switch (control.getAction()) {
            case DOCKER_CONTROL_ACTION_START -> collector.start(control.getContainerId());
            case DOCKER_CONTROL_ACTION_STOP -> collector.stop(control.getContainerId());
            case DOCKER_CONTROL_ACTION_RESTART -> collector.restart(control.getContainerId());
            case DOCKER_CONTROL_ACTION_REMOVE -> collector.remove(control.getContainerId());
            default -> new DockerStateCollector.ControlResult(false, "Unrecognised action");
        };
        sendResult(control.getCommandId(), result);
        // The control action just changed real container state — report a fresh snapshot immediately
        // rather than waiting up to reportIntervalMillis for the UI/CLI to see the effect.
        reportNow();
    }

    private void reportNow() {
        StreamObserver<NodeDockerEvent> channel = outbound;
        if (channel == null) {
            return; // disconnected; the next successful connect() sends an immediate snapshot anyway
        }
        try {
            NodeDockerEvent event = NodeDockerEvent.newBuilder()
                    .setReport(collector.collect())
                    .build();
            synchronized (writeLock) {
                channel.onNext(event);
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not send Docker state report: {}", e.getMessage());
        }
    }

    private void sendResult(String commandId, DockerStateCollector.ControlResult result) {
        StreamObserver<NodeDockerEvent> channel = outbound;
        if (channel == null) {
            LOG.warn("⚠ Could not report Docker control result for command {}: no open channel", commandId);
            return;
        }
        try {
            NodeDockerEvent event = NodeDockerEvent.newBuilder()
                    .setResult(DockerControlResult.newBuilder()
                            .setCommandId(commandId)
                            .setOk(result.ok())
                            .setMessage(result.message())
                            .build())
                    .build();
            synchronized (writeLock) {
                channel.onNext(event);
            }
        } catch (RuntimeException e) {
            LOG.warn("⚠ Could not report Docker control result for command {}: {}", commandId, e.getMessage());
        }
    }
}
