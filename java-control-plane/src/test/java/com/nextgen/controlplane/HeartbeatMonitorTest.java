package com.nextgen.controlplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;

/**
 * Unit tests for HeartbeatMonitor - ensures nodes are marked dead after timeout.
 */
class HeartbeatMonitorTest {

    @Test
    void testNodeMarkedDeadAfterTimeout() throws Exception {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", record);
        
        // Simulate old heartbeat (10 seconds ago)
        record.setLastHeartbeatMillis(System.currentTimeMillis() - 10_000);
        
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        // Use reflection to call private checkHeartbeats method directly
        Method checkMethod = HeartbeatMonitor.class.getDeclaredMethod("checkHeartbeats");
        checkMethod.setAccessible(true);
        checkMethod.invoke(monitor);
        
        // Status should be SUSPECTED_DEAD after timeout
        assertEquals("SUSPECTED_DEAD", record.getStatus());
    }

    @Test
    void testNodeRemainsAliveWithRecentHeartbeat() throws Exception {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", record);
        
        // Fresh heartbeat
        record.setLastHeartbeatMillis(System.currentTimeMillis());
        
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        // Use reflection to call checkHeartbeats directly
        Method checkMethod = HeartbeatMonitor.class.getDeclaredMethod("checkHeartbeats");
        checkMethod.setAccessible(true);
        checkMethod.invoke(monitor);
        
        // Status should still be ALIVE
        assertEquals("ALIVE", record.getStatus());
    }

    @Test
    void testDeadNodeRecovers() throws Exception {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", record);
        
        // Set to dead with old heartbeat
        record.setStatus("SUSPECTED_DEAD");
        record.setLastHeartbeatMillis(System.currentTimeMillis() - 10_000);
        
        // Simulate new heartbeat
        record.setLastHeartbeatMillis(System.currentTimeMillis());
        
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry);
        
        // Use reflection to call checkHeartbeats directly
        Method checkMethod = HeartbeatMonitor.class.getDeclaredMethod("checkHeartbeats");
        checkMethod.setAccessible(true);
        checkMethod.invoke(monitor);
        
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
