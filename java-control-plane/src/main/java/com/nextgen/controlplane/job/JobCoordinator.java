package com.nextgen.controlplane.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nextgen.controlplane.ControlPlaneWriter;
import com.nextgen.controlplane.EnvConfig;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.RoundRobinScheduler;
import com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer;
import com.nextgen.controlplane.capacity.NodeCapacityScorer;
import com.nextgen.controlplane.task.PortRelayManager;
import com.nextgen.controlplane.task.TaskDispatcher;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.task.TaskStateDomain;
import com.nextgen.controlplane.training.JobOutcomeLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Splits a job into N real sub-tasks, dispatches them through {@link TaskDispatcher} exactly like a
 * standalone {@code SubmitTask} would, and reduces them into one combined result once every sub-task
 * is terminal — {@link TaskRegistry}/{@link TaskDispatcher} are not duplicated, only composed on top
 * of: a job's sub-task is a completely ordinary {@code TaskRecord} that happens to carry a
 * {@code jobId}.
 *
 * <p>Owns exactly one piece of scheduling policy beyond Stage A: a sub-task that fails is redispatched
 * to a different node <b>once</b> (tracked via {@link JobRecord#hasBeenRetried}). This is a reactive
 * retry on an <i>observed</i> failure, deliberately separate from Stage D's proactive migration on a
 * <i>predicted</i> one — different triggers, different mechanisms, composed on the same
 * {@code TaskDispatcher} underneath.
 *
 * <p>Reduction is server-side and, today, kind-specific ({@code TASK_KIND_PRIME_COUNT_RANGE} only) —
 * a deliberate limit noted in the project plan: it will not scale to a workload whose own reduce step
 * is expensive, which would reintroduce the single-point-of-failure problem this project exists to
 * avoid. Extending to more kinds means extending the split/reduce logic here, not redesigning it.
 */
public final class JobCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(JobCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskRegistry taskRegistry;
    private final TaskDispatcher taskDispatcher;
    private final JobRegistry jobRegistry;
    private final NodeRegistry nodeRegistry;
    private final RoundRobinScheduler scheduler;
    private final NodeCapacityScorer capacityScorer;
    private final JobOutcomeLogger jobOutcomeLogger;
    /** Null (every constructor below except the last two) means "mutate taskRegistry/jobRegistry
     * directly" — today's exact behavior. See {@link ControlPlaneWriter}'s Javadoc. */
    private final ControlPlaneWriter writer;
    /** Null (every constructor below except the last) disables Stage O peer/relay-port env injection —
     * a docker-compose job with declared {@code peers} still runs every service, they simply stay
     * cross-node-unreachable, matching every other optional-capability-off degrade in this project. */
    private final PortRelayManager portRelayManager;

    public JobCoordinator(TaskRegistry taskRegistry, TaskDispatcher taskDispatcher, JobRegistry jobRegistry,
                          NodeRegistry nodeRegistry, RoundRobinScheduler scheduler) {
        this(taskRegistry, taskDispatcher, jobRegistry, nodeRegistry, scheduler,
                new HeuristicNodeCapacityScorer(), JobOutcomeLogger.noop());
    }

    /**
     * @param capacityScorer   weights each alive node's share of a job's initial split — see
     *                         {@link NodeCapacityScorer}'s Javadoc for why this doesn't apply to
     *                         retries/migrations.
     * @param jobOutcomeLogger records real (node properties, allocated share) &rarr; (duration, outcome)
     *                         examples, the dataset a future learned capacity model would train on.
     *                         {@link JobOutcomeLogger#noop()} disables this entirely.
     */
    public JobCoordinator(TaskRegistry taskRegistry, TaskDispatcher taskDispatcher, JobRegistry jobRegistry,
                          NodeRegistry nodeRegistry, RoundRobinScheduler scheduler,
                          NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger) {
        this(taskRegistry, taskDispatcher, jobRegistry, nodeRegistry, scheduler,
                capacityScorer, jobOutcomeLogger, null);
    }

    /** @param writer see {@link ControlPlaneWriter}; null preserves every other constructor's exact
     *                direct-call behavior. */
    public JobCoordinator(TaskRegistry taskRegistry, TaskDispatcher taskDispatcher, JobRegistry jobRegistry,
                          NodeRegistry nodeRegistry, RoundRobinScheduler scheduler,
                          NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger,
                          ControlPlaneWriter writer) {
        this(taskRegistry, taskDispatcher, jobRegistry, nodeRegistry, scheduler,
                capacityScorer, jobOutcomeLogger, writer, null);
    }

    /** @param portRelayManager Stage O/P: reserves relay ports for a docker-compose job's provider
     *                          services and injects peer env vars into their consumers — see
     *                          {@link #injectPeerRelayInfo}. */
    public JobCoordinator(TaskRegistry taskRegistry, TaskDispatcher taskDispatcher, JobRegistry jobRegistry,
                          NodeRegistry nodeRegistry, RoundRobinScheduler scheduler,
                          NodeCapacityScorer capacityScorer, JobOutcomeLogger jobOutcomeLogger,
                          ControlPlaneWriter writer, PortRelayManager portRelayManager) {
        this.taskRegistry = taskRegistry;
        this.taskDispatcher = taskDispatcher;
        this.jobRegistry = jobRegistry;
        this.nodeRegistry = nodeRegistry;
        this.scheduler = scheduler;
        this.capacityScorer = capacityScorer;
        this.portRelayManager = portRelayManager;
        this.jobOutcomeLogger = jobOutcomeLogger;
        this.writer = writer;
    }

    /**
     * Splits {@code payloadJson} into real sub-tasks and dispatches them, kind-aware: a
     * {@code PRIME_COUNT_RANGE} job splits a numeric range into {@code subTaskCount} sub-ranges; a
     * {@code DOCKER_COMPOSE_SERVICE} job creates one sub-task per declared service and ignores
     * {@code subTaskCount} entirely (the service count from the payload IS the sub-task count) — see
     * {@link #submitDockerComposeJob}.
     *
     * @throws IllegalArgumentException if {@code kind}/{@code subTaskCount}/{@code payloadJson} is
     *         not something this method knows how to split — the caller reports this back to the
     *         submitter as an honest rejection, never silently accepted as a zero-sub-task job.
     */
    public JobRecord submitJob(String jobId, TaskKindDomain kind, String payloadJson, int subTaskCount) {
        return switch (kind) {
            case PRIME_COUNT_RANGE -> submitPrimeCountJob(jobId, payloadJson, subTaskCount);
            case DOCKER_COMPOSE_SERVICE -> submitDockerComposeJob(jobId, payloadJson);
        };
    }

    private JobRecord submitPrimeCountJob(String jobId, String payloadJson, int subTaskCount) {
        if (subTaskCount < 1) {
            throw new IllegalArgumentException("sub_task_count must be >= 1");
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid payload JSON: " + e.getMessage());
        }
        long rangeStart = payload.path("range_start").asLong();
        long rangeEnd = payload.path("range_end").asLong();
        if (rangeEnd < rangeStart) {
            throw new IllegalArgumentException(
                    "range_end (" + rangeEnd + ") must be >= range_start (" + rangeStart + ")");
        }

        // Capability-aware split: the sub-task COUNT is decided exactly as before (never capped by
        // node count — a cluster of one node can still usefully be handed several independent
        // sub-tasks, e.g. so a later reactive retry has something granular to redispatch). What
        // changes is HOW the range is cut and WHICH node each piece goes to: a capacity-weighted
        // round-robin picks the node for each slot (a stronger node appears more often), and every
        // slot is then sized proportionally to the weight of the node handling it — so a weak or
        // at-risk node no longer receives the same-size chunk as a strong one just because it happened
        // to be next in a plain identity rotation.
        List<NodeRecord> aliveNodes = nodeRegistry.aliveSnapshot();
        List<long[]> subRanges;
        List<String> assignedNodeIds;
        if (aliveNodes.isEmpty()) {
            // No capacity data to weight against — same honest "nothing to dispatch to" fallback as
            // before; dispatchInitially below will mark each sub-task FAILED with the real reason.
            subRanges = splitRange(rangeStart, rangeEnd, subTaskCount);
            assignedNodeIds = new ArrayList<>(subRanges.size());
            for (int i = 0; i < subRanges.size(); i++) {
                assignedNodeIds.add("");
            }
        } else {
            int actualCount = (int) Math.max(1, Math.min(subTaskCount, Math.max(rangeEnd - rangeStart, 1)));
            List<NodeRecord> assignment = assignNodesByCapacity(aliveNodes, actualCount);
            List<Double> slotWeights = assignment.stream().map(capacityScorer::scoreCapacity).toList();
            subRanges = splitRangeWeighted(rangeStart, rangeEnd, slotWeights);
            assignedNodeIds = assignment.stream().map(NodeRecord::getNodeId).toList();
        }

        List<String> taskIds = new ArrayList<>(subRanges.size());
        List<String> subPayloads = new ArrayList<>(subRanges.size());
        for (int i = 0; i < subRanges.size(); i++) {
            String taskId = jobId + "-" + i;
            String subPayloadJson = MAPPER.createObjectNode()
                    .put("range_start", subRanges.get(i)[0])
                    .put("range_end", subRanges.get(i)[1])
                    .toString();
            taskIds.add(taskId);
            subPayloads.add(subPayloadJson);
        }

        if (writer != null) {
            // One command carrying the whole job's split — see ControlPlaneWriter's Javadoc on why
            // this is one proposal, not subRanges.size() + 1 separate ones.
            List<ControlPlaneWriter.JobSubTaskPlan> plan = new ArrayList<>(taskIds.size());
            for (int i = 0; i < taskIds.size(); i++) {
                plan.add(new ControlPlaneWriter.JobSubTaskPlan(taskIds.get(i), subPayloads.get(i)));
            }
            writer.submitJob(jobId, TaskKindDomain.PRIME_COUNT_RANGE, plan);
        } else {
            for (int i = 0; i < taskIds.size(); i++) {
                taskRegistry.createAndQueue(taskIds.get(i), jobId, TaskKindDomain.PRIME_COUNT_RANGE, subPayloads.get(i));
            }
            jobRegistry.createJob(jobId, TaskKindDomain.PRIME_COUNT_RANGE, taskIds);
        }
        LOG.info("📦 Job {} split into {} sub-task(s)", jobId, taskIds.size());

        for (int i = 0; i < taskIds.size(); i++) {
            String nodeId = assignedNodeIds.get(i);
            if (nodeId.isEmpty()) {
                dispatchInitially(taskIds.get(i));
            } else {
                dispatchToNode(taskIds.get(i), nodeId);
            }
        }

        return jobRegistry.get(jobId).orElseThrow();
    }

    /**
     * Stage P: one sub-task per declared service (no numeric range to split). Candidate nodes are
     * filtered to real Docker capability AND currently idle for this task kind (see the class header's
     * cross-reference) — this filter, applied once here at ranking time rather than as a post-hoc
     * check, is what guarantees each of THIS job's own services lands on a distinct node: services
     * beyond {@code candidates.size()} simply have no node left to claim and fail honestly, rather than
     * doubling up on an already-assigned node the way a repeat-tolerant weighted round-robin could.
     */
    private JobRecord submitDockerComposeJob(String jobId, String payloadJson) {
        JsonNode payload;
        try {
            payload = MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid payload JSON: " + e.getMessage());
        }
        JsonNode servicesNode = payload.path("services");
        if (!servicesNode.isArray() || servicesNode.isEmpty()) {
            throw new IllegalArgumentException("docker-compose job payload must have a non-empty 'services' array");
        }
        String projectName = payload.path("project_name").asText();
        if (projectName.isBlank()) {
            projectName = jobId;
        }

        List<JsonNode> services = new ArrayList<>();
        servicesNode.forEach(services::add);

        List<NodeRecord> candidates = nodeRegistry.aliveSnapshot().stream()
                .filter(n -> n.getCapabilities().getDockerAvailable())
                .filter(n -> taskRegistry.tasksOnNode(n.getNodeId()).stream()
                        .noneMatch(t -> t.getKind() == TaskKindDomain.DOCKER_COMPOSE_SERVICE))
                .sorted(Comparator.comparingDouble(capacityScorer::scoreCapacity).reversed())
                .toList();

        List<String> taskIds = new ArrayList<>(services.size());
        List<String> assignedNodeIds = new ArrayList<>(services.size());
        List<ObjectNode> resolvedSpecs = new ArrayList<>(services.size());
        for (int i = 0; i < services.size(); i++) {
            taskIds.add(jobId + "-" + i);
            assignedNodeIds.add(i < candidates.size() ? candidates.get(i).getNodeId() : "");
            ObjectNode spec = services.get(i).deepCopy();
            spec.put("project_name", projectName);
            resolvedSpecs.add(spec);
        }

        injectPeerRelayInfo(projectName, services, resolvedSpecs, assignedNodeIds);

        List<String> subPayloads = resolvedSpecs.stream().map(JsonNode::toString).toList();

        if (writer != null) {
            List<ControlPlaneWriter.JobSubTaskPlan> plan = new ArrayList<>(taskIds.size());
            for (int i = 0; i < taskIds.size(); i++) {
                plan.add(new ControlPlaneWriter.JobSubTaskPlan(taskIds.get(i), subPayloads.get(i)));
            }
            writer.submitJob(jobId, TaskKindDomain.DOCKER_COMPOSE_SERVICE, plan);
        } else {
            for (int i = 0; i < taskIds.size(); i++) {
                taskRegistry.createAndQueue(taskIds.get(i), jobId, TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                        subPayloads.get(i));
            }
            jobRegistry.createJob(jobId, TaskKindDomain.DOCKER_COMPOSE_SERVICE, taskIds);
        }
        LOG.info("📦 Docker-compose job {} ('{}') split into {} service(s)", jobId, projectName, taskIds.size());

        for (int i = 0; i < taskIds.size(); i++) {
            String nodeId = assignedNodeIds.get(i);
            if (nodeId.isEmpty()) {
                // No eligible idle Docker-capable node was left for this service — fail it honestly
                // rather than falling through to the kind-agnostic scheduler, which could place it on a
                // Docker-less or already-busy node (see dispatchInitially's own Javadoc for why that
                // fallback exists only for PRIME_COUNT_RANGE).
                if (writer != null) {
                    writer.markTaskFailed(taskIds.get(i), "", "FAILED — no eligible idle Docker-capable node available");
                } else {
                    taskRegistry.markFailed(taskIds.get(i), "", "FAILED — no eligible idle Docker-capable node available");
                }
                taskRegistry.get(taskIds.get(i))
                        .filter(record -> record.getState() == TaskStateDomain.FAILED)
                        .ifPresent(this::onSubTaskTerminal);
            } else {
                dispatchToNode(taskIds.get(i), nodeId);
            }
        }

        return jobRegistry.get(jobId).orElseThrow();
    }

    /**
     * Stage O's env-var peer-discovery wiring, resolved here (not at submit time on the CLI) because
     * only the control plane knows which node each service actually landed on. For every service that
     * declares a {@code peers} entry naming another service in this SAME job: reserves (once, deduped)
     * a relay port for the named peer via {@link PortRelayManager}, injects that port into the peer's
     * OWN resolved spec as {@code relay_ports} (so its node knows to open a tunnel — see
     * {@code DockerComposeServiceExecutor}), and injects {@code <env_prefix>_HOST}/{@code _PORT} into
     * the CONSUMING service's {@code environment} map. A peer that doesn't exist in this job, or never
     * got placed on a node, is skipped — the consumer still runs, just without that env var set, an
     * honest degrade rather than a crash (matching this project's "never fabricate a capability"
     * discipline for a capability that genuinely isn't available).
     */
    private void injectPeerRelayInfo(String projectName, List<JsonNode> services, List<ObjectNode> resolvedSpecs,
                                     List<String> assignedNodeIds) {
        if (portRelayManager == null) {
            return;
        }
        String advertisedHost = EnvConfig.stringValue("RELAY_ADVERTISED_HOST", "localhost");
        Map<String, Integer> reservedPortsByPeerService = new HashMap<>();

        for (int i = 0; i < services.size(); i++) {
            JsonNode peersNode = services.get(i).path("peers");
            if (!peersNode.isArray()) {
                continue;
            }
            for (JsonNode peer : peersNode) {
                String peerServiceName = peer.path("service_name").asText();
                if (peerServiceName.isBlank()) {
                    continue;
                }
                String envPrefixRaw = peer.path("env_prefix").asText();
                String envPrefix = envPrefixRaw.isBlank()
                        ? peerServiceName.toUpperCase(Locale.ROOT) : envPrefixRaw;
                int peerIndex = indexOfService(services, peerServiceName);
                if (peerIndex < 0 || assignedNodeIds.get(peerIndex).isEmpty()) {
                    continue;
                }
                Integer relayPort = reservedPortsByPeerService.get(peerServiceName);
                if (relayPort == null) {
                    try {
                        relayPort = portRelayManager.reservePort(projectName, peerServiceName);
                    } catch (IOException e) {
                        LOG.warn("⚠ Could not reserve a relay port for '{}/{}': {}",
                                projectName, peerServiceName, e.getMessage());
                        continue;
                    }
                    reservedPortsByPeerService.put(peerServiceName, relayPort);
                    // The PROVIDER's relay_ports entry must be ITS OWN local published port (what
                    // PortTunnelClient dials as localhost:<port> on the provider's own node) — NOT the
                    // externally-reserved relay port above, which only ever means anything on the
                    // control plane's machine. Consumers get the reserved port via <PEER>_PORT below;
                    // the provider gets its own port so it knows which local container to bridge to.
                    Integer providerLocalPort = extractPrimaryHostPort(services.get(peerIndex));
                    if (providerLocalPort == null) {
                        LOG.warn("⚠ Peer service '{}/{}' has no published 'ports' entry to relay — "
                                        + "'{}' will get {}_HOST/{}_PORT but the connection will fail",
                                projectName, peerServiceName, services.get(i).path("service_name").asText(),
                                envPrefix, envPrefix);
                    } else {
                        addRelayPort(resolvedSpecs.get(peerIndex), providerLocalPort);
                    }
                }
                ObjectNode env = ensureObjectField(resolvedSpecs.get(i), "environment");
                env.put(envPrefix + "_HOST", advertisedHost);
                env.put(envPrefix + "_PORT", String.valueOf(relayPort));
            }
        }
    }

    private static int indexOfService(List<JsonNode> services, String serviceName) {
        for (int i = 0; i < services.size(); i++) {
            if (services.get(i).path("service_name").asText().equals(serviceName)) {
                return i;
            }
        }
        return -1;
    }

    /** @return the host-side port of a service's first {@code ports} entry (accepting either a bare
     * port or {@code "hostPort:containerPort"}), or {@code null} if the service published nothing —
     * this is the LOCAL port on the provider's own node that a relay tunnel must bridge to. */
    private static Integer extractPrimaryHostPort(JsonNode serviceSpec) {
        JsonNode portsNode = serviceSpec.path("ports");
        if (!portsNode.isArray() || portsNode.isEmpty()) {
            return null;
        }
        String raw = portsNode.get(0).asText();
        String hostPart = raw.contains(":") ? raw.substring(0, raw.indexOf(':')) : raw;
        try {
            return Integer.parseInt(hostPart.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addRelayPort(ObjectNode spec, int port) {
        JsonNode existing = spec.get("relay_ports");
        ArrayNode relayPorts = existing instanceof ArrayNode arrayNode ? arrayNode : spec.putArray("relay_ports");
        relayPorts.add(port);
    }

    private static ObjectNode ensureObjectField(ObjectNode spec, String fieldName) {
        JsonNode existing = spec.get(fieldName);
        return existing instanceof ObjectNode objectNode ? objectNode : spec.putObject(fieldName);
    }

    private void dispatchInitially(String taskId) {
        Optional<NodeRecord> node = scheduler.select(nodeRegistry.aliveSnapshot());
        if (node.isEmpty()) {
            if (writer != null) {
                writer.markTaskFailed(taskId, "", "FAILED — no alive nodes");
            } else {
                taskRegistry.markFailed(taskId, "", "FAILED — no alive nodes");
            }
        } else {
            taskDispatcher.dispatch(taskId, node.get().getNodeId());
        }
        taskRegistry.get(taskId)
                .filter(record -> record.getState() == TaskStateDomain.FAILED)
                .ifPresent(this::onSubTaskTerminal);
    }

    /** Like {@link #dispatchInitially} but the node is already chosen — skips {@link #scheduler} entirely. */
    private void dispatchToNode(String taskId, String nodeId) {
        taskDispatcher.dispatch(taskId, nodeId);
        taskRegistry.get(taskId)
                .filter(record -> record.getState() == TaskStateDomain.FAILED)
                .ifPresent(this::onSubTaskTerminal);
    }

    /**
     * Smooth weighted round-robin (the same technique load balancers like nginx use): picks
     * {@code count} nodes from {@code aliveNodes}, in order, such that each node's share of the
     * picks converges to its share of total capacity weight — a node with twice the weight of
     * another appears roughly twice as often, without ever starving the weaker node entirely.
     * Deterministic (no randomness), so identical inputs always produce identical output; ties
     * resolve to the lowest-index node, matching {@code aliveNodes}' id-sorted order.
     */
    private List<NodeRecord> assignNodesByCapacity(List<NodeRecord> aliveNodes, int count) {
        int n = aliveNodes.size();
        double[] weight = new double[n];
        double totalWeight = 0;
        for (int i = 0; i < n; i++) {
            weight[i] = capacityScorer.scoreCapacity(aliveNodes.get(i));
            totalWeight += weight[i];
        }
        double[] current = new double[n];
        List<NodeRecord> assignment = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            int best = 0;
            for (int i = 0; i < n; i++) {
                current[i] += weight[i];
                if (current[i] > current[best]) {
                    best = i;
                }
            }
            assignment.add(aliveNodes.get(best));
            current[best] -= totalWeight;
        }
        return assignment;
    }

    /**
     * Called whenever a sub-task (a {@code TaskRecord} with a non-empty {@code jobId}) reaches a
     * terminal state — from a real result reported over a node's {@code TaskChannel}, or from a
     * dispatch that failed synchronously. A no-op for any task with no job.
     */
    public void onSubTaskTerminal(TaskRecord record) {
        if (!record.hasJob()) {
            return;
        }
        // Logged for every terminal report, including a since-retried failure — that failed attempt on
        // that specific node is still a real, informative (allocated share, outcome) data point.
        jobOutcomeLogger.log(record, nodeRegistry.get(record.getAssignedNodeId()).orElse(null));

        String jobId = record.getJobId();
        Optional<JobRecord> jobOpt = jobRegistry.get(jobId);
        if (jobOpt.isEmpty()) {
            return; // should not happen, but a stray/inconsistent record must never crash the handler
        }

        if (record.getState() == TaskStateDomain.FAILED && !jobOpt.get().hasBeenRetried(record.getTaskId())) {
            Optional<NodeRecord> replacement = pickReplacementNode(record.getAssignedNodeId(), record.getKind());
            if (replacement.isPresent()) {
                if (writer != null) {
                    writer.markTaskRetried(jobId, record.getTaskId());
                } else {
                    jobRegistry.markTaskRetried(jobId, record.getTaskId());
                }
                boolean dispatched = taskDispatcher.dispatch(record.getTaskId(), replacement.get().getNodeId());
                if (!dispatched) {
                    // The retry itself failed synchronously (e.g. the replacement node's channel closed
                    // in the gap since it was picked). The one retry is now used up — re-enter with the
                    // fresh record so this falls through to the permanent-failure/reduce path below.
                    taskRegistry.get(record.getTaskId()).ifPresent(this::onSubTaskTerminal);
                }
                return; // either the retry is in flight, or the re-entrant call above already reduced
            }
            // no alternative node exists — falls through as a permanent failure
        }

        maybeReduce(jobId);
    }

    /**
     * Prefers a node other than the one that just failed; falls back to retrying on the SAME node
     * only if it's the sole alive node in the cluster — still better odds than not retrying at all,
     * since the original failure may have been transient (e.g. a momentarily dropped channel).
     *
     * <p>For {@code DOCKER_COMPOSE_SERVICE}, the candidate pool is filtered to real Docker capability
     * AND currently idle for this task kind — the same rule {@link #submitDockerComposeJob} applies at
     * initial placement — so a retry can never land a compose service on a Docker-less or already-busy
     * node just because {@link RoundRobinScheduler} itself has no idea what kind of task it's placing.
     */
    private Optional<NodeRecord> pickReplacementNode(String excludeNodeId, TaskKindDomain kind) {
        List<NodeRecord> allAlive = nodeRegistry.aliveSnapshot();
        if (kind == TaskKindDomain.DOCKER_COMPOSE_SERVICE) {
            allAlive = allAlive.stream()
                    .filter(n -> n.getCapabilities().getDockerAvailable())
                    .filter(n -> taskRegistry.tasksOnNode(n.getNodeId()).stream()
                            .noneMatch(t -> t.getKind() == TaskKindDomain.DOCKER_COMPOSE_SERVICE))
                    .toList();
        }
        List<NodeRecord> withoutFailedNode = allAlive.stream()
                .filter(n -> !n.getNodeId().equals(excludeNodeId))
                .toList();
        return !withoutFailedNode.isEmpty() ? scheduler.select(withoutFailedNode) : scheduler.select(allAlive);
    }

    /** Reduces the job if every sub-task is now terminal; a no-op otherwise or if already finalized. */
    private void maybeReduce(String jobId) {
        Optional<JobRecord> jobOpt = jobRegistry.get(jobId);
        if (jobOpt.isEmpty() || jobOpt.get().getState() != JobStateDomain.RUNNING) {
            return;
        }
        JobRecord job = jobOpt.get();

        List<TaskRecord> subTasks = new ArrayList<>(job.getTaskIds().size());
        for (String taskId : job.getTaskIds()) {
            Optional<TaskRecord> subTask = taskRegistry.get(taskId);
            if (subTask.isEmpty() || !subTask.get().getState().isTerminal()) {
                return; // at least one sub-task is missing or still in flight — not reducible yet
            }
            subTasks.add(subTask.get());
        }

        int completedCount = 0;
        int failedCount = 0;
        for (TaskRecord subTask : subTasks) {
            if (subTask.getState() == TaskStateDomain.COMPLETED) {
                completedCount++;
            } else {
                failedCount++;
            }
        }

        JobStateDomain finalState;
        if (failedCount == 0) {
            finalState = JobStateDomain.COMPLETED;
        } else if (completedCount == 0) {
            finalState = JobStateDomain.FAILED;
        } else {
            finalState = JobStateDomain.PARTIAL_FAILURE;
        }

        String combinedResultJson = switch (job.getKind()) {
            case PRIME_COUNT_RANGE -> reducePrimeCountResult(subTasks, completedCount, failedCount);
            case DOCKER_COMPOSE_SERVICE -> reduceDockerComposeResult(subTasks, completedCount, failedCount);
        };

        // Two sub-tasks can turn terminal at nearly the same instant (e.g. two different nodes'
        // results arriving on two different gRPC threads), so this whole method can legitimately run
        // twice concurrently once all sub-tasks are done. completeJob's own RUNNING-only guard is the
        // real safety net; checking its result here just keeps the log honest — only the caller that
        // actually performed the transition reports having done so, not both.
        boolean applied;
        if (writer != null) {
            JobStateDomain before = jobRegistry.get(jobId).map(JobRecord::getState).orElse(null);
            writer.completeJob(jobId, finalState, combinedResultJson);
            applied = before == JobStateDomain.RUNNING;
        } else {
            applied = jobRegistry.completeJob(jobId, finalState, combinedResultJson).isPresent();
        }
        if (applied) {
            LOG.info("📦 Job {} reduced: state={} ({} completed, {} failed)",
                    jobId, finalState, completedCount, failedCount);
        }
    }

    private static String reducePrimeCountResult(List<TaskRecord> subTasks, int completedCount, int failedCount) {
        long totalPrimeCount = 0;
        for (TaskRecord subTask : subTasks) {
            if (subTask.getState() == TaskStateDomain.COMPLETED) {
                totalPrimeCount += extractPrimeCount(subTask.getResultJson());
            }
        }
        return MAPPER.createObjectNode()
                .put("prime_count", totalPrimeCount)
                .put("sub_task_count", subTasks.size())
                .put("completed_count", completedCount)
                .put("failed_count", failedCount)
                .toString();
    }

    private static long extractPrimeCount(String resultJson) {
        try {
            return MAPPER.readTree(resultJson).path("prime_count").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Stage P: a project-level status instead of a summed number — see the class header for why
     * reduction is kind-specific by design. */
    private static String reduceDockerComposeResult(List<TaskRecord> subTasks, int completedCount, int failedCount) {
        ArrayNode servicesArray = MAPPER.createArrayNode();
        for (TaskRecord subTask : subTasks) {
            servicesArray.add(MAPPER.createObjectNode()
                    .put("name", extractServiceName(subTask.getPayloadJson()))
                    .put("node_id", subTask.getAssignedNodeId())
                    .put("state", subTask.getState().name())
                    .put("container_id", subTask.getState() == TaskStateDomain.COMPLETED
                            ? extractContainerName(subTask.getResultJson()) : ""));
        }
        return MAPPER.createObjectNode()
                .put("sub_task_count", subTasks.size())
                .put("completed_count", completedCount)
                .put("failed_count", failedCount)
                .set("services", servicesArray)
                .toString();
    }

    private static String extractServiceName(String payloadJson) {
        try {
            return MAPPER.readTree(payloadJson).path("service_name").asText();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractContainerName(String resultJson) {
        try {
            return MAPPER.readTree(resultJson).path("container_name").asText();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Splits {@code [rangeStart, rangeEnd)} into up to {@code requestedCount} contiguous, near-equal
     * sub-ranges — clamped down to at least 1 and to no more sub-ranges than there are integers in the
     * range, so a small range never produces empty-but-numerous sub-tasks.
     */
    static List<long[]> splitRange(long rangeStart, long rangeEnd, int requestedCount) {
        long total = rangeEnd - rangeStart;
        int actualCount = (int) Math.max(1, Math.min(requestedCount, Math.max(total, 1)));

        List<long[]> ranges = new ArrayList<>(actualCount);
        long base = total / actualCount;
        long remainder = total % actualCount;
        long cursor = rangeStart;
        for (int i = 0; i < actualCount; i++) {
            long size = base + (i < remainder ? 1 : 0);
            long end = cursor + size;
            ranges.add(new long[]{cursor, end});
            cursor = end;
        }
        return ranges;
    }

    /**
     * Splits {@code [rangeStart, rangeEnd)} into {@code weights.size()} contiguous sub-ranges sized
     * proportionally to each entry in {@code weights} (same order), using the largest-remainder method
     * so the pieces always sum back to exactly the original total — generalizes {@link #splitRange}'s
     * equal-share remainder distribution from "N equal shares" to "N proportional shares"; with all
     * weights equal the two methods produce byte-identical boundaries.
     *
     * @param weights strictly positive; the caller (capacity-weighted node assignment) guarantees this.
     */
    static List<long[]> splitRangeWeighted(long rangeStart, long rangeEnd, List<Double> weights) {
        int n = weights.size();
        long total = rangeEnd - rangeStart;
        double weightSum = weights.stream().mapToDouble(Double::doubleValue).sum();

        long[] sizes = new long[n];
        double[] remainders = new double[n];
        long allocated = 0;
        for (int i = 0; i < n; i++) {
            double ideal = total * (weights.get(i) / weightSum);
            sizes[i] = (long) Math.floor(ideal);
            remainders[i] = ideal - sizes[i];
            allocated += sizes[i];
        }
        long leftover = total - allocated;

        // Give the leftover units to the largest fractional remainders first; ties break to the lower
        // index, so the result is fully deterministic for identical inputs.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(remainders[b], remainders[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int i = 0; i < leftover; i++) {
            sizes[order[i]]++;
        }

        List<long[]> ranges = new ArrayList<>(n);
        long cursor = rangeStart;
        for (int i = 0; i < n; i++) {
            long end = cursor + sizes[i];
            ranges.add(new long[]{cursor, end});
            cursor = end;
        }
        return ranges;
    }
}
