package com.nextgen.agent.state;

import com.nextgen.agent.metrics.ResourceMetrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class NodeState {
    public enum Phase {
        INITIALIZING,
        REGISTERING,
        RUNNING,
        DRAINING,
        OFFLINE,
        FAILED
    }

    private final AtomicReference<Phase> currentPhase = new AtomicReference<>(Phase.INITIALIZING);
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final AtomicLong heartbeatCadenceMs = new AtomicLong(5000);
    private final AtomicReference<ResourceMetrics> latestMetrics = new AtomicReference<>();
    private final AtomicReference<String> assignedNodeId = new AtomicReference<>(null);

    public Phase getPhase() {
        return currentPhase.get();
    }

    public void setPhase(Phase newPhase) {
        this.currentPhase.set(newPhase);
    }

    public long incrementAndGetSequence() {
        return sequenceCounter.incrementAndGet();
    }

    public long getHeartbeatCadenceMs() {
        return heartbeatCadenceMs.get();
    }

    public void setHeartbeatCadenceMs(long cadenceMs) {
        if (cadenceMs > 0) {
            this.heartbeatCadenceMs.set(cadenceMs);
        }
    }

    public ResourceMetrics getLatestMetrics() {
        return latestMetrics.get();
    }

    public void setLatestMetrics(ResourceMetrics metrics) {
        this.latestMetrics.set(metrics);
    }

    public String getAssignedNodeId() {
        return assignedNodeId.get();
    }

    public void setAssignedNodeId(String id) {
        this.assignedNodeId.set(id);
    }
}
