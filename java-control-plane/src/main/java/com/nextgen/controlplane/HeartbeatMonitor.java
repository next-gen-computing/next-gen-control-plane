package com.nextgen.controlplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Daemon thread that periodically checks all registered nodes for heartbeat timeout.
 * If a node hasn't sent a heartbeat within TIMEOUT_MS, it is marked SUSPECTED_DEAD.
 */
public class HeartbeatMonitor implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(HeartbeatMonitor.class);

    /** A node is considered dead if no heartbeat received within this duration. */
    private static final long TIMEOUT_MS = 6_000;

    /** How often the monitor thread checks for stragglers. */
    private static final long CHECK_INTERVAL_MS = 3_000;

    private final ConcurrentHashMap<String, NodeRecord> registry;

    public HeartbeatMonitor(ConcurrentHashMap<String, NodeRecord> registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        LOG.info("HeartbeatMonitor started (timeout={}ms, interval={}ms)", TIMEOUT_MS, CHECK_INTERVAL_MS);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
                checkHeartbeats();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("HeartbeatMonitor interrupted, shutting down.");
                break;
            }
        }
    }

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (NodeRecord node : registry.values()) {
            long elapsed = now - node.getLastHeartbeatMillis();
            if (elapsed > TIMEOUT_MS && !"SUSPECTED_DEAD".equals(node.getStatus())) {
                node.setStatus("SUSPECTED_DEAD");
                LOG.warn("⚠ Node '{}' marked SUSPECTED_DEAD (no heartbeat for {}ms)", node.getNodeId(), elapsed);
            } else if (elapsed <= TIMEOUT_MS && "SUSPECTED_DEAD".equals(node.getStatus())) {
                // Node came back alive
                node.setStatus("ALIVE");
                LOG.info("✅ Node '{}' recovered — heartbeat received", node.getNodeId());
            }
        }
    }
}
