package com.nextgen.controlplane;

import com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer;
import com.nextgen.controlplane.capacity.NodeCapacityScorer;
import com.nextgen.controlplane.docker.NodeDockerCommandRegistry;
import com.nextgen.controlplane.docker.NodeDockerStateRegistry;
import com.nextgen.controlplane.job.JobCoordinator;
import com.nextgen.controlplane.job.JobRecord;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.raft.LeadershipStatus;
import com.nextgen.controlplane.raft.RaftNode;
import com.nextgen.controlplane.task.BuildContextStore;
import com.nextgen.controlplane.task.JobEventBroadcaster;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.PortRelayManager;
import com.nextgen.controlplane.task.SynchronizedStreamObserver;
import com.nextgen.controlplane.task.TaskDispatcher;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.task.TaskStateDomain;
import com.nextgen.controlplane.training.JobOutcomeLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.proto.ControlPlaneProto.*;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import com.nextgen.proto.PredictorServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC service implementation for the ControlPlane.
 *
 * <p>All node state lives in {@link NodeRegistry}; task placement lives in
 * {@link RoundRobinScheduler}. This class is the wire adapter between them and gRPC, and holds no
 * mutable state of its own beyond the lazily-built predictor stub.
 */
public class ControlPlaneServiceImpl extends ControlPlaneServiceGrpc.ControlPlaneServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneServiceImpl.class);

    /** Cadence the control plane asks agents to heartbeat at. */
    private static final int SUGGESTED_HEARTBEAT_INTERVAL_MS = 2_000;

    /** Predictor calls are advisory; a slow predictor must never stall task placement. */
    private static final long PREDICTOR_DEADLINE_MS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NodeRegistry registry;
    private final RoundRobinScheduler scheduler;
    private final NodeHistory nodeHistory;
    private final TaskRegistry taskRegistry;
    private final NodeTaskChannelRegistry channelRegistry;
    private final TaskDispatcher taskDispatcher;
    private final JobRegistry jobRegistry;
    private final JobCoordinator jobCoordinator;
    private final BuildContextStore buildContextStore;
    private final com.nextgen.controlplane.task.SecretStore secretStore;
    private final PortRelayManager portRelayManager;
    /** Always constructed internally, like {@link #nodeHistory} — purely in-memory, ephemeral live
     * fan-out with nothing to configure. See {@link #streamJobEvents}. */
    private final JobEventBroadcaster jobEventBroadcaster = new JobEventBroadcaster();
    /** Stage T: same "always internal, nothing to configure" discipline as {@link #jobEventBroadcaster}
     * — real per-node Docker inventory + the live channel used to push container control commands. See
     * {@link #dockerStateChannel}. */
    private final NodeDockerStateRegistry dockerStateRegistry = new NodeDockerStateRegistry();
    private final NodeDockerCommandRegistry dockerCommandRegistry = new NodeDockerCommandRegistry();
    /** Correlates a {@link #controlDockerContainer} call awaiting a result with the {@code
     * DockerControlResult} that eventually arrives back over {@link #dockerStateChannel}'s request
     * side — the same "push down the stream, await the async reply" shape the whole RPC is built on. */
    private final ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<DockerControlResult>>
            pendingDockerCommands = new ConcurrentHashMap<>();
    /** Null (every constructor below except the last) means "mutate the registries directly" —
     * today's exact behavior. See {@link ControlPlaneWriter}'s Javadoc. */
    private final ControlPlaneWriter writer;
    /** Null in every non-Raft deployment — see {@link #getClusterStatus}. */
    private final RaftNode raftNode;

    private PredictorServiceGrpc.PredictorServiceBlockingStub predictorStub;
    private volatile boolean predictorInitialized = false;

    // ── Prometheus Metrics ──────────────────────────────
    // These stay static because they are registered on the default CollectorRegistry, which rejects
    // duplicate registration. Node counts are NOT here — they come from NodeRegistryCollector, which
    // reads the registry at scrape time so it cannot drift out of date.
    private static final Counter REGISTRATIONS = Counter.build()
            .name("controlplane_registrations_total")
            .help("Total node registrations")
            .register();

    private static final Counter REREGISTRATIONS = Counter.build()
            .name("controlplane_reregistrations_total")
            .help("Registrations that merged into an existing node record (reconnects)")
            .register();

    private static final Counter HEARTBEATS = Counter.build()
            .name("controlplane_heartbeats_total")
            .help("Total heartbeats received")
            .labelNames("node_id")
            .register();

    private static final Counter UNKNOWN_HEARTBEATS = Counter.build()
            .name("controlplane_unknown_heartbeats_total")
            .help("Heartbeats from nodes absent from the registry (triggers re-registration)")
            .register();

    private static final Counter TASKS_SUBMITTED = Counter.build()
            .name("controlplane_tasks_submitted_total")
            .help("Total tasks submitted")
            .register();

    private static final Counter TASKS_ASSIGNED = Counter.build()
            .name("controlplane_tasks_assigned_total")
            .help("Tasks successfully placed on a node")
            .labelNames("node_id")
            .register();

    private static final Counter TASKS_UNPLACEABLE = Counter.build()
            .name("controlplane_tasks_unplaceable_total")
            .help("Tasks rejected because no schedulable node was available")
            .register();

    private static final Counter STALE_TASK_REPORTS = Counter.build()
            .name("controlplane_stale_task_reports_total")
            .help("Task progress/result reports dropped because the reporting node no longer owns the task")
            .register();

    private static final Histogram HEARTBEAT_PROCESSING = Histogram.build()
            .name("controlplane_heartbeat_processing_seconds")
            .help("Server-side time to process a heartbeat (same JVM clock on both ends)")
            .buckets(.0001, .0005, .001, .005, .01, .05, .1)
            .register();

    private static final Histogram HEARTBEAT_INTERARRIVAL = Histogram.build()
            .name("controlplane_heartbeat_interarrival_seconds")
            .help("Observed gap between consecutive heartbeats from a node")
            .buckets(.5, 1, 2, 3, 5, 8, 15, 30)
            .register();

    /** Legacy constructor: wraps an externally-held map so existing callers keep their reference. */
    public ControlPlaneServiceImpl(ConcurrentHashMap<String, NodeRecord> registry) {
        this(new NodeRegistry(registry, System::currentTimeMillis), new RoundRobinScheduler());
    }

    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler) {
        this(registry, scheduler, new TaskRegistry(), new NodeTaskChannelRegistry());
    }

    /**
     * @param taskRegistry    tracks real dispatched tasks — see the {@code com.nextgen.controlplane.task}
     *                        package Javadoc for why this didn't exist before.
     * @param channelRegistry tracks each connected node's open {@code TaskChannel}, the only route the
     *                        server has to reach a node (nodes never accept inbound connections).
     */
    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler,
                                   TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry) {
        this(registry, scheduler, taskRegistry, channelRegistry,
                new HeuristicNodeCapacityScorer(), JobOutcomeLogger.noop());
    }

    /**
     * @param capacityScorer   weights each node's share of a job's initial split — see
     *                         {@link JobCoordinator}'s Javadoc for the full rationale.
     * @param jobOutcomeLogger records real (node properties, allocated share) &rarr; outcome examples
     *                         for future capacity-model training. {@link JobOutcomeLogger#noop()}
     *                         disables this entirely, which every other constructor uses.
     */
    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler,
                                   TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                                   NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger) {
        this(registry, scheduler, taskRegistry, channelRegistry, capacityScorer, jobOutcomeLogger,
                new JobRegistry(), null, null);
    }

    /**
     * @param jobRegistry constructed by the caller (rather than internally, as every other
     *                    constructor does) so it can be the SAME instance {@code writer} was built
     *                    against when Raft is enabled — {@code ControlPlaneServer.start()} needs one
     *                    shared {@link JobRegistry}, not a private copy each collaborator can't see.
     * @param writer      see {@link ControlPlaneWriter}; null preserves every other constructor's
     *                    exact direct-call behavior. Shared, unchanged, with the {@link TaskDispatcher}
     *                    and {@link JobCoordinator} this constructs internally.
     * @param raftNode    null in every non-Raft deployment. Already constructed (but not necessarily
     *                    started) by the caller before this — see {@link #getClusterStatus}.
     */
    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler,
                                   TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                                   NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger,
                                   JobRegistry jobRegistry, ControlPlaneWriter writer, RaftNode raftNode) {
        this(registry, scheduler, taskRegistry, channelRegistry, capacityScorer, jobOutcomeLogger,
                jobRegistry, writer, raftNode, defaultBuildContextStore());
    }

    /**
     * @param buildContextStore Stage N: server-side staging for CLI-uploaded build-context tarballs —
     *                          see {@link BuildContextStore}'s Javadoc. {@code ControlPlaneServer.start()}
     *                          passes the same instance it also starts as a daemon-thread TTL sweeper;
     *                          every shorter constructor above builds its own default instance (a real,
     *                          working store, not a disabled stub — build-context upload works out of
     *                          the box for any caller/test that doesn't care about it specifically).
     */
    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler,
                                   TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                                   NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger,
                                   JobRegistry jobRegistry, ControlPlaneWriter writer, RaftNode raftNode,
                                   BuildContextStore buildContextStore) {
        this(registry, scheduler, taskRegistry, channelRegistry, capacityScorer, jobOutcomeLogger,
                jobRegistry, writer, raftNode, buildContextStore, defaultPortRelayManager());
    }

    /**
     * @param portRelayManager Stage O: server-side half of the cross-node service networking relay —
     *                         see {@link PortRelayManager}'s Javadoc. Every shorter constructor builds
     *                         its own default instance, same discipline as {@code buildContextStore}.
     */
    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler,
                                   TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                                   NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger,
                                   JobRegistry jobRegistry, ControlPlaneWriter writer, RaftNode raftNode,
                                   BuildContextStore buildContextStore, PortRelayManager portRelayManager) {
        this(registry, scheduler, taskRegistry, channelRegistry, capacityScorer, jobOutcomeLogger,
                jobRegistry, writer, raftNode, buildContextStore, portRelayManager, defaultSecretStore());
    }

    /**
     * @param secretStore Stage NN: server-side encrypted-at-rest secret storage — see
     *                    {@link com.nextgen.controlplane.task.SecretStore}'s Javadoc. Every shorter
     *                    constructor builds its own default instance, same discipline as
     *                    {@code buildContextStore}.
     */
    public ControlPlaneServiceImpl(NodeRegistry registry, RoundRobinScheduler scheduler,
                                   TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                                   NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger,
                                   JobRegistry jobRegistry, ControlPlaneWriter writer, RaftNode raftNode,
                                   BuildContextStore buildContextStore, PortRelayManager portRelayManager,
                                   com.nextgen.controlplane.task.SecretStore secretStore) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.nodeHistory = new NodeHistory();
        this.taskRegistry = taskRegistry;
        this.channelRegistry = channelRegistry;
        this.buildContextStore = buildContextStore;
        this.secretStore = secretStore;
        this.portRelayManager = portRelayManager;
        this.taskDispatcher = new TaskDispatcher(taskRegistry, channelRegistry, System::currentTimeMillis, writer,
                buildContextStore, secretStore);
        this.jobRegistry = jobRegistry;
        this.jobCoordinator = new JobCoordinator(taskRegistry, taskDispatcher, jobRegistry, registry, scheduler,
                capacityScorer, jobOutcomeLogger, writer, portRelayManager, dockerStateRegistry);
        this.writer = writer;
        this.raftNode = raftNode;
    }

    private static BuildContextStore defaultBuildContextStore() {
        Path dataDir = Paths.get(EnvConfig.stringValue("NEXTGEN_DATA_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "data").toString()));
        return new BuildContextStore(dataDir,
                EnvConfig.longValue("BUILD_CONTEXT_TTL_MINUTES", 24 * 60) * 60_000L,
                EnvConfig.longValue("BUILD_CONTEXT_SWEEP_INTERVAL_MS", 60_000L));
    }

    private static PortRelayManager defaultPortRelayManager() {
        return new PortRelayManager(
                EnvConfig.intValue("RELAY_PORT_RANGE_START", 40000),
                EnvConfig.intValue("RELAY_PORT_RANGE_END", 40999));
    }

    private static com.nextgen.controlplane.task.SecretStore defaultSecretStore() {
        Path dataDir = Paths.get(EnvConfig.stringValue("NEXTGEN_DATA_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "data").toString()));
        return new com.nextgen.controlplane.task.SecretStore(dataDir);
    }

    public BuildContextStore buildContextStore() {
        return buildContextStore;
    }

    public com.nextgen.controlplane.task.SecretStore secretStore() {
        return secretStore;
    }

    public PortRelayManager portRelayManager() {
        return portRelayManager;
    }

    public NodeRegistry registry() {
        return registry;
    }

    public TaskRegistry taskRegistry() {
        return taskRegistry;
    }

    public JobRegistry jobRegistry() {
        return jobRegistry;
    }

    public NodeHistory nodeHistory() {
        return nodeHistory;
    }

    public NodeTaskChannelRegistry channelRegistry() {
        return channelRegistry;
    }

    public TaskDispatcher taskDispatcher() {
        return taskDispatcher;
    }

    public JobCoordinator jobCoordinator() {
        return jobCoordinator;
    }

    private synchronized PredictorServiceGrpc.PredictorServiceBlockingStub getPredictorStub() {
        if (!predictorInitialized) {
            String predictorHost = System.getenv().getOrDefault("PREDICTOR_HOST", "predictor");
            int predictorPort = EnvConfig.intValue("PREDICTOR_PORT", 50052);
            try {
                ManagedChannel channel = ManagedChannelBuilder
                        .forAddress(predictorHost, predictorPort)
                        .usePlaintext()
                        .build();
                predictorStub = PredictorServiceGrpc.newBlockingStub(channel);
                LOG.info("Predictor stub configured → {}:{}", predictorHost, predictorPort);
            } catch (Exception e) {
                LOG.warn("Could not connect to predictor at {}:{} - {}",
                        predictorHost, predictorPort, e.getMessage());
            }
            predictorInitialized = true;
        }
        return predictorStub;
    }

    // ── RegisterNode ─────────────────────────────────
    @Override
    public void registerNode(NodeInfo request, StreamObserver<RegisterResponse> responseObserver) {
        String id = request.getNodeId();
        LOG.info("📥 RegisterNode: id={}, ip={}, hostname={}", id, request.getIp(), request.getHostname());

        boolean resumedExisting;
        if (writer != null) {
            // A committed-and-applied write only tells us THAT it applied — re-read the state to learn
            // what it did, exactly as a different replica would. Checked BEFORE proposing since the
            // write is what makes this true from here on.
            resumedExisting = registry.contains(id);
            writer.registerNode(id, request.getIp(), request.getPort(), request.getHostname(),
                    request.getCapabilities(), request.getAgentVersion());
        } else {
            NodeRegistry.RegistrationOutcome outcome = registry.register(
                    id, request.getIp(), request.getPort(), request.getHostname(),
                    request.getCapabilities(), request.getAgentVersion());
            resumedExisting = outcome.resumedExisting();
        }

        REGISTRATIONS.inc();
        if (resumedExisting) {
            REREGISTRATIONS.inc();
            LOG.info("♻ Node '{}' re-integrated into its existing record (no duplicate created)", id);
        }

        responseObserver.onNext(RegisterResponse.newBuilder()
                .setStatus("REGISTERED")
                .setAssignedId(id)
                .setHeartbeatIntervalMillis(SUGGESTED_HEARTBEAT_INTERVAL_MS)
                .setServerEpochMillis(System.currentTimeMillis())
                .setResumedExisting(resumedExisting)
                .build());
        responseObserver.onCompleted();

        LOG.info("✅ Node '{}' registered successfully. Total nodes: {}", id, registry.size());
    }

    // ── SendHeartbeat ────────────────────────────────
    @Override
    public void sendHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        long receivedAt = System.currentTimeMillis();
        Histogram.Timer timer = HEARTBEAT_PROCESSING.startTimer();
        try {
            String id = request.getNodeId();

            Optional<NodeRecord> before = registry.get(id);
            NodeRegistry.HeartbeatOutcome outcome = registry.recordHeartbeat(
                    id, request.getCpu(), request.getCpuAvailable(),
                    request.getMemory(), request.getMemoryAvailable(), request.getSequence());

            if (!outcome.known()) {
                // The control plane has no record — typically because it restarted. Previously this
                // returned UNKNOWN_NODE forever and the agent logged it as a normal beat, so the node
                // never came back. Tell the agent to re-register instead.
                LOG.warn("⚠ Heartbeat from unknown node '{}' — instructing it to re-register", id);
                UNKNOWN_HEARTBEATS.inc();
                responseObserver.onNext(HeartbeatResponse.newBuilder()
                        .setStatus("UNKNOWN_NODE")
                        .setReregistrationRequired(true)
                        .setServerReceiveEpochMillis(receivedAt)
                        .setServerSendEpochMillis(System.currentTimeMillis())
                        .setSuggestedIntervalMillis(SUGGESTED_HEARTBEAT_INTERVAL_MS)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            HEARTBEATS.labels(id).inc();
            before.ifPresent(previous -> {
                long gap = receivedAt - previous.getLastHeartbeatMillis();
                if (gap > 0) {
                    HEARTBEAT_INTERARRIVAL.observe(gap / 1000.0);
                }
            });

            // Real trend history for the predictive risk scorer (Stage D) — every genuine heartbeat
            // passes through here regardless of whether any dashboard is even connected.
            nodeHistory.record(id, new NodeHistory.Sample(
                    receivedAt,
                    request.getCpu(), request.getCpuAvailable(),
                    request.getMemory(), request.getMemoryAvailable(),
                    request.getBatteryPercent(), request.getBatteryAvailable(),
                    request.getCharging(), request.getChargingKnown(),
                    request.getOnAcPower(), request.getOnAcPowerKnown(),
                    request.getPreviousRttSeconds(), request.getPreviousRttAvailable()));

            NodeRecord record = outcome.record();
            if (LOG.isDebugEnabled()) {
                LOG.debug("💓 Heartbeat: node={}, cpu={}, mem={}", id,
                        record.isCpuStale() ? "unavailable"
                                : String.format(Locale.ROOT, "%.1f%%", record.getCpuUsage()),
                        record.isMemoryStale() ? "unavailable"
                                : String.format(Locale.ROOT, "%.1f%%", record.getMemoryUsage()));
            }

            responseObserver.onNext(HeartbeatResponse.newBuilder()
                    .setStatus("OK")
                    .setNodeStatus(record.getStatus().toProto())
                    .setServerReceiveEpochMillis(receivedAt)
                    .setServerSendEpochMillis(System.currentTimeMillis())
                    .setSuggestedIntervalMillis(SUGGESTED_HEARTBEAT_INTERVAL_MS)
                    .build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    // ── SubmitTask ───────────────────────────────────

    /**
     * Accepts a task and dispatches it — but does NOT wait for it to finish.
     *
     * <p>Previously this method's entire job was picking a node and returning a confirmation string;
     * nothing was ever actually sent to that node, and nothing tracked the task afterward. It now
     * really dispatches the task over the selected node's {@link #taskChannel}, but still returns
     * immediately: {@link ControlPlaneClient} attaches a 4-second deadline to every call, so blocking
     * here until a real (potentially long-running) task finishes would routinely time out. Poll
     * {@link #getTaskStatus} for the real outcome.
     */
    @Override
    public void submitTask(TaskRequest request, StreamObserver<TaskResponse> responseObserver) {
        LOG.info("📋 SubmitTask: task_id={}, payload={}, kind={}",
                request.getTaskId(), request.getPayload(), request.getKind());
        TASKS_SUBMITTED.inc();

        // Selection stays scoped to liveness alone, exactly as before real dispatch existed —
        // RoundRobinScheduler's placement decision is a separate concern from both TaskDispatcher's
        // delivery mechanics and kind validity below. A selected node whose task then fails for either
        // reason is still a real placement decision (visible via GetTaskStatus as FAILED / reported
        // honestly here), not the same as "no node was available at all" — and critically, the
        // scheduler's round-robin cursor must advance the same way regardless of what happens next.
        List<NodeRecord> candidates = registry.aliveSnapshot();
        Optional<NodeRecord> selected = scheduler.select(candidates);

        if (selected.isEmpty()) {
            LOG.error("❌ No alive nodes available for task {}", request.getTaskId());
            TASKS_UNPLACEABLE.inc();
            responseObserver.onNext(TaskResponse.newBuilder()
                    .setAssignedNode("NONE")
                    .setResult("FAILED — no alive nodes")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        NodeRecord selectedNode = selected.get();
        TASKS_ASSIGNED.labels(selectedNode.getNodeId()).inc();

        TaskKindDomain kind = TaskKindDomain.fromProto(request.getKind());
        if (kind == null) {
            LOG.error("❌ Task {} names an unrecognised kind: {}", request.getTaskId(), request.getKind());
            responseObserver.onNext(TaskResponse.newBuilder()
                    .setAssignedNode(selectedNode.getNodeId())
                    .setResult("FAILED — unrecognised task kind")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        if (writer != null) {
            writer.submitTask(request.getTaskId(), kind, request.getPayload());
        } else {
            taskRegistry.createAndQueue(request.getTaskId(), "", kind, request.getPayload());
        }
        boolean dispatched = taskDispatcher.dispatch(request.getTaskId(), selectedNode.getNodeId());

        if (!dispatched) {
            LOG.error("❌ Could not dispatch task {} to node {}", request.getTaskId(), selectedNode.getNodeId());
            responseObserver.onNext(TaskResponse.newBuilder()
                    .setAssignedNode(selectedNode.getNodeId())
                    .setResult("FAILED — could not dispatch to " + selectedNode.getNodeId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String prediction = requestPrediction(selectedNode);
        String result = String.format("ACCEPTED — dispatched to node '%s' [prediction: %s]",
                selectedNode.getNodeId(), prediction);
        LOG.info("✅ {}", result);

        responseObserver.onNext(TaskResponse.newBuilder()
                .setAssignedNode(selectedNode.getNodeId())
                .setResult(result)
                .build());
        responseObserver.onCompleted();
    }

    // ── TaskChannel ──────────────────────────────────

    /**
     * The one long-lived, node-initiated stream a node opens (after registering) and keeps open for
     * its whole lifetime. The server pushes {@link TaskDispatch}/{@link TaskCancel} down the
     * response side whenever it has work; the node reports {@link TaskProgressEvent}/
     * {@link TaskResultEvent} back up the request side. No inbound port on the node is ever needed.
     */
    @Override
    public StreamObserver<NodeTaskEvent> taskChannel(StreamObserver<ServerTaskCommand> responseObserver) {
        // Every write to this node's channel MUST be serialized: a job with N sub-tasks landing on
        // the same node means TaskDispatcher.dispatch can be called for several of them from
        // different gRPC handler threads at once, and unsynchronized concurrent onNext calls corrupt
        // the wire (see SynchronizedStreamObserver's Javadoc). Registered/unregistered/used
        // exclusively as this one wrapped instance from here on, never the raw responseObserver.
        StreamObserver<ServerTaskCommand> safeObserver = new SynchronizedStreamObserver<>(responseObserver);

        return new StreamObserver<>() {
            /** Bound from the stream's first message ({@link TaskChannelHello}); nothing before that is trusted. */
            private volatile String nodeId;

            @Override
            public void onNext(NodeTaskEvent event) {
                switch (event.getEventCase()) {
                    case HELLO -> {
                        nodeId = event.getHello().getNodeId();
                        channelRegistry.register(nodeId, safeObserver);
                        LOG.info("🔌 Task channel opened for node '{}'", nodeId);
                    }
                    case PROGRESS -> handleProgress(event.getProgress());
                    case RESULT -> handleResult(event.getResult());
                    case LOG -> handleLog(event.getLog());
                    default -> { /* EVENT_NOT_SET — nothing to do */ }
                }
            }

            private void handleProgress(TaskProgressEvent progress) {
                if (nodeId == null || !fenceOrCount(progress.getTaskId(), nodeId)) {
                    return;
                }
                if (progress.getState() == TaskState.TASK_STATE_RUNNING) {
                    if (writer != null) {
                        writer.markTaskRunning(progress.getTaskId(), nodeId);
                    } else {
                        taskRegistry.markRunning(progress.getTaskId(), nodeId);
                    }
                }
                publishJobEvent(progress.getTaskId(), TaskEvent.newBuilder().setProgress(progress));
            }

            private void handleResult(TaskResultEvent result) {
                if (nodeId == null || !fenceOrCount(result.getTaskId(), nodeId)) {
                    return;
                }
                Optional<TaskRecord> updated;
                if (writer != null) {
                    if (result.getSuccess()) {
                        writer.markTaskCompleted(result.getTaskId(), nodeId, result.getResultJson());
                    } else {
                        writer.markTaskFailed(result.getTaskId(), nodeId, result.getError());
                    }
                    updated = taskRegistry.get(result.getTaskId());
                } else {
                    updated = result.getSuccess()
                            ? taskRegistry.markCompleted(result.getTaskId(), nodeId, result.getResultJson())
                            : taskRegistry.markFailed(result.getTaskId(), nodeId, result.getError());
                }
                publishJobEvent(result.getTaskId(), TaskEvent.newBuilder().setResult(result));
                // A standalone task (Stage A) has no jobId and is a no-op inside onSubTaskTerminal; a
                // job sub-task (Stage B) is what may trigger a retry or a job-level reduce here.
                updated.filter(TaskRecord::hasJob).ifPresent(jobCoordinator::onSubTaskTerminal);
            }

            private void handleLog(TaskLogEvent logEvent) {
                if (nodeId == null) {
                    return;
                }
                // Best-effort, no fencing: a log line from a since-migrated-away node is still real
                // output an operator following `nx logs` would want to see, unlike a stale RESULT which
                // must never be applied as authoritative.
                publishJobEvent(logEvent.getTaskId(), TaskEvent.newBuilder().setLog(logEvent));
            }

            /** Routes a task-scoped event out to any external {@code StreamJobEvents} subscribers of
             * that task's job — a no-op for a standalone task (Stage A) with no {@code jobId}, or a
             * task this server has no record of (e.g. a stray/late report). */
            private void publishJobEvent(String taskId, TaskEvent.Builder eventBuilder) {
                taskRegistry.get(taskId).filter(TaskRecord::hasJob).ifPresent(record ->
                        jobEventBroadcaster.publish(record.getJobId(), eventBuilder
                                .setTaskId(taskId)
                                .setEmittedAtEpochMillis(System.currentTimeMillis())
                                .build()));
            }

            @Override
            public void onError(Throwable t) {
                if (nodeId != null) {
                    channelRegistry.unregister(nodeId, safeObserver);
                    LOG.warn("🔌 Task channel for node '{}' closed with error: {}", nodeId, t.getMessage());
                }
            }

            @Override
            public void onCompleted() {
                if (nodeId != null) {
                    channelRegistry.unregister(nodeId, safeObserver);
                    LOG.info("🔌 Task channel closed for node '{}'", nodeId);
                }
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * Observability check ahead of {@link TaskRegistry}'s own internal fencing, which is the real
     * safety net regardless of this: reports {@code controlplane_stale_task_reports_total} when a
     * report names a task that exists but is no longer this node's to update (e.g. it was proactively
     * migrated elsewhere). Returns false in that case so the caller skips the redundant mutator call.
     */
    private boolean fenceOrCount(String taskId, String reportingNodeId) {
        Optional<TaskRecord> current = taskRegistry.get(taskId);
        if (current.isPresent() && !current.get().getAssignedNodeId().equals(reportingNodeId)) {
            STALE_TASK_REPORTS.inc();
            LOG.warn("⚠ Dropping stale report for task {} from node {} (currently assigned to {})",
                    taskId, reportingNodeId, current.get().getAssignedNodeId());
            return false;
        }
        return true;
    }

    // ── UploadBuildContext (Stage N) ──────────────────

    /**
     * Client-streaming: the CLI sends a build context tarball as a sequence of {@link BuildContextChunk}
     * messages, and this assembles + hashes them via {@link BuildContextStore}. Deliberately routed
     * through the SAME assembled-then-forwarded shape as the node-delivery half (see
     * {@code TaskDispatcher.shipBuildContext}) — no direct CLI-to-node path exists, matching this
     * project's hub-and-spoke rule.
     */
    @Override
    public StreamObserver<BuildContextChunk> uploadBuildContext(
            StreamObserver<UploadBuildContextResponse> responseObserver) {
        BuildContextStore.Upload upload;
        try {
            upload = buildContextStore.beginUpload();
        } catch (IOException e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Could not begin build context upload: " + e.getMessage())
                    .asRuntimeException());
            return new StreamObserver<>() {
                @Override public void onNext(BuildContextChunk chunk) { /* upload never started */ }
                @Override public void onError(Throwable t) { /* upload never started */ }
                @Override public void onCompleted() { /* upload never started */ }
            };
        }

        return new StreamObserver<>() {
            private volatile boolean failed = false;

            @Override
            public void onNext(BuildContextChunk chunk) {
                if (failed) {
                    return;
                }
                try {
                    upload.write(chunk.getData().toByteArray());
                } catch (IOException e) {
                    failed = true;
                    LOG.warn("Failed writing build context chunk for upload {}: {}",
                            upload.contextId(), e.getMessage());
                    upload.abort();
                    responseObserver.onError(Status.INTERNAL
                            .withDescription("Failed writing build context: " + e.getMessage())
                            .asRuntimeException());
                }
            }

            @Override
            public void onError(Throwable t) {
                LOG.warn("Build context upload {} failed: {}", upload.contextId(), t.getMessage());
                upload.abort();
            }

            @Override
            public void onCompleted() {
                if (failed) {
                    return; // already reported via onError above
                }
                try {
                    BuildContextStore.StoredContext stored = upload.finish();
                    responseObserver.onNext(UploadBuildContextResponse.newBuilder()
                            .setContextId(stored.contextId())
                            .setSizeBytes(stored.sizeBytes())
                            .setSha256(stored.sha256Hex())
                            .build());
                    responseObserver.onCompleted();
                } catch (IOException e) {
                    LOG.warn("Failed finishing build context upload {}: {}", upload.contextId(), e.getMessage());
                    responseObserver.onError(Status.INTERNAL
                            .withDescription("Failed finishing build context: " + e.getMessage())
                            .asRuntimeException());
                }
            }
        };
    }

    // ── SetSecret (Stage NN) ───────────────────────────

    /** Operator-invoked (via {@code nx secret set}), never called by a node. Encrypts and persists via
     * {@link com.nextgen.controlplane.task.SecretStore} — see its Javadoc for the at-rest crypto. The
     * plaintext travels in this unary request only as far as this method call; it's re-encrypted before
     * ever touching disk and is never logged. */
    @Override
    public void setSecret(SetSecretRequest request, StreamObserver<Empty> responseObserver) {
        if (request.getName().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("secret name must not be blank").asRuntimeException());
            return;
        }
        try {
            secretStore.put(request.getName(), request.getValue().toByteArray());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (java.security.GeneralSecurityException | IOException e) {
            LOG.warn("Failed storing secret '{}': {}", request.getName(), e.getMessage());
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Could not store secret: " + e.getMessage()).asRuntimeException());
        }
    }

    // ── TunnelPort (Stage O) ───────────────────────────

    /**
     * Node-initiated, once per one of that node's own service ports another service may need to reach
     * — see {@code TunnelFrame}'s own proto Javadoc for the hello-frame handshake and multiplexing
     * scheme. This handler is deliberately thin: all the real relay logic (accepting consumer
     * connections, forwarding bytes) lives in {@link PortRelayManager}; this just binds an incoming
     * stream to the (project, service) the hello frame names and routes subsequent frames.
     */
    @Override
    public StreamObserver<TunnelFrame> tunnelPort(StreamObserver<TunnelFrame> responseObserver) {
        StreamObserver<TunnelFrame> safeObserver = new SynchronizedStreamObserver<>(responseObserver);

        return new StreamObserver<>() {
            private volatile String projectName;
            private volatile String serviceName;
            private volatile boolean attached = false;

            @Override
            public void onNext(TunnelFrame frame) {
                if (!attached) {
                    if (!frame.getTunnelId().isEmpty()) {
                        LOG.warn("⚠ TunnelPort stream sent a data frame before its hello — dropping");
                        return;
                    }
                    try {
                        JsonNode hello = MAPPER.readTree(frame.getData().toStringUtf8());
                        projectName = hello.path("project_name").asText();
                        serviceName = hello.path("service_name").asText();
                    } catch (Exception e) {
                        safeObserver.onError(Status.INVALID_ARGUMENT
                                .withDescription("Malformed TunnelPort hello frame: " + e.getMessage())
                                .asRuntimeException());
                        return;
                    }
                    if (!portRelayManager.attachStream(projectName, serviceName, safeObserver)) {
                        safeObserver.onError(Status.FAILED_PRECONDITION
                                .withDescription("No relay port reserved for '" + projectName + "/" + serviceName + "'")
                                .asRuntimeException());
                        return;
                    }
                    attached = true;
                    LOG.info("🔀 TunnelPort stream attached for '{}/{}'", projectName, serviceName);
                    return;
                }
                portRelayManager.onFrameFromNode(projectName, serviceName, frame);
            }

            @Override
            public void onError(Throwable t) {
                if (attached) {
                    // detachStream (not release): this stream is only ONE of possibly several replica
                    // backends for this (project, service) under Stage OO — removing just this one
                    // leaves the others (and the shared listener) untouched.
                    portRelayManager.detachStream(projectName, serviceName, safeObserver);
                    LOG.warn("🔀 TunnelPort stream for '{}/{}' closed with error: {}",
                            projectName, serviceName, t.getMessage());
                }
            }

            @Override
            public void onCompleted() {
                if (attached) {
                    portRelayManager.detachStream(projectName, serviceName, safeObserver);
                    LOG.info("🔀 TunnelPort stream closed for '{}/{}'", projectName, serviceName);
                }
                responseObserver.onCompleted();
            }
        };
    }

    // ── GetTaskStatus / ListTasks ─────────────────────

    @Override
    public void getTaskStatus(TaskStatusRequest request, StreamObserver<TaskStatusResponse> responseObserver) {
        Optional<TaskRecord> record = taskRegistry.get(request.getTaskId());
        if (record.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No such task: " + request.getTaskId())
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(toStatusResponse(record.get()));
        responseObserver.onCompleted();
    }

    @Override
    public void listTasks(Empty request, StreamObserver<TaskList> responseObserver) {
        TaskList.Builder builder = TaskList.newBuilder();
        for (TaskRecord record : taskRegistry.snapshot()) {
            builder.addTasks(toStatusResponse(record));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * Stage U: a real, CONFIRMED cancel — not the fire-and-forget push {@code ProactiveMigrator} already
     * sends internally. This is the client-invocable RPC {@code nx down} calls (previously
     * {@code UNIMPLEMENTED} — confirmed live during an end-to-end run). The actual confirmed-wait logic
     * lives in {@link TaskDispatcher#cancelAndAwaitConfirmation}, shared with Stage PP's rolling update
     * (which needs the identical guarantee before it's safe to dispatch a replacement replica) — this
     * handler is a thin wire adapter over it.
     */
    @Override
    public void cancelTask(TaskCancelRequest request, StreamObserver<TaskCancelResponse> responseObserver) {
        TaskDispatcher.CancelOutcome outcome =
                taskDispatcher.cancelAndAwaitConfirmation(request.getTaskId(), request.getReason());
        responseObserver.onNext(TaskCancelResponse.newBuilder()
                .setAccepted(outcome.accepted())
                .setMessage(outcome.message())
                .build());
        responseObserver.onCompleted();
    }

    private static TaskStatusResponse toStatusResponse(TaskRecord record) {
        long updatedAt = Math.max(record.getCompletedAtMillis(),
                Math.max(record.getDispatchedAtMillis(), record.getCreatedAtMillis()));
        return TaskStatusResponse.newBuilder()
                .setTaskId(record.getTaskId())
                .setState(record.getState().toProto())
                .setAssignedNode(record.getAssignedNodeId())
                .setResultJson(record.getResultJson())
                .setError(record.getError())
                .setUpdatedAtEpochMillis(updatedAt)
                .build();
    }

    // ── SubmitJob / GetJobStatus / ListJobs ───────────

    /**
     * Splits a job into N real sub-tasks and dispatches them via {@link JobCoordinator} — see its
     * Javadoc for why this composes on top of {@link #taskRegistry}/{@link #taskDispatcher} rather
     * than duplicating them. Async-ack, exactly like {@link #submitTask}: poll {@link #getJobStatus}.
     */
    @Override
    public void submitJob(JobRequest request, StreamObserver<JobSubmitResponse> responseObserver) {
        LOG.info("📦 SubmitJob: job_id={}, kind={}, sub_task_count={}",
                request.getJobId(), request.getKind(), request.getSubTaskCount());

        TaskKindDomain kind = TaskKindDomain.fromProto(request.getKind());
        if (kind == null) {
            responseObserver.onNext(JobSubmitResponse.newBuilder()
                    .setJobId(request.getJobId())
                    .setResult("FAILED — unrecognised task kind")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        JobRecord job;
        try {
            // Stage PP: a non-blank supersedes_job_id means this is a rolling update, not a fresh job —
            // routed to JobCoordinator.updateJob's real cancel-then-redispatch-then-await sequence
            // instead of a plain split+dispatch.
            job = request.getSupersedesJobId().isBlank()
                    ? jobCoordinator.submitJob(request.getJobId(), kind, request.getPayloadJson(),
                            request.getSubTaskCount())
                    : jobCoordinator.updateJob(request.getJobId(), request.getSupersedesJobId(),
                            request.getPayloadJson());
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            LOG.error("❌ SubmitJob {} rejected: {}", request.getJobId(), e.getMessage());
            responseObserver.onNext(JobSubmitResponse.newBuilder()
                    .setJobId(request.getJobId())
                    .setResult("FAILED — " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String result = String.format("ACCEPTED — %d sub-task(s) dispatched", job.getTaskIds().size());
        LOG.info("✅ {} for job {}", result, request.getJobId());

        responseObserver.onNext(JobSubmitResponse.newBuilder()
                .setJobId(request.getJobId())
                .setResult(result)
                .setSubTaskCount(job.getTaskIds().size())
                .build());
        responseObserver.onCompleted();
    }

    /** Stage PP: {@code nx rollback} — re-applies whatever job {@code request.getJobId()} itself
     * superseded, as a new rolling update superseding the CURRENT one. See {@link
     * JobCoordinator#rollbackJob} for how the previous spec is reconstructed. */
    @Override
    public void rollbackJob(JobStatusRequest request, StreamObserver<JobSubmitResponse> responseObserver) {
        JobRecord job;
        try {
            String rollbackJobId = request.getJobId() + "-rollback-" + System.currentTimeMillis();
            job = jobCoordinator.rollbackJob(rollbackJobId, request.getJobId());
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            LOG.error("❌ RollbackJob {} rejected: {}", request.getJobId(), e.getMessage());
            responseObserver.onNext(JobSubmitResponse.newBuilder()
                    .setJobId(request.getJobId())
                    .setResult("FAILED — " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
            return;
        }
        String result = String.format("ACCEPTED — rolled back to %d sub-task(s)", job.getTaskIds().size());
        LOG.info("↩ {} for job {}", result, job.getJobId());
        responseObserver.onNext(JobSubmitResponse.newBuilder()
                .setJobId(job.getJobId())
                .setResult(result)
                .setSubTaskCount(job.getTaskIds().size())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getJobStatus(JobStatusRequest request, StreamObserver<JobStatusResponse> responseObserver) {
        Optional<JobRecord> job = jobRegistry.get(request.getJobId());
        if (job.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No such job: " + request.getJobId())
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(toJobStatusResponse(job.get()));
        responseObserver.onCompleted();
    }

    @Override
    public void listJobs(Empty request, StreamObserver<JobList> responseObserver) {
        JobList.Builder builder = JobList.newBuilder();
        for (JobRecord job : jobRegistry.snapshot()) {
            builder.addJobs(toJobStatusResponse(job));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * Stage L/R: live fan-out of a job's real {@code TaskProgressEvent}/{@code TaskResultEvent}/
     * {@code TaskLogEvent} traffic as it arrives over the relevant nodes' {@code TaskChannel} streams —
     * see {@link JobEventBroadcaster}'s own Javadoc. Stays open until the caller cancels (the {@code nx}
     * CLI's own {@code up}/{@code logs} follow forever, matching {@code docker compose}'s own attached
     * behavior) — never calls {@code onCompleted} itself.
     */
    @Override
    public void streamJobEvents(JobEventsRequest request, StreamObserver<TaskEvent> responseObserver) {
        String jobId = request.getJobId();
        StreamObserver<TaskEvent> safeObserver = new SynchronizedStreamObserver<>(responseObserver);
        jobEventBroadcaster.subscribe(jobId, safeObserver);
        if (responseObserver instanceof io.grpc.stub.ServerCallStreamObserver<TaskEvent> serverCallObserver) {
            serverCallObserver.setOnCancelHandler(() -> jobEventBroadcaster.unsubscribe(jobId, safeObserver));
        }
    }

    /**
     * Sub-task completed/failed counts are computed live from {@link #taskRegistry} on every call
     * rather than cached on {@link JobRecord} — {@code TaskRegistry} stays the single source of truth
     * for sub-task state, and a RUNNING job's counts must reflect whatever has happened so far, not a
     * stale snapshot from submission time.
     */
    private JobStatusResponse toJobStatusResponse(JobRecord job) {
        int completedCount = 0;
        int failedCount = 0;
        for (String taskId : job.getTaskIds()) {
            Optional<TaskStateDomain> state = taskRegistry.get(taskId).map(TaskRecord::getState);
            if (state.isPresent() && state.get() == TaskStateDomain.COMPLETED) {
                completedCount++;
            } else if (state.isPresent() && state.get() == TaskStateDomain.FAILED) {
                failedCount++;
            }
        }
        return JobStatusResponse.newBuilder()
                .setJobId(job.getJobId())
                .setState(job.getState().toProto())
                .setSubTaskCount(job.getTaskIds().size())
                .setCompletedCount(completedCount)
                .setFailedCount(failedCount)
                .setCombinedResultJson(job.getCombinedResultJson())
                .setUpdatedAtEpochMillis(job.getUpdatedAtMillis())
                .addAllTaskIds(job.getTaskIds())
                .build();
    }

    /**
     * Best-effort predictor lookup. Returns the literal {@code "N/A"} when the predictor is
     * unreachable or the node's telemetry is stale — never a substituted number.
     */
    private String requestPrediction(NodeRecord node) {
        if (node.isCpuStale() || node.isMemoryStale()) {
            // Feeding a stale reading into the predictor would produce a confident-looking answer
            // derived from data we know is not current.
            return "N/A (node telemetry unavailable)";
        }
        try {
            PredictorServiceGrpc.PredictorServiceBlockingStub stub = getPredictorStub();
            if (stub == null) {
                return "N/A";
            }
            PredictionResponse response = stub
                    .withDeadlineAfter(PREDICTOR_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getPrediction(PredictionRequest.newBuilder()
                            .setNodeId(node.getNodeId())
                            .setCpu(node.getCpuUsage())
                            .setMemory(node.getMemoryUsage())
                            .build());
            String prediction = String.format(Locale.US, "load=%.2f, fail_prob=%.2f, rec=%s",
                    response.getPredictedLoad(), response.getFailureProbability(),
                    response.getRecommendation());
            LOG.info("🔮 Prediction for {}: {}", node.getNodeId(), prediction);
            return prediction;
        } catch (Exception e) {
            LOG.warn("⚠ Predictor unavailable: {}", e.getMessage());
            return "N/A";
        }
    }

    // ── GetNodes ─────────────────────────────────────
    @Override
    public void getNodes(Empty request, StreamObserver<NodeList> responseObserver) {
        List<NodeRecord> all = registry.snapshot();
        LOG.debug("📊 GetNodes request — returning {} nodes", all.size());

        NodeList.Builder builder = NodeList.newBuilder();
        // Every node is returned regardless of status. Consumers need to see dead nodes in order to
        // display them as dead; the difference from before is that `status` now travels with them, so
        // a client no longer has to assume everything it receives is healthy.
        for (NodeRecord record : all) {
            builder.addNodes(withLatestPowerReading(record));
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * {@code NodeRecord} itself carries no battery/charging/AC-power fields — {@link NodeHistory} is
     * the single source of truth for them, so there is nothing to duplicate or let drift out of sync.
     * This overlays the node's latest recorded sample onto its otherwise-unchanged {@code NodeInfo}.
     * A node with no heartbeat yet (or heartbeats predating these fields) simply keeps the proto
     * defaults, which already mean "unavailable/unknown" — no special-casing needed.
     */
    private NodeInfo withLatestPowerReading(NodeRecord record) {
        NodeInfo base = record.toProto();
        return nodeHistory.latest(record.getNodeId())
                .map(sample -> base.toBuilder()
                        .setBatteryPercent(sample.batteryPercent())
                        .setBatteryAvailable(sample.batteryAvailable())
                        .setCharging(sample.charging())
                        .setChargingKnown(sample.chargingKnown())
                        .setOnAcPower(sample.onAcPower())
                        .setOnAcPowerKnown(sample.onAcPowerKnown())
                        .build())
                .orElse(base);
    }

    // ── DeregisterNode ───────────────────────────────
    @Override
    public void deregisterNode(DeregisterRequest request, StreamObserver<DeregisterResponse> responseObserver) {
        String id = request.getNodeId();
        boolean changed;
        if (writer != null) {
            changed = registry.contains(id); // see registerNode's identical before/after reasoning
            writer.deregisterNode(id, request.getDrain());
        } else {
            changed = registry.deregister(id, request.getDrain());
        }
        if (changed && !request.getDrain()) {
            // Only on a real removal — draining keeps the record (and so should keep its history).
            nodeHistory.forget(id);
        }

        if (changed) {
            LOG.info("👋 Node '{}' {} (reason: {})", id,
                    request.getDrain() ? "draining" : "deregistered",
                    request.getReason().isEmpty() ? "unspecified" : request.getReason());
        } else {
            LOG.warn("⚠ Deregister requested for unknown node '{}'", id);
        }

        responseObserver.onNext(DeregisterResponse.newBuilder()
                .setAccepted(changed)
                .setMessage(changed
                        ? (request.getDrain() ? "DRAINING" : "DEREGISTERED")
                        : "UNKNOWN_NODE")
                .build());
        responseObserver.onCompleted();
    }

    // ── GetClusterStatus ─────────────────────────────

    /**
     * Answerable by any replica — leader or follower — unlike every other RPC on this service, which
     * {@code RaftLeaderRedirectInterceptor} gates to the leader only. This is how a client discovers
     * the current leader proactively instead of only learning it from a rejected write's trailer.
     *
     * <p>In a non-Raft deployment ({@code raftNode == null}, the default single-server setup) there is
     * no cluster to describe — this honestly reports the one process as its own leader at term 0,
     * rather than fabricating Raft state that was never actually consulted.
     */
    @Override
    public void getClusterStatus(Empty request, StreamObserver<ClusterStatus> responseObserver) {
        ClusterStatus status;
        if (raftNode == null) {
            status = ClusterStatus.newBuilder()
                    .setNodeId("")
                    .setRole("LEADER")
                    .setTerm(0)
                    .setLeaderId("")
                    .setLeaderAddress("")
                    .setCommitIndex(0)
                    .setLastApplied(0)
                    .build();
        } else {
            LeadershipStatus leadership = raftNode.leadership();
            ClusterStatus.Builder builder = ClusterStatus.newBuilder()
                    .setNodeId(raftNode.nodeId())
                    .setRole(leadership.role().name())
                    .setTerm(leadership.term())
                    .setLeaderId(leadership.leaderId())
                    .setLeaderAddress(leadership.leaderAddress())
                    .setCommitIndex(raftNode.commitIndex())
                    .setLastApplied(raftNode.lastApplied());
            raftNode.members().forEach(member -> builder.addMembers(member.id()));
            status = builder.build();
        }
        responseObserver.onNext(status);
        responseObserver.onCompleted();
    }

    // ── DockerStateChannel / GetDockerResources / ControlDockerContainer (Stage T) ───

    private static final long DOCKER_CONTROL_TIMEOUT_MS = 20_000;

    /**
     * The Stage T sibling of {@link #taskChannel}: a node-initiated stream a Docker-capable node opens
     * once (never opened at all on a node without a reachable Docker daemon — see
     * {@code DockerCapabilityDetector}). The node reports a fresh {@link DockerStateReport} on its own
     * interval; the server pushes {@link DockerControlCommand}s down the response side whenever {@link
     * #controlDockerContainer} has one. A genuinely separate stream from {@code TaskChannel} — Docker
     * inventory exists independently of whether this node currently has any task dispatched to it.
     */
    @Override
    public StreamObserver<NodeDockerEvent> dockerStateChannel(StreamObserver<ServerDockerCommand> responseObserver) {
        // Same reasoning as taskChannel's safeObserver: a node could receive two concurrent
        // controlDockerContainer pushes (e.g. stop one container, start another) from different gRPC
        // handler threads, and unsynchronized concurrent onNext calls corrupt the wire.
        StreamObserver<ServerDockerCommand> safeObserver = new SynchronizedStreamObserver<>(responseObserver);

        return new StreamObserver<>() {
            /** Bound from the stream's first message ({@link DockerChannelHello}); nothing before that
             * is trusted, mirroring {@code taskChannel}'s identical discipline. */
            private volatile String nodeId;

            @Override
            public void onNext(NodeDockerEvent event) {
                switch (event.getEventCase()) {
                    case HELLO -> {
                        nodeId = event.getHello().getNodeId();
                        dockerCommandRegistry.register(nodeId, safeObserver);
                        LOG.info("🐳 Docker-state channel opened for node '{}'", nodeId);
                    }
                    case REPORT -> {
                        if (nodeId != null) {
                            dockerStateRegistry.update(nodeId, event.getReport());
                        }
                    }
                    case RESULT -> {
                        DockerControlResult result = event.getResult();
                        var future = pendingDockerCommands.remove(result.getCommandId());
                        if (future != null) {
                            future.complete(result);
                        }
                    }
                    default -> { /* EVENT_NOT_SET — nothing to do */ }
                }
            }

            @Override
            public void onError(Throwable t) {
                if (nodeId != null) {
                    dockerCommandRegistry.unregister(nodeId, safeObserver);
                    // A disconnected node's last-known inventory would look live if left in place —
                    // remove it rather than silently presenting stale data as current (see
                    // NodeDockerStateRegistry's own Javadoc).
                    dockerStateRegistry.remove(nodeId);
                    LOG.warn("🐳 Docker-state channel for node '{}' closed with error: {}", nodeId, t.getMessage());
                }
            }

            @Override
            public void onCompleted() {
                if (nodeId != null) {
                    dockerCommandRegistry.unregister(nodeId, safeObserver);
                    dockerStateRegistry.remove(nodeId);
                    LOG.info("🐳 Docker-state channel closed for node '{}'", nodeId);
                }
                responseObserver.onCompleted();
            }
        };
    }

    /** Point-in-time aggregate across every currently-connected Docker-capable node — what the desktop
     * UI and {@code nx images}/{@code nx volumes}/{@code nx networks}/{@code nx container ls} poll. A
     * node that has never reported (or isn't Docker-capable) is simply absent, never present with a
     * fabricated empty report. */
    @Override
    public void getDockerResources(Empty request, StreamObserver<DockerResourcesSnapshot> responseObserver) {
        responseObserver.onNext(DockerResourcesSnapshot.newBuilder()
                .addAllNodes(dockerStateRegistry.snapshot())
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Routes a start/stop/restart/rm request to the node that owns {@code container_id}, over its
     * already-open {@link #dockerStateChannel}, and blocks (bounded by {@link
     * #DOCKER_CONTROL_TIMEOUT_MS}) for that node's real {@link DockerControlResult} — never a synthetic
     * "accepted" response, since the caller (desktop UI/{@code nx container}) needs to know whether the
     * real {@code docker} invocation actually succeeded. Fails honestly, matching {@link
     * com.nextgen.controlplane.task.TaskDispatcher}'s own "FAILED — node has no open task channel"
     * precedent, if the target node has no open channel at all — never a silent no-op.
     */
    @Override
    public void controlDockerContainer(DockerControlCommand request, StreamObserver<DockerControlResult> responseObserver) {
        Optional<StreamObserver<ServerDockerCommand>> channel = dockerCommandRegistry.get(request.getNodeId());
        if (channel.isEmpty()) {
            responseObserver.onNext(DockerControlResult.newBuilder()
                    .setCommandId(request.getCommandId())
                    .setOk(false)
                    .setMessage("node '" + request.getNodeId() + "' has no open Docker-state channel")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String commandId = request.getCommandId().isBlank()
                ? java.util.UUID.randomUUID().toString() : request.getCommandId();
        DockerControlCommand withId = request.toBuilder().setCommandId(commandId).build();
        var future = new java.util.concurrent.CompletableFuture<DockerControlResult>();
        pendingDockerCommands.put(commandId, future);

        DockerControlResult result;
        try {
            channel.get().onNext(ServerDockerCommand.newBuilder().setControl(withId).build());
            result = future.get(DOCKER_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            result = DockerControlResult.newBuilder().setCommandId(commandId).setOk(false)
                    .setMessage("Timed out waiting for node '" + request.getNodeId() + "' to respond").build();
        } catch (Exception e) {
            result = DockerControlResult.newBuilder().setCommandId(commandId).setOk(false)
                    .setMessage("Failed: " + e.getMessage()).build();
        } finally {
            pendingDockerCommands.remove(commandId);
        }
        responseObserver.onNext(result);
        responseObserver.onCompleted();
    }
}
