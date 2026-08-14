package com.nextgen.agent;

import com.nextgen.agent.task.TaskChannelClient;
import com.nextgen.agent.task.TaskEventSink;
import com.nextgen.agent.task.TaskExecutor;
import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptor;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.proto.ControlPlaneProto.HeartbeatRequest;
import com.nextgen.proto.ControlPlaneProto.HeartbeatResponse;
import com.nextgen.proto.ControlPlaneProto.NodeInfo;
import com.nextgen.proto.ControlPlaneProto.NodeTaskEvent;
import com.nextgen.proto.ControlPlaneProto.RegisterResponse;
import com.nextgen.proto.ControlPlaneProto.ServerTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskDispatch;
import com.nextgen.proto.ControlPlaneProto.TaskKind;
import com.nextgen.proto.ControlPlaneProto.TaskResultEvent;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves NodeAgent's operational paths — registration, the heartbeat loop, and the task channel — all
 * follow a Raft leader-redirect trailer the same way {@code ControlPlaneClientLeaderRedirectTest}
 * already proves for desktop-ui, and separately proves the {@link TaskChannelClient} stale-observer fix:
 * a task still executing when its connection fails over must still deliver its result, to the NEW
 * leader, not lose it by writing to the old, by-then-dead stream.
 *
 * <p>Real loopback TCP servers, not mocks — binds directly to port 0 and reads back the assigned port,
 * the same fix {@code ControlPlaneClientLeaderRedirectTest} already established for the Windows
 * reserve-then-rebind port race.
 */
class NodeAgentRedirectTest {

    private final List<Server> servers = new ArrayList<>();
    private ControlPlaneConnection connectionUnderTest;

    @AfterEach
    void tearDown() throws Exception {
        if (connectionUnderTest != null) {
            connectionUnderTest.shutdown();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ControlPlaneEndpoints.HostPort startServer(ControlPlaneServiceGrpc.ControlPlaneServiceImplBase impl)
            throws Exception {
        Server server = NettyServerBuilder.forPort(0).addService(impl).build().start();
        servers.add(server);
        return new ControlPlaneEndpoints.HostPort("localhost", server.getPort());
    }

    private static Metadata leaderHintTrailers(String hint) {
        Metadata trailers = new Metadata();
        trailers.put(RaftLeaderRedirectInterceptor.LEADER_HINT, hint);
        trailers.put(RaftLeaderRedirectInterceptor.RAFT_TERM, "5");
        return trailers;
    }

    // ── registerWithBackoff ─────────────────────────────────────────────

    @Test
    void registerWithBackoffFollowsALeaderHintImmediatelyWithoutABackoffDelay() throws Exception {
        AtomicInteger leaderCalls = new AtomicInteger();
        ControlPlaneEndpoints.HostPort leader = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void registerNode(NodeInfo request, StreamObserver<RegisterResponse> obs) {
                leaderCalls.incrementAndGet();
                obs.onNext(RegisterResponse.newBuilder().setStatus("OK").setAssignedId(request.getNodeId()).build());
                obs.onCompleted();
            }
        });
        ControlPlaneEndpoints.HostPort follower = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void registerNode(NodeInfo request, StreamObserver<RegisterResponse> obs) {
                obs.onError(Status.UNAVAILABLE.withDescription("NOT_LEADER")
                        .asRuntimeException(leaderHintTrailers("cp-leader=" + leader)));
            }
        });

        connectionUnderTest = new ControlPlaneConnection(
                ControlPlaneEndpoints.single(follower.host(), follower.port()), false, null);
        NodeInfo registration = NodeInfo.newBuilder().setNodeId("agent-1").setIp("127.0.0.1")
                .setPort(follower.port()).setHostname("agent-1").build();
        // A huge backoff — if the redirect consumed a delay slot, this call would take >30s and the
        // test's own timeout would catch it; a genuine discovery must return almost immediately instead.
        BackoffPolicy hugeBackoff = new BackoffPolicy(30_000L, 60_000L, 2.0, 0.0);

        long start = System.currentTimeMillis();
        NodeAgent.registerWithBackoff(connectionUnderTest, registration, hugeBackoff);
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(1, leaderCalls.get());
        assertTrue(elapsedMs < 5_000, "a redirect must not consume a backoff delay slot, took " + elapsedMs + "ms");
    }

    @Test
    void registerWithBackoffAppliesNormalBackoffOnAGenuineTransportFailureThenSucceeds() throws Exception {
        AtomicInteger realCalls = new AtomicInteger();
        ControlPlaneEndpoints.HostPort dead;
        try (ServerSocket probe = new ServerSocket(0)) {
            dead = new ControlPlaneEndpoints.HostPort("localhost", probe.getLocalPort());
        } // closed immediately — guaranteed nothing listens here now
        ControlPlaneEndpoints.HostPort real = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void registerNode(NodeInfo request, StreamObserver<RegisterResponse> obs) {
                realCalls.incrementAndGet();
                obs.onNext(RegisterResponse.newBuilder().setStatus("OK").setAssignedId(request.getNodeId()).build());
                obs.onCompleted();
            }
        });

        connectionUnderTest = new ControlPlaneConnection(
                new ControlPlaneEndpoints(List.of(dead, real)), false, null);
        NodeInfo registration = NodeInfo.newBuilder().setNodeId("agent-1").setIp("127.0.0.1")
                .setPort(real.port()).setHostname("agent-1").build();

        NodeAgent.registerWithBackoff(connectionUnderTest, registration, new BackoffPolicy(50L, 200L, 2.0, 0.0));

        assertEquals(1, realCalls.get(), "onFailure() rotation must still reach the working candidate");
    }

    // ── HeartbeatLoop ────────────────────────────────────────────────────

    @Test
    void heartbeatLoopFollowsALeaderHintImmediately() throws Exception {
        AtomicInteger leaderCalls = new AtomicInteger();
        ControlPlaneEndpoints.HostPort leader = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void sendHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> obs) {
                leaderCalls.incrementAndGet();
                obs.onNext(HeartbeatResponse.newBuilder().setStatus("OK").build());
                obs.onCompleted();
            }
        });
        ControlPlaneEndpoints.HostPort follower = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public void sendHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> obs) {
                obs.onError(Status.UNAVAILABLE.withDescription("NOT_LEADER")
                        .asRuntimeException(leaderHintTrailers("cp-leader=" + leader)));
            }
        });

        connectionUnderTest = new ControlPlaneConnection(
                ControlPlaneEndpoints.single(follower.host(), follower.port()), false, null);
        NodeInfo registration = NodeInfo.newBuilder().setNodeId("agent-1").setIp("127.0.0.1")
                .setPort(follower.port()).setHostname("agent-1").build();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-heartbeat-scheduler");
            t.setDaemon(true);
            return t;
        });
        try {
            NodeAgent.HeartbeatLoop loop = new NodeAgent.HeartbeatLoop(
                    scheduler, connectionUnderTest, registration, new SystemMetricsReader(),
                    new PowerMetricsReader(), BackoffPolicy.defaultPolicy(), 2_000);

            long nextDelay = loop.sendOneHeartbeat();

            assertEquals(1, leaderCalls.get());
            assertTrue(nextDelay > 0);
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ── TaskChannelClient ────────────────────────────────────────────────

    @Test
    void taskChannelClientOpensOnTheHintedLeaderWhenTheFirstCandidateRedirects() throws Exception {
        LinkedBlockingQueue<String> leaderHellos = new LinkedBlockingQueue<>();
        ControlPlaneEndpoints.HostPort leader = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public StreamObserver<NodeTaskEvent> taskChannel(StreamObserver<ServerTaskCommand> responseObserver) {
                return new StreamObserver<>() {
                    @Override
                    public void onNext(NodeTaskEvent event) {
                        if (event.hasHello()) {
                            leaderHellos.add(event.getHello().getNodeId());
                        }
                    }
                    @Override public void onError(Throwable t) { }
                    @Override public void onCompleted() { }
                };
            }
        });
        ControlPlaneEndpoints.HostPort follower = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public StreamObserver<NodeTaskEvent> taskChannel(StreamObserver<ServerTaskCommand> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("NOT_LEADER")
                        .asRuntimeException(leaderHintTrailers("cp-leader=" + leader)));
                return new StreamObserver<>() {
                    @Override public void onNext(NodeTaskEvent event) { }
                    @Override public void onError(Throwable t) { }
                    @Override public void onCompleted() { }
                };
            }
        });

        connectionUnderTest = new ControlPlaneConnection(
                ControlPlaneEndpoints.single(follower.host(), follower.port()), false, null);
        TaskChannelClient client = new TaskChannelClient(
                connectionUnderTest, "agent-1", Map.of(), BackoffPolicy.defaultPolicy());
        client.start();
        try {
            String hello = leaderHellos.poll(5, TimeUnit.SECONDS);
            assertEquals("agent-1", hello,
                    "the task channel must reconnect to the hinted leader, not stay on the follower");
        } finally {
            client.shutdown();
        }
    }

    /**
     * The headline regression test for the stale-observer bug: a task still executing when the control
     * plane it was dispatched from fails over must still deliver its eventual result — to the NEW
     * leader, not lost by writing to the old, by-then-dead stream. Before the fix, {@code runDispatch}
     * captured {@code outbound} once, before {@code executor.execute(...)} ran; this test forces a
     * reconnect to happen WHILE execution is still blocked, so only the fixed (read-fresh-at-send-time)
     * behavior can pass it.
     */
    @Test
    void aTaskStillRunningThroughAMidTaskLeaderFailoverStillDeliversItsResultToTheNewLeader() throws Exception {
        LinkedBlockingQueue<String> newLeaderHellos = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<TaskResultEvent> newLeaderResults = new LinkedBlockingQueue<>();
        ControlPlaneEndpoints.HostPort newLeader = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public StreamObserver<NodeTaskEvent> taskChannel(StreamObserver<ServerTaskCommand> responseObserver) {
                return new StreamObserver<>() {
                    @Override
                    public void onNext(NodeTaskEvent event) {
                        if (event.hasHello()) {
                            newLeaderHellos.add(event.getHello().getNodeId());
                        } else if (event.hasResult()) {
                            newLeaderResults.add(event.getResult());
                        }
                    }
                    @Override public void onError(Throwable t) { }
                    @Override public void onCompleted() { }
                };
            }
        });

        AtomicReference<StreamObserver<ServerTaskCommand>> oldLeaderResponseObserver = new AtomicReference<>();
        ControlPlaneEndpoints.HostPort oldLeader = startServer(new ControlPlaneServiceGrpc.ControlPlaneServiceImplBase() {
            @Override
            public StreamObserver<NodeTaskEvent> taskChannel(StreamObserver<ServerTaskCommand> responseObserver) {
                oldLeaderResponseObserver.set(responseObserver);
                return new StreamObserver<>() {
                    @Override
                    public void onNext(NodeTaskEvent event) {
                        if (event.hasHello()) {
                            // Immediately dispatch a task, exactly like a real control plane would.
                            responseObserver.onNext(ServerTaskCommand.newBuilder()
                                    .setDispatch(TaskDispatch.newBuilder()
                                            .setTaskId("t1")
                                            .setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                                            .setPayloadJson("{}")
                                            .build())
                                    .build());
                        }
                    }
                    @Override public void onError(Throwable t) { }
                    @Override public void onCompleted() { }
                };
            }
        });

        connectionUnderTest = new ControlPlaneConnection(
                ControlPlaneEndpoints.single(oldLeader.host(), oldLeader.port()), false, null);

        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        TaskExecutor blockingExecutor = new TaskExecutor() {
            @Override public TaskKindDomain kind() {
                return TaskKindDomain.PRIME_COUNT_RANGE;
            }
            @Override public String execute(String taskId, String payloadJson, TaskEventSink events) throws Exception {
                executionStarted.countDown();
                assertTrue(releaseExecution.await(10, TimeUnit.SECONDS), "test never released the executor");
                return "{\"prime_count\":42}";
            }
        };

        TaskChannelClient client = new TaskChannelClient(connectionUnderTest, "agent-1",
                Map.of(TaskKindDomain.PRIME_COUNT_RANGE, blockingExecutor), BackoffPolicy.defaultPolicy());
        client.start();
        try {
            assertTrue(executionStarted.await(5, TimeUnit.SECONDS), "the dispatched task never started executing");

            // The task is now executing, still connected to oldLeader. Fail the connection over to
            // newLeader WHILE it's still running — exactly the Raft leader-failover scenario.
            StreamObserver<ServerTaskCommand> oldObserver = oldLeaderResponseObserver.get();
            assertNotNull(oldObserver);
            oldObserver.onError(Status.UNAVAILABLE.withDescription("NOT_LEADER")
                    .asRuntimeException(leaderHintTrailers("cp-new=" + newLeader)));

            assertEquals("agent-1", newLeaderHellos.poll(5, TimeUnit.SECONDS),
                    "the task channel must have reconnected to the new leader while the task was still running");

            // Only now does the still-running task finish — after the reconnect already happened.
            releaseExecution.countDown();

            TaskResultEvent result = newLeaderResults.poll(5, TimeUnit.SECONDS);
            assertNotNull(result, "the result must reach the NEW leader, not be lost on the old, dead stream");
            assertEquals("t1", result.getTaskId());
            assertTrue(result.getSuccess());
            assertEquals("{\"prime_count\":42}", result.getResultJson());
        } finally {
            client.shutdown();
        }
    }
}
