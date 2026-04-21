package com.nextgen.agent;

import com.nextgen.agent.config.AgentConfig;
import com.nextgen.agent.grpc.GrpcChannelFactory;
import com.nextgen.agent.service.HeartbeatService;
import com.nextgen.agent.service.RegistrationService;
import com.nextgen.agent.state.NodeState;
import com.nextgen.controlplane.grpc.DeregisterRequest;
import com.nextgen.controlplane.grpc.NodeAgentServiceGrpc;
import com.nextgen.controlplane.grpc.RegisterResponse;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NodeAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(NodeAgentOrchestrator.class);
    private final AgentConfig config;
    private final NodeState state;
    private ManagedChannel channel;
    private HeartbeatService heartbeatService;
    private NodeAgentServiceGrpc.NodeAgentServiceBlockingStub blockingStub;

    public NodeAgentOrchestrator(AgentConfig config) {
        this.config = config;
        this.state = new NodeState();
    }

    public void orchestrate() {
        log.info("Orchestrating NodeAgent (ID={}) connecting to {}:{}", config.getNodeId(), config.getControlPlaneHost(), config.getControlPlanePort());
        
        channel = GrpcChannelFactory.buildChannel(config);
        blockingStub = NodeAgentServiceGrpc.newBlockingStub(channel);

        state.setPhase(NodeState.Phase.REGISTERING);

        RegistrationService registrationService = new RegistrationService();
        try {
            RegisterResponse response = registrationService.register(blockingStub, config.getNodeId());
            
            state.setPhase(NodeState.Phase.RUNNING);
            state.setAssignedNodeId(response.getAssignedNodeId().isEmpty() ? config.getNodeId() : response.getAssignedNodeId());
            if (response.getHeartbeatCadenceMs() > 0) {
                state.setHeartbeatCadenceMs(response.getHeartbeatCadenceMs());
            }

            heartbeatService = new HeartbeatService(state, state.getAssignedNodeId(), blockingStub);
            heartbeatService.start();

            // Register Shutdown Hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        } catch (Exception e) {
            log.error("Failed to start agent properly.", e);
            state.setPhase(NodeState.Phase.FAILED);
            shutdown();
        }
    }

    public void shutdown() {
        log.info("Agent shutting down...");
        if (heartbeatService != null) heartbeatService.stop();

        if (channel != null && !channel.isShutdown()) {
            if (state.getPhase() == NodeState.Phase.RUNNING || state.getPhase() == NodeState.Phase.DRAINING) {
                try {
                    blockingStub.deregisterNode(DeregisterRequest.newBuilder().setNodeId(state.getAssignedNodeId()).build());
                    log.info("Deregistered successfully.");
                } catch (Exception e) {
                    log.warn("Failed to deregister upon shutdown: {}", e.getMessage());
                }
            }
            channel.shutdownNow();
        }
    }
}
