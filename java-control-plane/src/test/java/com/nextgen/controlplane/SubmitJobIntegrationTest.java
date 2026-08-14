package com.nextgen.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.agent.task.PrimeRangeCounterExecutor;
import com.nextgen.agent.task.TaskEventSink;
import com.nextgen.controlplane.task.NodeTaskChannelRegistry;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.proto.ControlPlaneProto.Empty;
import com.nextgen.proto.ControlPlaneProto.HeartbeatRequest;
import com.nextgen.proto.ControlPlaneProto.JobList;
import com.nextgen.proto.ControlPlaneProto.JobRequest;
import com.nextgen.proto.ControlPlaneProto.JobState;
import com.nextgen.proto.ControlPlaneProto.JobStatusRequest;
import com.nextgen.proto.ControlPlaneProto.JobStatusResponse;
import com.nextgen.proto.ControlPlaneProto.JobSubmitResponse;
import com.nextgen.proto.ControlPlaneProto.NodeInfo;
import com.nextgen.proto.ControlPlaneProto.NodeTaskEvent;
import com.nextgen.proto.ControlPlaneProto.ServerTaskCommand;
import com.nextgen.proto.ControlPlaneProto.TaskChannelHello;
import com.nextgen.proto.ControlPlaneProto.TaskDispatch;
import com.nextgen.proto.ControlPlaneProto.TaskKind;
import com.nextgen.proto.ControlPlaneProto.TaskResultEvent;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves a real job — split across several fake nodes over real in-process gRPC TaskChannel streams —
 * reduces to the mathematically correct total, not just "the pipeline ran without throwing." Each fake
 * node "executes" its sub-task with the SAME {@link PrimeRangeCounterExecutor} a real
 * {@code TaskChannelClient} would use, so the final combined count is independently checkable against
 * a known π(x) value end to end, through the real split/dispatch/collect/reduce path.
 */
class SubmitJobIntegrationTest {

    private Server server;
    private ManagedChannel channel;
    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub;
    private ControlPlaneServiceGrpc.ControlPlaneServiceStub asyncStub;
    private final PrimeRangeCounterExecutor primeExecutor = new PrimeRangeCounterExecutor();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TaskEventSink NO_OP_SINK = (line, stderr) -> { };

    private final Map<String, LinkedBlockingQueue<ServerTaskCommand>> nodeCommands = new ConcurrentHashMap<>();
    private final Map<String, StreamObserver<NodeTaskEvent>> nodeOutbound = new ConcurrentHashMap<>();
    /** Range size of the most recent TaskDispatch each node received — for capacity-split assertions. */
    private final Map<String, Long> lastDispatchedRangeSizeByNode = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(new ControlPlaneServiceImpl(
                        new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis),
                        new RoundRobinScheduler(),
                        new TaskRegistry(),
                        new NodeTaskChannelRegistry()))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        blockingStub = ControlPlaneServiceGrpc.newBlockingStub(channel);
        asyncStub = ControlPlaneServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void registerAliveNode(String nodeId) {
        registerAliveNode(nodeId, com.nextgen.proto.ControlPlaneProto.NodeCapabilities.getDefaultInstance());
    }

    /** Same as {@link #registerAliveNode(String)} but with declared capabilities, for capacity-split tests. */
    private void registerAliveNode(String nodeId, int cpuCores, long totalMemoryBytes) {
        registerAliveNode(nodeId, com.nextgen.proto.ControlPlaneProto.NodeCapabilities.newBuilder()
                .setCpuCores(cpuCores).setTotalMemoryBytes(totalMemoryBytes).build());
    }

    private void registerAliveNode(String nodeId, com.nextgen.proto.ControlPlaneProto.NodeCapabilities capabilities) {
        blockingStub.registerNode(NodeInfo.newBuilder()
                .setNodeId(nodeId).setIp("10.0.0.1").setPort(50051).setHostname(nodeId)
                .setCapabilities(capabilities).build());
        blockingStub.sendHeartbeat(HeartbeatRequest.newBuilder()
                .setNodeId(nodeId).setCpu(10f).setCpuAvailable(true).setMemory(10f).setMemoryAvailable(true)
                .build());

        LinkedBlockingQueue<ServerTaskCommand> incoming = new LinkedBlockingQueue<>();
        nodeCommands.put(nodeId, incoming);
        StreamObserver<NodeTaskEvent> outbound = asyncStub.taskChannel(new StreamObserver<>() {
            @Override public void onNext(ServerTaskCommand value) { incoming.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
        nodeOutbound.put(nodeId, outbound);
        outbound.onNext(NodeTaskEvent.newBuilder()
                .setHello(TaskChannelHello.newBuilder().setNodeId(nodeId).build())
                .build());
    }

    /**
     * Drains every node's incoming command queue, executing each TaskDispatch for real and reporting
     * the real result back — looping until no node has anything left, so a JobCoordinator-triggered
     * retry (redispatched to another node's queue) is also picked up and executed.
     */
    private void executeAllPendingDispatches() throws Exception {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Map.Entry<String, LinkedBlockingQueue<ServerTaskCommand>> entry : nodeCommands.entrySet()) {
                ServerTaskCommand command = entry.getValue().poll();
                if (command == null) {
                    continue;
                }
                progressed = true;
                assertTrue(command.hasDispatch());
                TaskDispatch dispatch = command.getDispatch();
                StreamObserver<NodeTaskEvent> outbound = nodeOutbound.get(entry.getKey());
                recordDispatchedRangeSize(entry.getKey(), dispatch.getPayloadJson());

                String resultJson = "";
                boolean success;
                String error = "";
                try {
                    resultJson = primeExecutor.execute(dispatch.getTaskId(), dispatch.getPayloadJson(), NO_OP_SINK);
                    success = true;
                } catch (Exception e) {
                    success = false;
                    error = e.getMessage();
                }

                outbound.onNext(NodeTaskEvent.newBuilder()
                        .setResult(TaskResultEvent.newBuilder()
                                .setTaskId(dispatch.getTaskId())
                                .setSuccess(success)
                                .setResultJson(resultJson)
                                .setError(error))
                        .build());
            }
        }
    }

    private void recordDispatchedRangeSize(String nodeId, String payloadJson) {
        try {
            JsonNode payload = MAPPER.readTree(payloadJson);
            long size = payload.get("range_end").asLong() - payload.get("range_start").asLong();
            lastDispatchedRangeSizeByNode.put(nodeId, size);
        } catch (Exception ignored) {
            // Not every dispatch in this test is expected to be inspectable this way; best-effort only.
        }
    }

    private JobStatusResponse awaitJobState(String jobId, JobState expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        JobStatusResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = blockingStub.getJobStatus(JobStatusRequest.newBuilder().setJobId(jobId).build());
            if (last.getState() == expected) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("Job " + jobId + " never reached " + expected + "; last seen "
                + (last == null ? "null" : last.getState()));
        return null;
    }

    @Test
    void aRealJobSplitAcrossThreeNodesReducesToTheMathematicallyCorrectTotal() throws Exception {
        registerAliveNode("node1");
        registerAliveNode("node2");
        registerAliveNode("node3");

        // pi(1000) = 168 — a known, independently-checkable total, split three ways.
        JobSubmitResponse submitResponse = blockingStub.submitJob(JobRequest.newBuilder()
                .setJobId("job-1000")
                .setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .setPayloadJson("{\"range_start\":0,\"range_end\":1000}")
                .setSubTaskCount(3)
                .build());

        assertTrue(submitResponse.getResult().startsWith("ACCEPTED"), submitResponse.getResult());
        assertEquals(3, submitResponse.getSubTaskCount());

        executeAllPendingDispatches();

        JobStatusResponse finalStatus = awaitJobState("job-1000", JobState.JOB_STATE_COMPLETED);
        assertEquals(3, finalStatus.getCompletedCount());
        assertEquals(0, finalStatus.getFailedCount());
        assertTrue(finalStatus.getCombinedResultJson().contains("\"prime_count\":168"),
                finalStatus.getCombinedResultJson());
        assertEquals(3, finalStatus.getTaskIdsCount());

        JobList list = blockingStub.listJobs(Empty.newBuilder().build());
        assertTrue(list.getJobsList().stream().anyMatch(j -> j.getJobId().equals("job-1000")));
    }

    @Test
    void aJobWithMoreSubTasksThanNodesStillConvergesCorrectly() throws Exception {
        registerAliveNode("node1");
        registerAliveNode("node2");

        // pi(500) = 95, split into 5 sub-tasks across only 2 nodes — some nodes get more than one.
        blockingStub.submitJob(JobRequest.newBuilder()
                .setJobId("job-500")
                .setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .setPayloadJson("{\"range_start\":0,\"range_end\":500}")
                .setSubTaskCount(5)
                .build());

        executeAllPendingDispatches();

        JobStatusResponse finalStatus = awaitJobState("job-500", JobState.JOB_STATE_COMPLETED);
        assertEquals(5, finalStatus.getCompletedCount());
        assertTrue(finalStatus.getCombinedResultJson().contains("\"prime_count\":95"),
                finalStatus.getCombinedResultJson());
    }

    @Test
    void aRealJobOverMismatchedNodesGivesTheMoreCapableNodeTheLargerShare() throws Exception {
        // Weight = cpuCores*1.0 + memoryGb*0.25 → weak=4+8*0.25=6, strong=8+16*0.25=12 — a clean 1:2
        // ratio, so a 1200-unit range splits exactly 400/800 with zero remainder, real gRPC end to end.
        registerAliveNode("weak", 4, 8_000_000_000L);
        registerAliveNode("strong", 8, 16_000_000_000L);

        blockingStub.submitJob(JobRequest.newBuilder()
                .setJobId("job-capacity")
                .setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .setPayloadJson("{\"range_start\":0,\"range_end\":1200}")
                .setSubTaskCount(2)
                .build());

        executeAllPendingDispatches();
        JobStatusResponse finalStatus = awaitJobState("job-capacity", JobState.JOB_STATE_COMPLETED);
        assertEquals(2, finalStatus.getCompletedCount());

        assertEquals(400L, lastDispatchedRangeSizeByNode.get("weak"));
        assertEquals(800L, lastDispatchedRangeSizeByNode.get("strong"));
    }

    @Test
    void getJobStatusOnAnUnknownJobIsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                blockingStub.getJobStatus(JobStatusRequest.newBuilder().setJobId("ghost").build()));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }

    @Test
    void aJobRejectedForBadInputNeverAppearsInGetJobStatus() {
        JobSubmitResponse response = blockingStub.submitJob(JobRequest.newBuilder()
                .setJobId("bad-job")
                .setKind(TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .setPayloadJson("{\"range_start\":100,\"range_end\":0}")
                .setSubTaskCount(3)
                .build());

        assertTrue(response.getResult().startsWith("FAILED"), response.getResult());
        assertThrows(StatusRuntimeException.class, () ->
                blockingStub.getJobStatus(JobStatusRequest.newBuilder().setJobId("bad-job").build()));
    }
}
