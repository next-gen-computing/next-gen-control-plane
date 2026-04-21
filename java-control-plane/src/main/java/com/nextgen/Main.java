package com.nextgen;

import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.agent.NodeAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified entry point for the Next-Gen Control Plane application.
 * Behavior is determined by the ROLE environment variable:
 *   - "server" → starts the ControlPlane gRPC server
 *   - "agent"  → starts a NodeAgent that registers and sends heartbeats
 */
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("ROLE", "server");
        LOG.info("=== Next-Gen Control Plane | Role: {} ===", role.toUpperCase());

        try {
            switch (role.toLowerCase()) {
                case "server" -> {
                    LOG.info("Starting ControlPlane server...");
                    ControlPlaneServer.start();
                }
                case "agent" -> {
                    LOG.info("Starting NodeAgent...");
                    NodeAgent.start();
                }
                default -> {
                    LOG.error("Unknown ROLE '{}'. Set ROLE=server or ROLE=agent.", role);
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            LOG.error("Fatal error during startup", e);
            System.exit(1);
        }
    }
}
