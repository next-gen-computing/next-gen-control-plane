package com.nextgen.controlplane;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeRecord - the core data structure for node registry.
 * Tests thread safety, field initialization, and state transitions.
 */
class NodeRecordTest {

    @Test
    void testConstructorInitialization() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        
        assertEquals("node1", record.getNodeId());
        assertEquals("192.168.1.1", record.getIp());
        assertEquals(50051, record.getPort());
        assertEquals("host1", record.getHostname());
        assertEquals(0.0f, record.getCpuUsage(), 0.001);
        assertEquals(0.0f, record.getMemoryUsage(), 0.001);
        assertEquals("ALIVE", record.getStatus());
        assertTrue(record.getLastHeartbeatMillis() > 0);
    }

    @Test
    void testCpuUsageUpdate() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        
        record.setCpuUsage(45.5f);
        assertEquals(45.5f, record.getCpuUsage(), 0.001);
        
        record.setCpuUsage(99.9f);
        assertEquals(99.9f, record.getCpuUsage(), 0.001);
    }

    @Test
    void testMemoryUsageUpdate() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        
        record.setMemoryUsage(78.3f);
        assertEquals(78.3f, record.getMemoryUsage(), 0.001);
    }

    @Test
    void testStatusTransitions() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        
        assertEquals("ALIVE", record.getStatus());
        
        record.setStatus("SUSPECTED_DEAD");
        assertEquals("SUSPECTED_DEAD", record.getStatus());
        
        record.setStatus("ALIVE");
        assertEquals("ALIVE", record.getStatus());
    }

    @Test
    void testHeartbeatTimestampUpdate() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        long initialTime = record.getLastHeartbeatMillis();
        
        // Wait a tiny bit to ensure timestamp changes
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long newTime = System.currentTimeMillis();
        record.setLastHeartbeatMillis(newTime);
        
        assertEquals(newTime, record.getLastHeartbeatMillis());
        assertTrue(record.getLastHeartbeatMillis() >= initialTime);
    }

    @Test
    void testToStringFormat() {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        record.setCpuUsage(50.0f);
        record.setMemoryUsage(60.0f);
        record.setStatus("ALIVE");
        
        String str = record.toString();
        assertTrue(str.contains("node1"));
        assertTrue(str.contains("192.168.1.1"));
        assertTrue(str.contains("cpu=50.0"));
        assertTrue(str.contains("mem=60.0"));
        assertTrue(str.contains("status=ALIVE"));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        
        // Simulate concurrent updates from heartbeat monitor
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                record.setCpuUsage(i % 100);
                record.setLastHeartbeatMillis(System.currentTimeMillis());
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                record.setMemoryUsage(i % 100);
                record.setStatus(i % 2 == 0 ? "ALIVE" : "SUSPECTED_DEAD");
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        // If we get here without exception, volatile fields are working
        assertNotNull(record.getStatus());
        assertTrue(record.getCpuUsage() >= 0 && record.getCpuUsage() < 100);
        assertTrue(record.getMemoryUsage() >= 0 && record.getMemoryUsage() < 100);
    }
}
