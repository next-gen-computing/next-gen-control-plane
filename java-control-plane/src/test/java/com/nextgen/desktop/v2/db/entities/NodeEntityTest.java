package com.nextgen.desktop.v2.db.entities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class NodeEntityTest {
    private NodeEntity node;

    @BeforeEach
    void setUp() {
        node = new NodeEntity();
        node.setId("node-123");
        node.setName("TestNode");
        node.setHostname("test-host");
        node.setCpuCores(4);
        node.setMemoryGb(16.0);
        node.setDiskGb(500.0);
        node.setOsInfo("Ubuntu 22.04");
        node.setTlsCertificate("cert-data");
        node.setStatus(NodeEntity.NodeStatus.OFFLINE);
        node.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void testGettersAndSetters() {
        assertEquals("node-123", node.getId());
        assertEquals("TestNode", node.getName());
        assertEquals("test-host", node.getHostname());
        assertEquals(4, node.getCpuCores());
        assertEquals(16.0, node.getMemoryGb());
        assertEquals(500.0, node.getDiskGb());
        assertEquals("Ubuntu 22.04", node.getOsInfo());
        assertEquals("cert-data", node.getTlsCertificate());
        assertEquals(NodeEntity.NodeStatus.OFFLINE, node.getStatus());
        assertNotNull(node.getCreatedAt());
    }

    @Test
    void testStatusTransitions() {
        assertEquals(NodeEntity.NodeStatus.OFFLINE, node.getStatus());
        node.setStatus(NodeEntity.NodeStatus.ONLINE);
        assertEquals(NodeEntity.NodeStatus.ONLINE, node.getStatus());
        node.setStatus(NodeEntity.NodeStatus.PENDING);
        assertEquals(NodeEntity.NodeStatus.PENDING, node.getStatus());
    }

    @Test
    void testNoArgsConstructor() {
        NodeEntity emptyNode = new NodeEntity();
        assertNull(emptyNode.getId());
        assertNull(emptyNode.getName());
        assertNull(emptyNode.getStatus());
    }
}
