package com.nextgen.desktop.ui.model;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeModel.
 */
class NodeModelTest {

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
    }

    @Test
    void testDefaultConstructor() {
        NodeModel node = new NodeModel();
        assertEquals("", node.getId());
        assertEquals("", node.getName());
        assertEquals("OFFLINE", node.getStatus());
        assertEquals(0.0, node.getCpuUsage());
    }

    @Test
    void testParameterizedConstructor() {
        NodeModel node = new NodeModel("node-1", "Test Node", "localhost", "127.0.0.1", 50051);
        assertEquals("node-1", node.getId());
        assertEquals("Test Node", node.getName());
        assertEquals("localhost", node.getHostname());
        assertEquals("127.0.0.1", node.getIp());
        assertEquals(50051, node.getPort());
    }

    @Test
    void testPropertyBinding() {
        NodeModel node = new NodeModel();

        node.setCpuUsage(45.5);
        assertEquals(45.5, node.getCpuUsage());

        node.setMemoryUsage(72.3);
        assertEquals(72.3, node.getMemoryUsage());

        node.setStatus("HEALTHY");
        assertEquals("HEALTHY", node.getStatus());
    }

    @Test
    void testToString() {
        NodeModel node = new NodeModel("n1", "Worker 1", "host", "10.0.0.1", 50051);
        assertEquals("Worker 1 (n1)", node.toString());
    }
}
