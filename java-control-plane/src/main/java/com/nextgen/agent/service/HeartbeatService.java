package com.nextgen.agent.service;

import com.nextgen.agent.grpc.ProtoConverter;
import com.nextgen.agent.metrics.ResourceMetrics;
import com.nextgen.agent.state.NodeState;
import com.nextgen.controlplane.grpc.HeartbeatRequest;
import com.nextgen.controlplane.grpc.HeartbeatResponse;
import com.nextgen.controlplane.grpc.NodeAgentServiceGrpc;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final NodeState nodeState;
    private final String nodeId;
    private final NodeAgentServiceGrpc.NodeAgentServiceBlockingStub stub;
    private final AtomicInteger subsequentFailures = new AtomicInteger(0);

    public HeartbeatService(NodeState nodeState, String nodeId, NodeAgentServiceGrpc.NodeAgentServiceBlockingStub stub) {
        this.nodeState = nodeState;
        this.nodeId = nodeId;
        this.stub = stub;
    }

    public void start() {
        if (nodeState.getPhase() != NodeState.Phase.RUNNING) {
            log.warn("Cannot start heartbeat, phase is {}", nodeState.getPhase());
            return;
        }
        scheduleNext();
    }

    private void scheduleNext() {
        if (nodeState.getPhase() == NodeState.Phase.FAILED || nodeState.getPhase() == NodeState.Phase.OFFLINE) {
            log.info("Agent is {}. Stopping heartbeat loop.", nodeState.getPhase());
            return;
        }
        
        long cadence = nodeState.getHeartbeatCadenceMs();
        scheduler.schedule(this::doHeartbeat, cadence, TimeUnit.MILLISECONDS);
    }

    private void doHeartbeat() {
        try {
            ResourceMetrics currentMetrics = ResourceMetrics.collectLive();
            nodeState.setLatestMetrics(currentMetrics);
            HeartbeatRequest req = ProtoConverter.toHeartbeatRequest(nodeId, nodeState.incrementAndGetSequence(), currentMetrics);
            
            HeartbeatResponse response = stub.sendHeartbeat(req);
            
            subsequentFailures.set(0); // reset on success
            
            if (response.getNewHeartbeatCadenceMs() > 0) {
                nodeState.setHeartbeatCadenceMs(response.getNewHeartbeatCadenceMs());
            }

            if (!response.getTargetState().isEmpty()) {
                log.info("Server requested state change to {}", response.getTargetState());
                if ("DRAIN".equalsIgnoreCase(response.getTargetState())) {
                    nodeState.setPhase(NodeState.Phase.DRAINING);
                } else if ("OFFLINE".equalsIgnoreCase(response.getTargetState())) {
                    nodeState.setPhase(NodeState.Phase.OFFLINE);
                }
            }

        } catch (StatusRuntimeException e) {
            log.error("Heartbeat failed: {}", e.getStatus());
            int failures = subsequentFailures.incrementAndGet();
            if (failures >= 3) {
                log.error("Heartbeat failed 3 consecutive times. Transitioning state to FAILED.");
                nodeState.setPhase(NodeState.Phase.FAILED);
            }
        } finally {
            scheduleNext();
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
