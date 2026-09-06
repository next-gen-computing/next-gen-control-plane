package com.nextgen.agent.task;

import com.nextgen.agent.BackoffPolicy;
import com.nextgen.agent.ControlPlaneConnection;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.proto.ControlPlaneProto.NodeTaskEvent;
import com.nextgen.proto.ControlPlaneProto.ServerTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskChannelHello;
import com.nextgen.proto.ControlPlaneProto.TaskDispatch;
import com.nextgen.proto.ControlPlaneProto.TaskLogEvent;
import com.nextgen.proto.ControlPlaneProto.TaskProgressEvent;
import com.nextgen.proto.ControlPlaneProto.TaskResultEvent;
import com.nextgen.proto.ControlPlaneProto.TaskState;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opens and keeps open the one long-lived {@code TaskChannel} stream this node uses to receive real
 * work from the control plane — on the SAME {@link ControlPlaneConnection} {@link com.nextgen.agent.NodeAgent}
 * already uses for registration/heartbeats, never a separate connection, and never an inbound port on
 * this node (the server pushes {@code TaskDispatch} down the stream this node opened). Sharing that
 * connection means a leader discovered via a heartbeat's redirect is immediately what this stream
 * reconnects to as well, without needing to rediscover it independently.
 *
 * <p>Reconnects on stream failure using the same {@link BackoffPolicy} shape {@code NodeAgent} uses
 * for registration retries, so a control-plane restart or network blip is recovered from
 * automatically rather than leaving this node silently unable to receive work until a manual restart.
 * A leader-redirect trailer (as opposed to a genuine transport failure) reconnects immediately instead,
 * without consuming a backoff delay slot — see {@link #connect()}.
 *
 * <p>Task execution runs on a separate pool, never on the gRPC callback thread — a slow or CPU-heavy
 * {@link TaskExecutor} must not block this node from receiving/acking other stream traffic.
 */
public final class TaskChannelClient {
    private static final Logger LOG = LoggerFactory.getLogger(TaskChannelClient.class);

    private final ControlPlaneConnection connection;
    private final String nodeId;
    private final Map<TaskKindDomain, TaskExecutor> executors;
    private final BackoffPolicy backoff;
    private final ExecutorService taskExecutorPool;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final AtomicLong logSequence = new AtomicLong(0);

    /** Which executor is currently running which task, so a {@code TaskCancel} can be routed to the
     * right one — see {@link TaskExecutor#cancel}. Populated for the duration of one {@link #runDispatch}
     * call; a cancel for a task not present here (already finished, or never dispatched to this node)
     * is a normal, silently-ignored no-op, not an error. */
    private final Map<String, TaskExecutor> runningTasks = new ConcurrentHashMap<>();

    private volatile boolean shutdown = false;
    private volatile StreamObserver<NodeTaskEvent> outbound;

    /**
     * Serializes every write to {@link #outbound}. gRPC's {@code StreamObserver} contract requires
     * {@code onNext} to never be called concurrently from multiple threads unless the caller
     * synchronizes externally — and with a job's N sub-tasks each executing on their own
     * {@link #taskExecutorPool} thread but all reporting over this one node's single shared stream,
     * concurrent unsynchronized calls here WILL happen. Without this lock, two threads' writes
     * interleave on the wire and the server sees a corrupted, unparseable protobuf byte sequence —
     * silently stalling every sub-task dispatched to this node from that point on.
     */
    private final Object writeLock = new Object();

    private final NodeBuildContextStore buildContextStore;
    private final NodeSecretStore secretStore;

    public TaskChannelClient(ControlPlaneConnection connection, String nodeId,
                             Map<TaskKindDomain, TaskExecutor> executors, BackoffPolicy backoff) {
        this(connection, nodeId, executors, backoff, new NodeBuildContextStore());
    }

    /** @param buildContextStore Stage N: receives {@code BuildContextChunk} traffic — see its own
     * Javadoc. Injectable so a test can point it at a {@code @TempDir}; the no-arg constructor above
     * uses a real, working default under the system temp directory. */
    public TaskChannelClient(ControlPlaneConnection connection, String nodeId,
                             Map<TaskKindDomain, TaskExecutor> executors, BackoffPolicy backoff,
                             NodeBuildContextStore buildContextStore) {
        this(connection, nodeId, executors, backoff, buildContextStore, new NodeSecretStore());
    }

    /** @param secretStore Stage NN: receives {@code SecretMaterial} traffic — see its own Javadoc.
     * Injectable for tests, same discipline as {@code buildContextStore}. */
    public TaskChannelClient(ControlPlaneConnection connection, String nodeId,
                             Map<TaskKindDomain, TaskExecutor> executors, BackoffPolicy backoff,
                             NodeBuildContextStore buildContextStore, NodeSecretStore secretStore) {
        this.connection = connection;
        this.nodeId = nodeId;
        this.executors = executors;
        this.backoff = backoff;
        this.buildContextStore = buildContextStore;
        this.secretStore = secretStore;
        this.taskExecutorPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "task-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /** Exposed so {@code NodeAgent} can hand the SAME instance to the {@link DockerComposeServiceExecutor}
     * it wires up — this class only ever writes into the store; the executor is what reads/consumes. */
    public NodeSecretStore secretStore() {
        return secretStore;
    }

    public void start() {
        connect();
    }

    public void shutdown() {
        shutdown = true;
        taskExecutorPool.shutdownNow();
        StreamObserver<NodeTaskEvent> current = outbound;
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
        StreamObserver<ServerTaskCommand> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServerTaskCommand command) {
                // Any message from the server proves this connection is alive; a later failure
                // should not still be penalised by delay accumulated from a much earlier one.
                reconnectAttempt.set(0);
                handleCommand(command);
            }

            @Override
            public void onError(Throwable t) {
                String hint = ControlPlaneConnection.leaderHint(t);
                if (hint != null) {
                    // A redirect is a successful discovery, not a failure: reconnect immediately to the
                    // hinted address rather than burning a backoff delay slot on it.
                    LOG.info("↪ Task channel redirected to leader hint '{}'; reconnecting", hint);
                    connection.onLeaderHint(hint);
                    reconnectAttempt.set(0);
                    reconnectImmediately();
                    return;
                }
                LOG.warn("⚠ Task channel error: {}; reconnecting", t.getMessage());
                connection.onFailure();
                scheduleReconnect();
            }

            @Override
            public void onCompleted() {
                LOG.warn("⚠ Task channel closed by the control plane; reconnecting");
                scheduleReconnect();
            }
        };

        StreamObserver<NodeTaskEvent> newOutbound = connection.asyncStub().taskChannel(responseObserver);
        outbound = newOutbound;
        synchronized (writeLock) {
            newOutbound.onNext(NodeTaskEvent.newBuilder()
                    .setHello(TaskChannelHello.newBuilder().setNodeId(nodeId).build())
                    .build());
        }
        LOG.info("🔌 Task channel opened for node '{}'", nodeId);
    }

    private void scheduleReconnect() {
        if (shutdown) {
            return;
        }
        long delay = backoff.delayForAttempt(reconnectAttempt.incrementAndGet());
        // Sleeping/reconnecting on the gRPC callback thread would deadlock the channel; hop off it.
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            connect();
        }, "task-channel-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /** As {@link #scheduleReconnect()}, but with no delay — used for a leader-redirect hint, which
     * should be followed as soon as possible rather than waiting out a failure backoff. Still hops off
     * the gRPC callback thread, for the same reason {@link #scheduleReconnect()} does. */
    private void reconnectImmediately() {
        if (shutdown) {
            return;
        }
        Thread reconnectThread = new Thread(this::connect, "task-channel-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void handleCommand(ServerTaskCommand command) {
        switch (command.getCommandCase()) {
            case DISPATCH -> taskExecutorPool.submit(() -> runDispatch(command.getDispatch()));
            case CANCEL -> {
                String taskId = command.getCancel().getTaskId();
                TaskExecutor executor = runningTasks.get(taskId);
                if (executor == null) {
                    LOG.debug("Cancel requested for task {} — not currently running on this node "
                            + "(already finished, or never dispatched here); ignoring", taskId);
                } else {
                    LOG.info("⏹ Cancelling task {} (best-effort)", taskId);
                    try {
                        executor.cancel(taskId);
                    } catch (RuntimeException e) {
                        LOG.warn("⚠ Cancel for task {} threw: {}", taskId, e.getMessage());
                    }
                }
            }
            case BUILD_CONTEXT_CHUNK -> {
                var chunk = command.getBuildContextChunk();
                try {
                    buildContextStore.appendChunk(chunk.getContextId(), chunk.getData(), chunk.getLast());
                } catch (java.io.IOException e) {
                    LOG.warn("⚠ Failed buffering build context chunk for '{}': {}",
                            chunk.getContextId(), e.getMessage());
                }
            }
            case SECRET_MATERIAL -> {
                var material = command.getSecretMaterial();
                secretStore.put(material.getName(), material.getValue().toByteArray());
            }
            default -> { /* COMMAND_NOT_SET */ }
        }
    }

    /**
     * Runs {@code dispatch} and reports its outcome.
     *
     * <p>{@link #sendProgress}/{@link #sendResult} each read {@link #outbound} fresh, at the moment
     * they actually send — this method deliberately does NOT capture it once up front and reuse that
     * capture after {@code executor.execute(...)} returns. A task can run for a long time, and a
     * reconnect (routine under Raft leader failover — see {@link ControlPlaneConnection}) can happen at
     * any point while it does. Capturing the stream once here would mean {@code sendResult} silently
     * writes to a torn-down stream from before the reconnect and the result is lost forever, even though
     * a perfectly good current connection exists by the time the result is ready to send.
     */
    private void runDispatch(TaskDispatch dispatch) {
        if (outbound == null) {
            return; // disconnected between scheduling and running; nothing to report to yet
        }

        TaskKindDomain kind = TaskKindDomain.fromProto(dispatch.getKind());
        if (kind == null) {
            sendResult(dispatch.getTaskId(), false, "",
                    "FAILED — unrecognised task kind " + dispatch.getKind());
            return;
        }
        TaskExecutor executor = executors.get(kind);
        if (executor == null) {
            sendResult(dispatch.getTaskId(), false, "",
                    "FAILED — no executor registered for " + kind);
            return;
        }

        String taskId = dispatch.getTaskId();
        runningTasks.put(taskId, executor);
        sendProgress(taskId, TaskState.TASK_STATE_RUNNING);
        try {
            TaskEventSink sink = (line, stderr) -> sendLog(taskId, line, stderr);
            String resultJson = executor.execute(taskId, dispatch.getPayloadJson(), sink);
            sendResult(taskId, true, resultJson, "");
        } catch (Exception e) {
            LOG.error("❌ Task {} failed: {}", taskId, e.getMessage(), e);
            sendResult(taskId, false, "", "FAILED — " + e.getMessage());
        } finally {
            runningTasks.remove(taskId, executor);
        }
    }

    private void sendProgress(String taskId, TaskState state) {
        StreamObserver<NodeTaskEvent> channel = outbound;
        if (channel == null) {
            LOG.warn("⚠ Could not report progress for task {}: no open task channel", taskId);
            return;
        }
        try {
            NodeTaskEvent event = NodeTaskEvent.newBuilder()
                    .setProgress(TaskProgressEvent.newBuilder().setTaskId(taskId).setState(state).build())
                    .build();
            synchronized (writeLock) {
                channel.onNext(event);
            }
        } catch (RuntimeException e) {
            LOG.warn("⚠ Could not report progress for task {}: {}", taskId, e.getMessage());
        }
    }

    /** Best-effort: a lost log line is never worth failing the task over, unlike a lost result. */
    private void sendLog(String taskId, String line, boolean stderr) {
        StreamObserver<NodeTaskEvent> channel = outbound;
        if (channel == null) {
            return;
        }
        try {
            NodeTaskEvent event = NodeTaskEvent.newBuilder()
                    .setLog(TaskLogEvent.newBuilder()
                            .setTaskId(taskId)
                            .setLine(line)
                            .setStderr(stderr)
                            .setSequence(logSequence.incrementAndGet())
                            .setEmittedAtEpochMillis(System.currentTimeMillis())
                            .build())
                    .build();
            synchronized (writeLock) {
                channel.onNext(event);
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not send log line for task {}: {}", taskId, e.getMessage());
        }
    }

    private void sendResult(String taskId, boolean success, String resultJson, String error) {
        StreamObserver<NodeTaskEvent> channel = outbound;
        if (channel == null) {
            LOG.warn("⚠ Could not report result for task {}: no open task channel — result lost", taskId);
            return;
        }
        try {
            NodeTaskEvent event = NodeTaskEvent.newBuilder()
                    .setResult(TaskResultEvent.newBuilder()
                            .setTaskId(taskId)
                            .setSuccess(success)
                            .setResultJson(resultJson)
                            .setError(error)
                            .build())
                    .build();
            synchronized (writeLock) {
                channel.onNext(event);
            }
        } catch (RuntimeException e) {
            LOG.warn("⚠ Could not report result for task {}: {}", taskId, e.getMessage());
        }
    }
}
