package com.nextgen.agent.grpc;

import com.nextgen.agent.metrics.ResourceMetrics;
import com.nextgen.agent.state.NodeState;
import com.nextgen.controlplane.grpc.HeartbeatRequest;

public class ProtoConverter {
    public static HeartbeatRequest toHeartbeatRequest(String nodeId, long sequence, ResourceMetrics metrics) {
        return HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setSequenceCounter(sequence)
                .setCpuPercent(metrics.cpuPercent())
                .setHeapPercent(metrics.heapPercent())
                .setDiskPercent(metrics.diskPercent())
                .build();
    }
}
