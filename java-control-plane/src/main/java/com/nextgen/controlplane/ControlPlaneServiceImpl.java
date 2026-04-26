package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto.*;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import com.nextgen.proto.PredictorServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC service implementation for the ControlPlane.
 * Implements all 4 RPCs: RegisterNode, SendHeartbeat, SubmitTask, GetNodes.
 */
public class ControlPlaneServiceImpl extends ControlPlaneServiceGrpc.ControlPlaneServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneServiceImpl.class);

    // ── Node Registry ────────────────────────────────
    private final ConcurrentHashMap<String, NodeRecord> registry;

    // ── Round-Robin Scheduler ────────────────────────
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    // ── Predictor gRPC Channel ──────────────────────
    private PredictorServiceGrpc.PredictorServiceBlockingStub predictorStub;
    private volatile boolean predictorInitialized = false;

    // ── Prometheus Metrics ──────────────────────────
    private static final Counter REGISTRATIONS = Counter.build()
            .name("controlplane_registrations_total")
            .help("Total node registrations")
            .register();

    private static final Counter HEARTBEATS = Counter.build()
            .name("controlplane_heartbeats_total")
            .help("Total heartbeats received")
            .labelNames("node_id")
            .register();

    private static final Counter TASKS_SUBMITTED = Counter.build()
            .name("controlplane_tasks_submitted_total")
            .help("Total tasks submitted")
            .register();

    private static final Gauge ACTIVE_NODES = Gauge.build()
            .name("controlplane_active_nodes")
            .help("Number of currently active nodes")
            .register();

    public ControlPlaneServiceImpl(ConcurrentHashMap<String, NodeRecord> registry) {
        this.registry = registry;
        // Predictor connection is lazy - initialized on first use
    }

    private synchronized PredictorServiceGrpc.PredictorServiceBlockingStub getPredictorStub() {
        if (!predictorInitialized) {
            String predictorHost = System.getenv().getOrDefault("PREDICTOR_HOST", "predictor");
            try {
                ManagedChannel channel = ManagedChannelBuilder
                        .forAddress(predictorHost, 50052)
                        .usePlaintext()
                        .build();
                predictorStub = PredictorServiceGrpc.newBlockingStub(channel);
                LOG.info("Predictor stub configured → {}:50052", predictorHost);
            } catch (Exception e) {
                LOG.warn("Could not connect to predictor at {}:50052 - {}", predictorHost, e.getMessage());
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

        NodeRecord record = new NodeRecord(id, request.getIp(), request.getPort(), request.getHostname());
        registry.put(id, record);
        REGISTRATIONS.inc();
        ACTIVE_NODES.set(registry.size());

        RegisterResponse response = RegisterResponse.newBuilder()
                .setStatus("REGISTERED")
                .setAssignedId(id)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();

        LOG.info("✅ Node '{}' registered successfully. Total nodes: {}", id, registry.size());
    }

    // ── SendHeartbeat ────────────────────────────────
    @Override
    public void sendHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        String id = request.getNodeId();
        NodeRecord record = registry.get(id);

        if (record == null) {
            LOG.warn("⚠ Heartbeat from unknown node '{}' — ignored", id);
            responseObserver.onNext(HeartbeatResponse.newBuilder().setStatus("UNKNOWN_NODE").build());
            responseObserver.onCompleted();
            return;
        }

        record.setCpuUsage(request.getCpu());
        record.setMemoryUsage(request.getMemory());
        record.setLastHeartbeatMillis(System.currentTimeMillis());
        record.setStatus("ALIVE");
        HEARTBEATS.labels(id).inc();

        LOG.debug("💓 Heartbeat: node={}, cpu={}%, mem={}%", id,
                String.format("%.1f", request.getCpu()), String.format("%.1f", request.getMemory()));

        responseObserver.onNext(HeartbeatResponse.newBuilder().setStatus("OK").build());
        responseObserver.onCompleted();
    }

    // ── SubmitTask ───────────────────────────────────
    @Override
    public void submitTask(TaskRequest request, StreamObserver<TaskResponse> responseObserver) {
        LOG.info("📋 SubmitTask: task_id={}, payload={}", request.getTaskId(), request.getPayload());
        TASKS_SUBMITTED.inc();

        // Collect alive nodes
        List<NodeRecord> aliveNodes = new ArrayList<>();
        for (NodeRecord node : registry.values()) {
            if ("ALIVE".equals(node.getStatus())) {
                aliveNodes.add(node);
            }
        }

        if (aliveNodes.isEmpty()) {
            LOG.error("❌ No alive nodes available for task {}", request.getTaskId());
            responseObserver.onNext(TaskResponse.newBuilder()
                    .setAssignedNode("NONE")
                    .setResult("FAILED — no alive nodes")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // Round-robin selection
        int idx = Math.abs(roundRobinIndex.getAndIncrement()) % aliveNodes.size();
        NodeRecord selectedNode = aliveNodes.get(idx);

        // Call predictor for insight (best-effort, don't fail the task if predictor is down)
        String prediction = "N/A";
        try {
            PredictorServiceGrpc.PredictorServiceBlockingStub stub = getPredictorStub();
            if (stub != null) {
                PredictionRequest predReq = PredictionRequest.newBuilder()
                        .setNodeId(selectedNode.getNodeId())
                        .setCpu(selectedNode.getCpuUsage())
                        .setMemory(selectedNode.getMemoryUsage())
                        .build();
                PredictionResponse predResp = stub.getPrediction(predReq);
                prediction = String.format("load=%.2f, fail_prob=%.2f, rec=%s",
                        predResp.getPredictedLoad(), predResp.getFailureProbability(), predResp.getRecommendation());
                LOG.info("🔮 Prediction for {}: {}", selectedNode.getNodeId(), prediction);
            }
        } catch (Exception e) {
            LOG.warn("⚠ Predictor unavailable: {}", e.getMessage());
        }

        String result = String.format("Task '%s' assigned to node '%s' [prediction: %s]",
                request.getTaskId(), selectedNode.getNodeId(), prediction);
        LOG.info("✅ {}", result);

        responseObserver.onNext(TaskResponse.newBuilder()
                .setAssignedNode(selectedNode.getNodeId())
                .setResult(result)
                .build());
        responseObserver.onCompleted();
    }

    // ── GetNodes ─────────────────────────────────────
    @Override
    public void getNodes(Empty request, StreamObserver<NodeList> responseObserver) {
        LOG.debug("📊 GetNodes request — returning {} nodes", registry.size());

        NodeList.Builder builder = NodeList.newBuilder();
        for (NodeRecord record : registry.values()) {
            builder.addNodes(NodeInfo.newBuilder()
                    .setNodeId(record.getNodeId())
                    .setIp(record.getIp())
                    .setPort(record.getPort())
                    .setHostname(record.getHostname())
                    .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
