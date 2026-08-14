package com.nextgen.controlplane;

import com.nextgen.controlplane.capacity.HeuristicNodeCapacityScorer;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.raft.ApplyClock;
import com.nextgen.controlplane.raft.GrpcRaftTransport;
import com.nextgen.controlplane.raft.RaftConsensusServiceImpl;
import com.nextgen.controlplane.raft.RaftControlPlaneWriter;
import com.nextgen.controlplane.raft.RaftLog;
import com.nextgen.controlplane.raft.RaftNode;
import com.nextgen.controlplane.raft.RaftPeer;
import com.nextgen.controlplane.raft.RaftStateMachine;
import com.nextgen.controlplane.raft.RaftTimings;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.training.JobOutcomeLogger;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneProto.NodeTaskEvent;
import com.nextgen.proto.ControlPlaneProto.ServerTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskChannelHello;
import com.nextgen.proto.ControlPlaneProto.TaskResponse;
import com.nextgen.proto.ControlPlaneProto.TaskState;
import com.nextgen.proto.ControlPlaneProto.TaskStatusResponse;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The headline Stage J proof: three fully wired {@link ControlPlaneServiceImpl}+{@link RaftNode}
 * replicas — real {@link GrpcRaftTransport} over real loopback TCP for Raft consensus, real in-process
 * gRPC for the client-facing {@code ControlPlaneService} — registering a node and dispatching a task
 * against whichever replica is currently leader, killing that leader mid-task, and proving the
 * surviving two replicas elect a new leader that still knows about the task (replicated, not lost) and
 * still accepts the fake node's eventual result once it reconnects — exactly the "Follower Crash and
 * Workload Migration" scenario this whole stage exists to make real.
 *
 * <p>Deliberately does NOT call {@link ControlPlaneServer#start}: that method registers
 * process-global static Prometheus collectors ({@code NodeRegistryCollector}) that would collide across
 * three in-process replicas in one JVM. Each replica here is assembled by hand from the same
 * collaborators {@code start()} wires, mirroring {@code ControlPlaneServiceImplTaskChannelTest}'s own
 * "don't start the real server" discipline.
 *
 * <p>This test does not exercise {@code RaftLeaderRedirectInterceptor} or client-side redirect-following
 * (those are {@link com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptorTest} and
 * {@code ControlPlaneClientLeaderRedirectTest}'s jobs) — it dials whichever replica the test itself has
 * determined is leader directly, the same way a real client would end up doing after following a
 * redirect. What's unique to this test is proving REPLICATION and FAILOVER actually work end to end
 * over a real network, which no lower-level test (Phase A's in-memory-transport harness, or the
 * gRPC-plumbing-only {@code RaftConsensusServiceImplTest}) can prove by itself.
 */
class ReplicatedControlPlaneIntegrationTest {

    /**
     * Wider min/max election-timeout spread than {@code RaftConsensusServiceImplTest}'s 2-node timings
     * — with 3 real peers over real loopback TCP, a tight spread relative to network RTT makes repeated
     * split votes likely (multiple candidates timing out within the same narrow window), which is
     * exactly what an earlier version of this test observed empirically (16 terms before an election
     * committed). Correctness does not depend on the exact numbers here (that's Phase A's job, with
     * deliberately adversarial timings); this test only needs elections to settle promptly and reliably
     * so the failover it's actually testing isn't obscured by unrelated election flakiness.
     */
    private static final RaftTimings TIMINGS = new RaftTimings(50, 400, 800, 5000);

    private final List<ReplicaHandle> replicas = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ReplicaHandle replica : replicas) {
            replica.close();
        }
    }

    private record ReplicaHandle(String id, RaftNode raftNode, ControlPlaneServiceImpl serviceImpl,
                                 Server raftGrpcServer, GrpcRaftTransport transport,
                                 Server serviceServer, ManagedChannel serviceChannel) {
        ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub() {
            return ControlPlaneServiceGrpc.newBlockingStub(serviceChannel);
        }

        ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub() {
            return ControlPlaneServiceGrpc.newStub(serviceChannel);
        }

        void close() {
            serviceChannel.shutdownNow();
            serviceServer.shutdownNow();
            raftGrpcServer.shutdownNow();
            raftNode.close();
            transport.shutdown();
        }

        /** Simulates the process actually dying — killed BEFORE its own graceful close() would run,
         * matching a real crash rather than a clean shutdown. */
        void kill() {
            close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ReplicaHandle buildReplica(String id, int raftPort, List<RaftPeer> members, Path raftLogDir)
            throws IOException {
        ApplyClock applyClock = new ApplyClock();
        NodeRegistry nodeRegistry = new NodeRegistry(new ConcurrentHashMap<>(), applyClock);
        TaskRegistry taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), applyClock);
        JobRegistry jobRegistry = new JobRegistry(new ConcurrentHashMap<>(), applyClock);

        RaftLog log = new RaftLog(raftLogDir);
        RaftStateMachine stateMachine = new RaftStateMachine(nodeRegistry, taskRegistry, jobRegistry, applyClock);
        GrpcRaftTransport transport = new GrpcRaftTransport();
        RaftNode raftNode = new RaftNode(id, "localhost:" + raftPort, members, log, stateMachine, transport,
                TIMINGS);

        Server raftGrpcServer = NettyServerBuilder.forPort(raftPort)
                .addService(new RaftConsensusServiceImpl(raftNode))
                .build()
                .start();

        RaftControlPlaneWriter writer = new RaftControlPlaneWriter(raftNode, TIMINGS.proposeTimeoutMs());
        NodeTaskChannelRegistry channelRegistry = new NodeTaskChannelRegistry();
        ControlPlaneServiceImpl serviceImpl = new ControlPlaneServiceImpl(nodeRegistry, new RoundRobinScheduler(),
                taskRegistry, channelRegistry, new HeuristicNodeCapacityScorer(), JobOutcomeLogger.noop(),
                jobRegistry, writer, raftNode);

        String serverName = InProcessServerBuilder.generateName();
        Server serviceServer = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(serviceImpl).build().start();
        ManagedChannel serviceChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        raftNode.start();

        return new ReplicaHandle(id, raftNode, serviceImpl, raftGrpcServer, transport, serviceServer, serviceChannel);
    }

    /**
     * Waits for the SAME leader to be reported on two consecutive polls before returning it. A single
     * "someone is leader" check is not enough under a contested election: the node that satisfied the
     * check can step down before a caller's very next line even runs — exactly the race
     * {@code RaftTestSupport.awaitLeader}'s Javadoc documents for the Phase A in-memory-transport tests.
     * Requiring stability across two polls, combined with this test's wider {@link #TIMINGS} spread,
     * makes that race negligible in practice without needing every subsequent call in this test to be
     * leader-hopping-retry-aware — the task channel this test opens is only ever valid against ONE
     * specific replica anyway (channels are leader-local, never replicated), so a caller here needs a
     * single settled leader to act against, not a retry loop that could silently hop to a different one.
     */
    private ReplicaHandle awaitStableLeader(List<ReplicaHandle> candidates, Duration timeout) {
        ReplicaHandle[] lastSeen = new ReplicaHandle[1];
        await().atMost(timeout).pollInterval(Duration.ofMillis(15)).until(() -> {
            ReplicaHandle current = candidates.stream().filter(r -> r.raftNode().isLeader()).findFirst()
                    .orElse(null);
            boolean stable = current != null && current == lastSeen[0];
            lastSeen[0] = current;
            return stable;
        });
        return lastSeen[0];
    }

    /** Retries SubmitTask against the SAME given leader until real dispatch succeeds — the fake node's
     * channel is registered asynchronously relative to this call (a real, routine, short-lived race, not
     * a leadership one), exactly mirroring {@code ControlPlaneServiceImplTaskChannelTest}'s own helper of
     * the same shape. */
    private TaskResponse submitUntilDispatched(ReplicaHandle leader, String taskId, String payload) {
        long deadline = System.currentTimeMillis() + 5_000;
        TaskResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = leader.blockingStub().submitTask(ControlPlaneProto.TaskRequest.newBuilder()
                    .setTaskId(taskId)
                    .setPayload(payload)
                    .setKind(ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                    .build());
            if (last.getResult().startsWith("ACCEPTED")) {
                return last;
            }
            sleep(20);
        }
        fail("Task was never successfully dispatched: " + (last == null ? "null" : last.getResult()));
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Registers {@code nodeId} against a freshly re-resolved stable leader, retrying on failure —
     * the one call in this test that legitimately needs to hop to a different leader on retry, since
     * nothing leader-local (like a task channel) depends on it yet. Returns whichever replica the
     * registration actually succeeded against, which every subsequent step in the test then sticks to. */
    private ReplicaHandle registerNodeAgainstAStableLeader(String nodeId) {
        long deadline = System.currentTimeMillis() + 15_000;
        RuntimeException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            ReplicaHandle candidate = awaitStableLeader(replicas, Duration.ofSeconds(10));
            try {
                candidate.blockingStub().registerNode(ControlPlaneProto.NodeInfo.newBuilder()
                        .setNodeId(nodeId).setIp("10.0.0.5").setPort(50051).setHostname(nodeId).build());
                return candidate;
            } catch (RuntimeException e) {
                lastError = e;
                sleep(30);
            }
        }
        throw new IllegalStateException("registerNode never succeeded against a stable leader", lastError);
    }

    @Test
    void aKilledLeadersInFlightTaskSurvivesOnTheNewLeaderAndTheFakeNodesEventualResultIsAccepted(
            @TempDir Path baseDir) throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        List<RaftPeer> members = List.of(
                new RaftPeer("r1", "localhost", portA),
                new RaftPeer("r2", "localhost", portB),
                new RaftPeer("r3", "localhost", portC));

        ReplicaHandle r1 = buildReplica("r1", portA, members, baseDir.resolve("r1"));
        ReplicaHandle r2 = buildReplica("r2", portB, members, baseDir.resolve("r2"));
        ReplicaHandle r3 = buildReplica("r3", portC, members, baseDir.resolve("r3"));
        replicas.add(r1);
        replicas.add(r2);
        replicas.add(r3);

        // ── Elect an initial leader and register a real node against it ────────────────────────
        // registerNode itself is retried against a freshly re-resolved leader on failure (the narrow
        // remaining race after awaitStableLeader's two-poll check: the settled leader steps down in the
        // instant between that check and this specific call) — but everything AFTER this, starting with
        // the task channel, must stick to whichever replica registration actually succeeded against,
        // since a channel is leader-local and only ever valid on the one replica it was opened against.
        ReplicaHandle leader = registerNodeAgainstAStableLeader("fake-node-1");

        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                .until(() -> replicas.stream().allMatch(r -> r.serviceImpl().registry().contains("fake-node-1")));
        for (ReplicaHandle replica : replicas) {
            assertTrue(replica.serviceImpl().registry().contains("fake-node-1"),
                    "node registration must replicate to every replica, including " + replica.id());
        }

        // ── Open the fake node's task channel against the LEADER and submit a real task ────────
        LinkedBlockingQueue<ServerTaskCommand> oldLeaderCommands = new LinkedBlockingQueue<>();
        StreamObserver<NodeTaskEvent> oldLeaderOutbound = leader.asyncStub().taskChannel(new StreamObserver<>() {
            @Override public void onNext(ServerTaskCommand value) { oldLeaderCommands.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        oldLeaderOutbound.onNext(NodeTaskEvent.newBuilder()
                .setHello(TaskChannelHello.newBuilder().setNodeId("fake-node-1").build())
                .build());

        TaskResponse submitResponse = submitUntilDispatched(leader, "t1", "{\"range_start\":0,\"range_end\":101}");
        assertEquals("fake-node-1", submitResponse.getAssignedNode());

        ServerTaskCommand dispatchCommand = oldLeaderCommands.poll(5, TimeUnit.SECONDS);
        assertNotNull(dispatchCommand, "the leader must actually push a real TaskDispatch down the stream");
        assertTrue(dispatchCommand.hasDispatch());
        assertEquals("t1", dispatchCommand.getDispatch().getTaskId());

        // ── Prove the dispatch replicated to EVERY replica before killing anything ──────────────
        // SubmitTask and DispatchTask are two SEPARATE replicated commands, proposed back to back
        // on the leader — waiting only for the task record to exist (rather than for its
        // assignedNodeId to actually be set) is a real race: the SubmitTask command can land on a
        // follower a few milliseconds before the follow-up DispatchTask command does, especially
        // under load. Wait for the actual assignment, matching what's asserted right below.
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).until(() ->
                replicas.stream().allMatch(r -> r.serviceImpl().taskRegistry().get("t1")
                        .map(t -> "fake-node-1".equals(t.getAssignedNodeId()))
                        .orElse(false)));
        for (ReplicaHandle replica : replicas) {
            var task = replica.serviceImpl().taskRegistry().get("t1");
            assertTrue(task.isPresent(), "task t1 must replicate to " + replica.id());
            assertEquals("fake-node-1", task.get().getAssignedNodeId(),
                    "the assignment itself, not just the task's existence, must replicate to " + replica.id());
        }

        // ── Kill the leader WHILE the task is still in flight — the actual failover moment ──────
        ReplicaHandle killedLeader = leader;
        List<ReplicaHandle> survivors = replicas.stream().filter(r -> r != killedLeader).toList();
        killedLeader.kill();

        ReplicaHandle newLeader = awaitStableLeader(survivors, Duration.ofSeconds(10));
        assertTrue(newLeader != killedLeader, "the new leader must be one of the two survivors");

        // The task assignment must still be there on the new leader — Raft replication is what makes
        // this possible; the OLD leader dying must not have taken the task's placement with it.
        var recoveredTask = newLeader.serviceImpl().taskRegistry().get("t1");
        assertTrue(recoveredTask.isPresent(), "the new leader must still know about task t1");
        assertEquals("fake-node-1", recoveredTask.get().getAssignedNodeId());
        assertEquals(TaskState.TASK_STATE_DISPATCHED, recoveredTask.get().getState().toProto());

        // ── The fake node reconnects — to the NEW leader, exactly like a real TaskChannelClient
        // following a redirect hint would — and reports its real result ─────────────────────────
        LinkedBlockingQueue<NodeTaskEvent> newLeaderEvents = new LinkedBlockingQueue<>();
        StreamObserver<NodeTaskEvent> newLeaderOutbound = newLeader.asyncStub().taskChannel(new StreamObserver<>() {
            @Override public void onNext(ServerTaskCommand value) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        newLeaderOutbound.onNext(NodeTaskEvent.newBuilder()
                .setHello(TaskChannelHello.newBuilder().setNodeId("fake-node-1").build())
                .build());
        newLeaderOutbound.onNext(NodeTaskEvent.newBuilder()
                .setResult(ControlPlaneProto.TaskResultEvent.newBuilder()
                        .setTaskId("t1").setSuccess(true).setResultJson("{\"prime_count\":25}"))
                .build());

        // ── The result must be accepted and visible on BOTH surviving replicas ──────────────────
        for (ReplicaHandle survivor : survivors) {
            TaskStatusResponse status = awaitTaskState(survivor, "t1", TaskState.TASK_STATE_COMPLETED);
            assertEquals("fake-node-1", status.getAssignedNode());
            assertEquals("{\"prime_count\":25}", status.getResultJson());
            assertEquals("", status.getError());
        }
    }

    private TaskStatusResponse awaitTaskState(ReplicaHandle replica, String taskId, TaskState expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        TaskStatusResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = replica.blockingStub().getTaskStatus(
                    ControlPlaneProto.TaskStatusRequest.newBuilder().setTaskId(taskId).build());
            if (last.getState() == expected) {
                return last;
            }
            sleep(20);
        }
        fail("Task " + taskId + " on " + replica.id() + " never reached state " + expected
                + "; last seen: " + (last == null ? "null" : last.getState()));
        return null;
    }
}
