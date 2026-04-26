package com.nextgen.controlplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Unit tests for HeartbeatMonitor - ensures nodes are marked dead after timeout.
 */
class HeartbeatMonitorTest {

    @Test
    void testNodeMarkedDeadAfterTimeout() throws InterruptedException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", record);
        
        // Simulate old heartbeat
        record.setLastHeartbeatMillis(System.currentTimeMillis() - 10_000); // 10 seconds ago
        
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        // Run check once
        Thread monitorThread = new Thread(() -> {
            try {
                Thread.sleep(100); // Let monitor start
                monitor.run(); // This will loop, so interrupt it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        monitorThread.start();
        Thread.sleep(500); // Let monitor check once
        monitorThread.interrupt();
        
        // Status should be SUSPECTED_DEAD after timeout
        assertEquals("SUSPECTED_DEAD", record.getStatus());
    }

    @Test
    void testNodeRemainsAliveWithRecentHeartbeat() throws InterruptedException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", record);
        
        // Fresh heartbeat
        record.setLastHeartbeatMillis(System.currentTimeMillis());
        
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();
        Thread.sleep(500); // Let monitor check
        monitorThread.interrupt();
        
        // Status should still be ALIVE
        assertEquals("ALIVE", record.getStatus());
    }

    @Test
    void testDeadNodeRecovers() throws InterruptedException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", record);
        
        // Set to dead
        record.setStatus("SUSPECTED_DEAD");
        record.setLastHeartbeatMillis(System.currentTimeMillis() - 10_000);
        
        // Simulate new heartbeat
        record.setLastHeartbeatMillis(System.currentTimeMillis());
        
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();
        Thread.sleep(500);
        monitorThread.interrupt();
        
        // Should recover to ALIVE
        assertEquals("ALIVE", record.getStatus());
    }

    @Test
    @Timeout(2)
    void testMonitorShutsDownOnInterrupt() throws InterruptedException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();
        monitorThread.interrupt();
        monitorThread.join();
        
        // Should terminate quickly on interrupt
        assertFalse(monitorThread.isAlive());
    }
}
