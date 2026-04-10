package com.nextgen.agent.config;

import java.util.Objects;

public class AgentConfig {
    private final String controlPlaneHost;
    private final int controlPlanePort;
    private final String nodeId;

    private AgentConfig(Builder builder) {
        this.controlPlaneHost = builder.controlPlaneHost;
        this.controlPlanePort = builder.controlPlanePort;
        this.nodeId = builder.nodeId;
    }

    public String getControlPlaneHost() { return controlPlaneHost; }
    public int getControlPlanePort() { return controlPlanePort; }
    public String getNodeId() { return nodeId; }

    public static AgentConfig fromEnvironment() {
        String host = System.getenv().getOrDefault("CONTROL_PLANE_HOST", "localhost");
        String portStr = System.getenv().getOrDefault("CONTROL_PLANE_PORT", "50051");
        String id = System.getenv().getOrDefault("NODE_ID", "node-" + System.currentTimeMillis());
        return new Builder()
                .controlPlaneHost(host)
                .controlPlanePort(Integer.parseInt(portStr))
                .nodeId(id)
                .build();
    }

    public static class Builder {
        private String controlPlaneHost = "localhost";
        private int controlPlanePort = 50051;
        private String nodeId = "default-node";

        public Builder controlPlaneHost(String controlPlaneHost) {
            this.controlPlaneHost = Objects.requireNonNull(controlPlaneHost);
            return this;
        }

        public Builder controlPlanePort(int controlPlanePort) {
            this.controlPlanePort = controlPlanePort;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = Objects.requireNonNull(nodeId);
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
