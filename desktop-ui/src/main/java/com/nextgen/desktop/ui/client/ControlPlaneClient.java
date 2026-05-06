package com.nextgen.desktop.ui.client;

import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * gRPC client for ControlPlaneService.
 * Handles node registration, heartbeats, task submission, and node listing.
 */
public class ControlPlaneClient {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneClient.class);

    private final ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub;

    public ControlPlaneClient(ManagedChannel channel) {
        this.blockingStub = ControlPlaneServiceGrpc.newBlockingStub(channel);
    }

    public ControlPlaneProto.RegisterResponse registerNode(String nodeId, String ip, int port, String hostname) {
        try {
            ControlPlaneProto.NodeInfo request = ControlPlaneProto.NodeInfo.newBuilder()
                    .setNodeId(nodeId)
                    .setIp(ip)
                    .setPort(port)
                    .setHostname(hostname)
                    .build();
            ControlPlaneProto.RegisterResponse response = blockingStub.registerNode(request);
            LOG.info("Node registered: status={}, assigned_id={}", response.getStatus(), response.getAssignedId());
            return response;
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to register node", e);
            return ControlPlaneProto.RegisterResponse.newBuilder()
                    .setStatus("ERROR: " + e.getStatus().getDescription())
                    .build();
        }
    }

    public ControlPlaneProto.HeartbeatResponse sendHeartbeat(String nodeId, float cpu, float memory) {
        try {
            ControlPlaneProto.HeartbeatRequest request = ControlPlaneProto.HeartbeatRequest.newBuilder()
                    .setNodeId(nodeId)
                    .setCpu(cpu)
                    .setMemory(memory)
                    .build();
            return blockingStub.sendHeartbeat(request);
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to send heartbeat for node {}", nodeId, e);
            return ControlPlaneProto.HeartbeatResponse.newBuilder()
                    .setStatus("ERROR")
                    .build();
        }
    }

    public ControlPlaneProto.TaskResponse submitTask(String taskId, String payload) {
        try {
            ControlPlaneProto.TaskRequest request = ControlPlaneProto.TaskRequest.newBuilder()
                    .setTaskId(taskId)
                    .setPayload(payload)
                    .build();
            return blockingStub.submitTask(request);
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to submit task {}", taskId, e);
            return ControlPlaneProto.TaskResponse.newBuilder()
                    .setResult("ERROR: " + e.getStatus().getDescription())
                    .build();
        }
    }

    public List<ControlPlaneProto.NodeInfo> getNodes() {
        try {
            ControlPlaneProto.NodeList response = blockingStub.getNodes(
                    ControlPlaneProto.Empty.getDefaultInstance());
            return response.getNodesList();
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to get nodes", e);
            return List.of();
        }
    }
}
