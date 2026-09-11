package com.nextgen.controlplane.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.RoundRobinScheduler;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.TaskDispatcher;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.task.TaskStateDomain;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives JobCoordinator with synthetic sub-task completions/failures — no real node process, no real
 * gRPC — to check the split → dispatch → retry → reduce logic in isolation. The real-wire version of
 * this (an actual gRPC job, actual fake node streams) lives in SubmitJobIntegrationTest.
 */
class JobCoordinatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NodeRegistry nodeRegistry;
    private TaskRegistry taskRegistry;
    private NodeTaskChannelRegistry channelRegistry;
    private JobRegistry jobRegistry;
    private JobCoordinator coordinator;

    @BeforeEach
    void setUp() {
        nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);
        taskRegistry = new TaskRegistry();
        channelRegistry = new NodeTaskChannelRegistry();
        TaskDispatcher taskDispatcher = new TaskDispatcher(taskRegistry, channelRegistry);
        jobRegistry = new JobRegistry();
        coordinator = new JobCoordinator(
                taskRegistry, taskDispatcher, jobRegistry, nodeRegistry, new RoundRobinScheduler());
    }

    /** Registers a node as ALIVE with an open (fake, no-op) task channel, so dispatch can succeed. */
    private void registerNode(String nodeId) {
        registerNode(nodeId, ControlPlaneProto.NodeCapabilities.getDefaultInstance());
    }

    /** Same as {@link #registerNode(String)} but with declared capabilities, for capacity-split tests. */
    private void registerNode(String nodeId, int cpuCores, long totalMemoryBytes) {
        registerNode(nodeId, ControlPlaneProto.NodeCapabilities.newBuilder()
                .setCpuCores(cpuCores).setTotalMemoryBytes(totalMemoryBytes).build());
    }

    private void registerNode(String nodeId, ControlPlaneProto.NodeCapabilities capabilities) {
        nodeRegistry.register(nodeId, "10.0.0.1", 50051, nodeId, capabilities, "1.0.0");
        channelRegistry.register(nodeId, new StreamObserver<>() {
            @Override public void onNext(ControlPlaneProto.ServerTaskCommand value) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
    }

    private long rangeSizeOf(String taskId) throws Exception {
        JsonNode payload = MAPPER.readTree(taskRegistry.get(taskId).orElseThrow().getPayloadJson());
        return payload.get("range_end").asLong() - payload.get("range_start").asLong();
    }

    /** Simulates a real node reporting success, exactly as ControlPlaneServiceImpl's wire handler would. */
    private void completeSubTask(String taskId, String nodeId, long primeCount) {
        TaskRecord record = taskRegistry.markCompleted(taskId, nodeId,
                "{\"prime_count\":" + primeCount + "}").orElseThrow();
        coordinator.onSubTaskTerminal(record);
    }

    /** Simulates a real node reporting failure. */
    private void failSubTask(String taskId, String nodeId, String error) {
        TaskRecord record = taskRegistry.markFailed(taskId, nodeId, error).orElseThrow();
        coordinator.onSubTaskTerminal(record);
    }

    @Test
    void splitsAJobIntoTheRequestedSubTaskCountAndDispatchesEachOne() {
        registerNode("node1");
        registerNode("node2");

        JobRecord job = coordinator.submitJob("job1", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":1000}", 4);

        assertEquals(4, job.getTaskIds().size());
        assertEquals(JobStateDomain.RUNNING, job.getState());
        for (String taskId : job.getTaskIds()) {
            TaskRecord record = taskRegistry.get(taskId).orElseThrow();
            assertEquals("job1", record.getJobId());
            assertEquals(TaskKindDomain.PRIME_COUNT_RANGE, record.getKind());
            assertNotEquals("", record.getAssignedNodeId(), "each sub-task must have been dispatched");
        }
    }

    @Test
    void clampsSubTaskCountToTheSizeOfTheRange() {
        registerNode("node1");

        // A range with only 3 integers cannot usefully become 10 sub-tasks.
        JobRecord job = coordinator.submitJob("job-small", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":3}", 10);

        assertEquals(3, job.getTaskIds().size());
    }

    @Test
    void rejectsAnInvertedRangeWithoutCreatingAnyTasks() {
        registerNode("node1");

        assertThrows(IllegalArgumentException.class, () -> coordinator.submitJob(
                "bad-job", TaskKindDomain.PRIME_COUNT_RANGE, "{\"range_start\":100,\"range_end\":0}", 3));
        assertTrue(jobRegistry.get("bad-job").isEmpty());
    }

    @Test
    void reducesToCompletedOnceEverySubTaskSucceeds() throws Exception {
        registerNode("node1");
        JobRecord job = coordinator.submitJob("job2", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":30}", 3);
        List<String> taskIds = job.getTaskIds();

        completeSubTask(taskIds.get(0), "node1", 5);
        completeSubTask(taskIds.get(1), "node1", 7);
        // Job must not be reduced until the LAST sub-task turns terminal.
        assertEquals(JobStateDomain.RUNNING, jobRegistry.get("job2").orElseThrow().getState());

        completeSubTask(taskIds.get(2), "node1", 3);

        JobRecord finalJob = jobRegistry.get("job2").orElseThrow();
        assertEquals(JobStateDomain.COMPLETED, finalJob.getState());
        JsonNode result = MAPPER.readTree(finalJob.getCombinedResultJson());
        assertEquals(15, result.get("prime_count").asLong());
        assertEquals(3, result.get("completed_count").asLong());
        assertEquals(0, result.get("failed_count").asLong());
    }

    @Test
    void retriesAFailedSubTaskOnADifferentNodeAndStillCompletes() {
        registerNode("node1");
        registerNode("node2");
        JobRecord job = coordinator.submitJob("job3", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":10}", 1);
        String taskId = job.getTaskIds().get(0);
        String originalNode = taskRegistry.get(taskId).orElseThrow().getAssignedNodeId();
        String otherNode = originalNode.equals("node1") ? "node2" : "node1";

        failSubTask(taskId, originalNode, "transient failure");

        TaskRecord afterRetry = taskRegistry.get(taskId).orElseThrow();
        assertEquals(otherNode, afterRetry.getAssignedNodeId(), "retry must land on the OTHER node");
        assertEquals(TaskStateDomain.DISPATCHED, afterRetry.getState());
        assertEquals(JobStateDomain.RUNNING, jobRegistry.get("job3").orElseThrow().getState(),
                "a task back in flight after retry must not resolve the job yet");

        completeSubTask(taskId, otherNode, 4);

        assertEquals(JobStateDomain.COMPLETED, jobRegistry.get("job3").orElseThrow().getState());
    }

    @Test
    void aSubTaskThatFailsTwiceInARowBecomesPermanentlyFailedAndYieldsPartialFailure() {
        registerNode("node1"); // only one node: the retry has nowhere else to go but back to node1
        JobRecord job = coordinator.submitJob("job4", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":20}", 2);
        List<String> taskIds = job.getTaskIds();

        failSubTask(taskIds.get(0), "node1", "boom");
        assertEquals(TaskStateDomain.DISPATCHED, taskRegistry.get(taskIds.get(0)).orElseThrow().getState(),
                "the one retry must have been used, redispatching to the only available node");

        failSubTask(taskIds.get(0), "node1", "boom again");
        assertEquals(TaskStateDomain.FAILED, taskRegistry.get(taskIds.get(0)).orElseThrow().getState(),
                "a second failure after the retry is used up must be permanent");

        completeSubTask(taskIds.get(1), "node1", 8);

        JobRecord finalJob = jobRegistry.get("job4").orElseThrow();
        assertEquals(JobStateDomain.PARTIAL_FAILURE, finalJob.getState());
    }

    @Test
    void aJobWhereEverySubTaskFailsResolvesToFailedNotPartialFailure() {
        registerNode("node1");
        JobRecord job = coordinator.submitJob("job5", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":10}", 1);
        String taskId = job.getTaskIds().get(0);

        failSubTask(taskId, "node1", "boom");
        failSubTask(taskId, "node1", "boom again");

        JobRecord finalJob = jobRegistry.get("job5").orElseThrow();
        assertEquals(JobStateDomain.FAILED, finalJob.getState(),
                "zero surviving sub-tasks must be FAILED, not PARTIAL_FAILURE — nothing usable came back");
    }

    @Test
    void aTaskWithNoJobIdIsIgnoredByOnSubTaskTerminal() {
        // A standalone SubmitTask task (Stage A) has no jobId; feeding it through here must be a no-op,
        // not throw or otherwise misbehave.
        registerNode("node1");
        TaskRecord standalone = taskRegistry.createAndQueue("solo-task", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        assertDoesNotThrow(() -> coordinator.onSubTaskTerminal(standalone));
    }

    // ── Stage F: capability-aware splitting ─────────────────────────────────

    @Test
    void twoNodesWithDifferentCapacitiesEachReceiveExactlyOneSubTaskSizedProportionally() throws Exception {
        // Weight = cpuCores*1.0 + memoryGb*0.25 → weak=4+8*0.25=6, strong=8+16*0.25=12 (a clean 1:2
        // ratio), so a 1200-unit range splits into exactly 400/800 with zero remainder either way.
        registerNode("weak", 4, 8_000_000_000L);
        registerNode("strong", 8, 16_000_000_000L);

        JobRecord job = coordinator.submitJob("job-capacity", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":1200}", 2);

        assertEquals(2, job.getTaskIds().size());
        String weakTaskId = null;
        String strongTaskId = null;
        for (String taskId : job.getTaskIds()) {
            String assignedNode = taskRegistry.get(taskId).orElseThrow().getAssignedNodeId();
            if (assignedNode.equals("weak")) weakTaskId = taskId;
            if (assignedNode.equals("strong")) strongTaskId = taskId;
        }
        assertTrue(weakTaskId != null && strongTaskId != null,
                "with only a 2x capability gap and 2 sub-tasks, each node must receive exactly one");
        assertEquals(400, rangeSizeOf(weakTaskId));
        assertEquals(800, rangeSizeOf(strongTaskId));
    }

    @Test
    void aFarStrongerNodeAccumulatesAMeasurablyLargerShareThanAFarWeakerOneAcrossManySubTasks() throws Exception {
        registerNode("weak", 1, 1_000_000_000L);
        registerNode("strong", 16, 32_000_000_000L);

        JobRecord job = coordinator.submitJob("job-capacity-many", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":10000}", 8);

        assertEquals(8, job.getTaskIds().size());
        long weakTotal = 0;
        long strongTotal = 0;
        for (String taskId : job.getTaskIds()) {
            String assignedNode = taskRegistry.get(taskId).orElseThrow().getAssignedNodeId();
            long size = rangeSizeOf(taskId);
            if (assignedNode.equals("weak")) weakTotal += size;
            if (assignedNode.equals("strong")) strongTotal += size;
        }
        assertEquals(10000, weakTotal + strongTotal, "every unit of the range must be assigned to some node");
        assertTrue(strongTotal > weakTotal,
                "a node with 16x the cores and 32x the memory must accumulate the larger cumulative share");
    }

    @Test
    void anAtRiskNodeReceivesALessThanEqualShareOfAJobSplitAcrossEquallyCapableNodes() throws Exception {
        registerNode("healthy", 4, 8_000_000_000L);
        registerNode("at-risk", 4, 8_000_000_000L);
        nodeRegistry.updateRisk("at-risk", 1.0, true, List.of("battery_low"));

        JobRecord job = coordinator.submitJob("job-risk-split", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":1000}", 2);

        long healthySize = 0;
        long atRiskSize = 0;
        for (String taskId : job.getTaskIds()) {
            String assignedNode = taskRegistry.get(taskId).orElseThrow().getAssignedNodeId();
            long size = rangeSizeOf(taskId);
            if (assignedNode.equals("healthy")) healthySize = size;
            if (assignedNode.equals("at-risk")) atRiskSize = size;
        }
        assertTrue(healthySize > atRiskSize, "an at-risk node must receive a smaller share than a healthy peer");
    }

    // ── Stage P: docker-compose job splitting, node exclusivity, project-status reduce ──────────

    private void registerDockerNode(String nodeId) {
        registerNode(nodeId, ControlPlaneProto.NodeCapabilities.newBuilder().setDockerAvailable(true).build());
    }

    private static String composeServicesPayload(String... serviceNames) {
        com.fasterxml.jackson.databind.node.ArrayNode services = MAPPER.createArrayNode();
        for (String name : serviceNames) {
            services.add(MAPPER.createObjectNode().put("service_name", name).put("image", "busybox"));
        }
        return MAPPER.createObjectNode().put("project_name", "proj").set("services", services).toString();
    }

    @Test
    void eachDeclaredServiceLandsOnADistinctDockerCapableNode() {
        registerDockerNode("d1");
        registerDockerNode("d2");

        JobRecord job = coordinator.submitJob("djob1", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeServicesPayload("web", "database"), 0);

        assertEquals(2, job.getTaskIds().size());
        List<String> assignedNodes = job.getTaskIds().stream()
                .map(id -> taskRegistry.get(id).orElseThrow().getAssignedNodeId()).toList();
        assertEquals(2, java.util.Set.copyOf(assignedNodes).size(), "each service must land on a distinct node");
        for (String taskId : job.getTaskIds()) {
            assertEquals(TaskKindDomain.DOCKER_COMPOSE_SERVICE, taskRegistry.get(taskId).orElseThrow().getKind());
        }
    }

    @Test
    void aNonDockerCapableNodeIsNeverAssignedADockerComposeService() {
        registerNode("plain"); // no docker_available
        registerDockerNode("docker1");

        JobRecord job = coordinator.submitJob("djob2", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeServicesPayload("web"), 0);

        assertEquals("docker1", taskRegistry.get(job.getTaskIds().get(0)).orElseThrow().getAssignedNodeId());
    }

    @Test
    void servicesBeyondTheNumberOfEligibleNodesFailHonestlyRatherThanDoublingUpOnANode() {
        registerDockerNode("only-one");

        JobRecord job = coordinator.submitJob("djob3", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeServicesPayload("web", "database"), 0);

        List<TaskRecord> records = job.getTaskIds().stream().map(id -> taskRegistry.get(id).orElseThrow()).toList();
        long placed = records.stream().filter(r -> !r.getAssignedNodeId().isEmpty()).count();
        long failed = records.stream().filter(r -> r.getState() == TaskStateDomain.FAILED).count();
        assertEquals(1, placed, "only one node exists — only one service can be placed");
        assertEquals(1, failed, "the unplaceable second service must fail honestly, not double up on the busy node");
    }

    @Test
    void aNodeAlreadyRunningAComposeServiceIsExcludedFromASecondProjectButOtherNodesRemainEligible() {
        registerDockerNode("busy");
        registerDockerNode("idle");
        // Simulate "busy" already running a compose service from an earlier job.
        taskRegistry.createAndQueue("earlier-task", "earlier-job", TaskKindDomain.DOCKER_COMPOSE_SERVICE, "{}");
        taskRegistry.markDispatched("earlier-task", "busy");

        JobRecord job = coordinator.submitJob("djob4", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeServicesPayload("web"), 0);

        assertEquals("idle", taskRegistry.get(job.getTaskIds().get(0)).orElseThrow().getAssignedNodeId(),
                "the already-busy node must be excluded even though it's alive and docker-capable");
    }

    @Test
    void reducingADockerComposeJobProducesAProjectStatusNotASummedNumber() throws Exception {
        registerDockerNode("d1");
        registerDockerNode("d2");
        JobRecord job = coordinator.submitJob("djob5", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeServicesPayload("web", "database"), 0);
        List<String> taskIds = job.getTaskIds();

        TaskRecord r0 = taskRegistry.markCompleted(taskIds.get(0),
                taskRegistry.get(taskIds.get(0)).orElseThrow().getAssignedNodeId(),
                "{\"container_name\":\"nx-proj-web-abc\",\"exit_code\":0}").orElseThrow();
        coordinator.onSubTaskTerminal(r0);
        TaskRecord r1 = taskRegistry.markCompleted(taskIds.get(1),
                taskRegistry.get(taskIds.get(1)).orElseThrow().getAssignedNodeId(),
                "{\"container_name\":\"nx-proj-database-def\",\"exit_code\":0}").orElseThrow();
        coordinator.onSubTaskTerminal(r1);

        JobRecord finalJob = jobRegistry.get("djob5").orElseThrow();
        assertEquals(JobStateDomain.COMPLETED, finalJob.getState());
        JsonNode result = MAPPER.readTree(finalJob.getCombinedResultJson());
        assertTrue(result.has("services"), "a docker-compose job's combined result must have a 'services' array");
        assertFalse(result.has("prime_count"), "a docker-compose job must never carry a prime-count field");
        assertEquals(2, result.get("services").size());
        java.util.Set<String> names = new java.util.HashSet<>();
        result.get("services").forEach(s -> names.add(s.get("name").asText()));
        assertEquals(java.util.Set.of("web", "database"), names);
    }

    @Test
    void peerEnvVarsAndRelayPortsAreInjectedForDeclaredPeerDependencies() {
        registerDockerNode("web-node");
        registerDockerNode("db-node");
        com.nextgen.controlplane.task.PortRelayManager portRelayManager =
                new com.nextgen.controlplane.task.PortRelayManager(41500, 41599);
        TaskDispatcher taskDispatcher = new TaskDispatcher(taskRegistry, channelRegistry);
        JobCoordinator coordinatorWithRelay = new JobCoordinator(taskRegistry, taskDispatcher, jobRegistry,
                nodeRegistry, new RoundRobinScheduler(), new com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer(),
                com.nextgen.controlplane.training.JobOutcomeLogger.noop(), null, portRelayManager);

        com.fasterxml.jackson.databind.node.ArrayNode services = MAPPER.createArrayNode();
        com.fasterxml.jackson.databind.node.ArrayNode peers = MAPPER.createArrayNode();
        peers.add(MAPPER.createObjectNode().put("service_name", "database").put("env_prefix", "DATABASE"));
        services.add(MAPPER.createObjectNode().put("service_name", "web").put("image", "busybox").set("peers", peers));
        com.fasterxml.jackson.databind.node.ArrayNode dbPorts = MAPPER.createArrayNode().add("5432:5432");
        services.add(MAPPER.createObjectNode().put("service_name", "database").put("image", "busybox")
                .set("ports", dbPorts));
        String payload = MAPPER.createObjectNode().put("project_name", "proj").set("services", services).toString();

        JobRecord job = coordinatorWithRelay.submitJob("djob6", TaskKindDomain.DOCKER_COMPOSE_SERVICE, payload, 0);

        JsonNode webSpec = readPayload(job.getTaskIds().get(0));
        JsonNode dbSpec = readPayload(job.getTaskIds().get(1));
        assertTrue(webSpec.path("environment").has("DATABASE_HOST"), "consumer must have peer HOST injected");
        assertTrue(webSpec.path("environment").has("DATABASE_PORT"), "consumer must have peer PORT injected");
        assertTrue(dbSpec.path("relay_ports").isArray() && dbSpec.path("relay_ports").size() == 1,
                "provider must have its relay port declared so it opens a tunnel");
        // The provider's relay_ports entry is its OWN local published port (5432, from its own "ports"
        // list) — a DIFFERENT number from the externally-reserved DATABASE_PORT the consumer gets, which
        // only ever means anything on the control plane's own machine. Conflating the two was a real bug
        // caught during a live end-to-end run: relaying to the wrong port silently fails to connect.
        assertEquals(5432, dbSpec.path("relay_ports").get(0).asInt());
        assertTrue(webSpec.path("environment").path("DATABASE_PORT").asInt() >= 41500
                        && webSpec.path("environment").path("DATABASE_PORT").asInt() <= 41599,
                "consumer's DATABASE_PORT must be the externally-reserved relay port, not the provider's own port");
    }

    // ── Stage OO: load-balanced multi-replica services ────────────────────────────────────────

    @Test
    void aServiceWithReplicasProducesOneTaskPerReplicaOnDistinctNodes() {
        registerDockerNode("d1");
        registerDockerNode("d2");
        registerDockerNode("d3");
        String payload = MAPPER.createObjectNode().put("project_name", "proj")
                .set("services", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .put("service_name", "web").put("image", "busybox").put("replicas", 3)))
                .toString();

        JobRecord job = coordinator.submitJob("djob-replicas", TaskKindDomain.DOCKER_COMPOSE_SERVICE, payload, 0);

        assertEquals(3, job.getTaskIds().size(), "three replicas must produce three real sub-tasks");
        List<String> assignedNodes = job.getTaskIds().stream()
                .map(id -> taskRegistry.get(id).orElseThrow().getAssignedNodeId()).toList();
        assertEquals(3, java.util.Set.copyOf(assignedNodes).size(), "every replica must land on a distinct node");
        for (String taskId : job.getTaskIds()) {
            assertEquals("web", readPayload(taskId).path("service_name").asText(),
                    "every replica's dispatched spec must still be the 'web' service");
        }
    }

    @Test
    void replicasBeyondTheNumberOfEligibleNodesFailHonestlyForTheUnplaceableOnes() {
        registerDockerNode("only-one");
        String payload = MAPPER.createObjectNode().put("project_name", "proj")
                .set("services", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .put("service_name", "web").put("image", "busybox").put("replicas", 3)))
                .toString();

        JobRecord job = coordinator.submitJob("djob-replicas-short", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                payload, 0);

        List<TaskRecord> records = job.getTaskIds().stream().map(id -> taskRegistry.get(id).orElseThrow()).toList();
        long placed = records.stream().filter(r -> !r.getAssignedNodeId().isEmpty()).count();
        long failed = records.stream().filter(r -> r.getState() == TaskStateDomain.FAILED).count();
        assertEquals(1, placed, "only one node exists — only one replica can be placed");
        assertEquals(2, failed, "the two unplaceable replicas must fail honestly, not double up on the busy node");
    }

    @Test
    void everyPlacedReplicaOfAProviderGetsItsOwnRelayPortsEntry() {
        registerDockerNode("web-node");
        registerDockerNode("db-node-1");
        registerDockerNode("db-node-2");
        com.nextgen.controlplane.task.PortRelayManager portRelayManager =
                new com.nextgen.controlplane.task.PortRelayManager(41600, 41699);
        TaskDispatcher taskDispatcher = new TaskDispatcher(taskRegistry, channelRegistry);
        JobCoordinator coordinatorWithRelay = new JobCoordinator(taskRegistry, taskDispatcher, jobRegistry,
                nodeRegistry, new RoundRobinScheduler(), new com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer(),
                com.nextgen.controlplane.training.JobOutcomeLogger.noop(), null, portRelayManager);

        com.fasterxml.jackson.databind.node.ArrayNode services = MAPPER.createArrayNode();
        com.fasterxml.jackson.databind.node.ArrayNode peers = MAPPER.createArrayNode();
        peers.add(MAPPER.createObjectNode().put("service_name", "database").put("env_prefix", "DATABASE"));
        services.add(MAPPER.createObjectNode().put("service_name", "web").put("image", "busybox").set("peers", peers));
        com.fasterxml.jackson.databind.node.ArrayNode dbPorts = MAPPER.createArrayNode().add("5432:5432");
        services.add(MAPPER.createObjectNode().put("service_name", "database").put("image", "busybox")
                .put("replicas", 2).set("ports", dbPorts));
        String payload = MAPPER.createObjectNode().put("project_name", "proj").set("services", services).toString();

        JobRecord job = coordinatorWithRelay.submitJob("djob-replica-relay", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                payload, 0);

        // task 0 = web, tasks 1-2 = the two database replicas (flattening preserves declaration order).
        assertEquals(3, job.getTaskIds().size());
        JsonNode dbReplica1 = readPayload(job.getTaskIds().get(1));
        JsonNode dbReplica2 = readPayload(job.getTaskIds().get(2));
        assertEquals("database", dbReplica1.path("service_name").asText());
        assertEquals("database", dbReplica2.path("service_name").asText());
        assertTrue(dbReplica1.path("relay_ports").isArray() && dbReplica1.path("relay_ports").size() == 1,
                "the first database replica must independently get relay_ports so it opens its own tunnel");
        assertTrue(dbReplica2.path("relay_ports").isArray() && dbReplica2.path("relay_ports").size() == 1,
                "the second database replica must ALSO independently get relay_ports — this is the actual "
                        + "load-balancing mechanism: each replica registers as its own backend");
    }

    // ── Stage PP: rolling update + rollback ────────────────────────────────────────────────────

    private java.util.concurrent.LinkedBlockingQueue<ControlPlaneProto.ServerTaskCommand> registerDockerNodeWithQueue(
            String nodeId) {
        nodeRegistry.register(nodeId, "10.0.0.1", 50051, nodeId,
                ControlPlaneProto.NodeCapabilities.newBuilder().setDockerAvailable(true).build(), "1.0.0");
        java.util.concurrent.LinkedBlockingQueue<ControlPlaneProto.ServerTaskCommand> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        channelRegistry.register(nodeId, new StreamObserver<>() {
            @Override public void onNext(ControlPlaneProto.ServerTaskCommand value) { queue.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        return queue;
    }

    private static String composeReplicaPayload(String serviceName, int replicas, String image) {
        return MAPPER.createObjectNode().put("project_name", "proj")
                .set("services", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .put("service_name", serviceName).put("image", image).put("replicas", replicas)))
                .toString();
    }

    @Test
    void updateJobReplacesReplicasOneAtATimeNeverMoreThanOneDownAtOnce() throws Exception {
        var d1Commands = registerDockerNodeWithQueue("d1");
        var d2Commands = registerDockerNodeWithQueue("d2");
        var d3Commands = registerDockerNodeWithQueue("d3");

        JobRecord oldJob = coordinator.submitJob("old-job", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeReplicaPayload("web", 3, "busybox:old"), 0);
        assertEquals(3, oldJob.getTaskIds().size());
        // Drain the initial TaskDispatch commands the submit itself already sent, so this test's own
        // polling below only ever sees Stage PP's OWN commands.
        d1Commands.clear();
        d2Commands.clear();
        d3Commands.clear();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> coordinator.updateJob(
                    "new-job", "old-job", composeReplicaPayload("web", 3, "busybox:new")));

            for (int i = 0; i < 3; i++) {
                String oldTaskId = oldJob.getTaskIds().get(i);
                String oldNodeId = taskRegistry.get(oldTaskId).orElseThrow().getAssignedNodeId();
                var oldNodeQueue = queueFor(oldNodeId, d1Commands, d2Commands, d3Commands);

                ControlPlaneProto.ServerTaskCommand cancelCmd = oldNodeQueue.poll(5, TimeUnit.SECONDS);
                assertNotNull(cancelCmd, "replica " + i + "'s old task must be cancelled");
                assertTrue(cancelCmd.hasCancel());
                assertEquals(oldTaskId, cancelCmd.getCancel().getTaskId());

                // The invariant this whole test exists to prove: no LATER old replica has been touched
                // yet — only ever one replica down (mid-swap) at any given moment.
                for (int later = i + 1; later < 3; later++) {
                    String laterOldTaskId = oldJob.getTaskIds().get(later);
                    assertEquals(TaskStateDomain.DISPATCHED,
                            taskRegistry.get(laterOldTaskId).orElseThrow().getState(),
                            "replica " + later + " must still be untouched while replica " + i + " is mid-swap");
                }

                // Simulate the real node confirming it stopped (what DockerComposeServiceExecutor's
                // stoppedByRequest path would really report).
                taskRegistry.markCompleted(oldTaskId, oldNodeId, "{\"stopped_by_request\":true}");

                // The new replica must land on the SAME node — it's the only one that just freed up
                // while the other two old replicas still occupy theirs.
                String newTaskId = "new-job-" + i;
                ControlPlaneProto.ServerTaskCommand dispatchCmd = oldNodeQueue.poll(5, TimeUnit.SECONDS);
                assertNotNull(dispatchCmd, "the replacement replica must be dispatched to the freed node");
                assertTrue(dispatchCmd.hasDispatch());
                assertEquals(newTaskId, dispatchCmd.getDispatch().getTaskId());

                // Let awaitReplicaReady's RUNNING check succeed so the loop proceeds to the next replica.
                taskRegistry.markRunning(newTaskId, oldNodeId);
            }

            JobRecord newJob = future.get(10, TimeUnit.SECONDS);
            assertEquals(3, newJob.getTaskIds().size());
            assertEquals("old-job", newJob.getSupersedesJobId());
            for (String taskId : newJob.getTaskIds()) {
                assertEquals("busybox:new",
                        readPayload(taskId).path("image").asText(), "the new replica must run the NEW image");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static java.util.concurrent.LinkedBlockingQueue<ControlPlaneProto.ServerTaskCommand> queueFor(
            String nodeId,
            java.util.concurrent.LinkedBlockingQueue<ControlPlaneProto.ServerTaskCommand> d1,
            java.util.concurrent.LinkedBlockingQueue<ControlPlaneProto.ServerTaskCommand> d2,
            java.util.concurrent.LinkedBlockingQueue<ControlPlaneProto.ServerTaskCommand> d3) {
        return switch (nodeId) {
            case "d1" -> d1;
            case "d2" -> d2;
            case "d3" -> d3;
            default -> throw new IllegalArgumentException("unknown node " + nodeId);
        };
    }

    @Test
    void rollbackReappliesThePreviousSpecAsAFreshUpdate() throws Exception {
        var d1Commands = registerDockerNodeWithQueue("d1");

        JobRecord oldJob = coordinator.submitJob("orig-job", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeReplicaPayload("web", 1, "busybox:v1"), 0);
        String oldTaskId = oldJob.getTaskIds().get(0);
        d1Commands.clear();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var updateFuture = pool.submit(() -> coordinator.updateJob(
                    "v2-job", "orig-job", composeReplicaPayload("web", 1, "busybox:v2")));

            ControlPlaneProto.ServerTaskCommand cancelCmd = d1Commands.poll(5, TimeUnit.SECONDS);
            assertNotNull(cancelCmd);
            taskRegistry.markCompleted(oldTaskId, "d1", "{}");
            ControlPlaneProto.ServerTaskCommand dispatchCmd = d1Commands.poll(5, TimeUnit.SECONDS);
            assertNotNull(dispatchCmd);
            assertEquals("busybox:v2", MAPPER.readTree(dispatchCmd.getDispatch().getPayloadJson())
                    .path("image").asText());
            taskRegistry.markRunning("v2-job-0", "d1");

            JobRecord v2Job = updateFuture.get(10, TimeUnit.SECONDS);
            assertEquals("orig-job", v2Job.getSupersedesJobId());

            // Now roll back: v2-job's own supersedesJobId ("orig-job") is what gets reconstructed and
            // re-applied — the real proof this isn't just "resubmit whatever the CLI happens to have
            // cached locally," but a genuine server-side reconstruction from stored task specs.
            var rollbackFuture = pool.submit(() -> coordinator.rollbackJob("rollback-job", "v2-job"));

            ControlPlaneProto.ServerTaskCommand rollbackCancelCmd = d1Commands.poll(5, TimeUnit.SECONDS);
            assertNotNull(rollbackCancelCmd);
            assertEquals("v2-job-0", rollbackCancelCmd.getCancel().getTaskId());
            taskRegistry.markCompleted("v2-job-0", "d1", "{}");
            ControlPlaneProto.ServerTaskCommand rollbackDispatchCmd = d1Commands.poll(5, TimeUnit.SECONDS);
            assertNotNull(rollbackDispatchCmd);
            assertEquals("busybox:v1", MAPPER.readTree(rollbackDispatchCmd.getDispatch().getPayloadJson())
                    .path("image").asText(), "rollback must restore the ORIGINAL image, not the v2 one");
            taskRegistry.markRunning("rollback-job-0", "d1");

            JobRecord rolledBack = rollbackFuture.get(10, TimeUnit.SECONDS);
            assertEquals("v2-job", rolledBack.getSupersedesJobId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rollingBackAJobThatWasNeverAnUpdateIsRejectedHonestly() {
        registerDockerNode("solo");
        JobRecord job = coordinator.submitJob("plain-job", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                composeReplicaPayload("web", 1, "busybox"), 0);

        assertThrows(IllegalArgumentException.class, () -> coordinator.rollbackJob("x", job.getJobId()));
    }

    private JsonNode readPayload(String taskId) {
        try {
            return MAPPER.readTree(taskRegistry.get(taskId).orElseThrow().getPayloadJson());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
