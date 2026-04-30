package com.nextgen.desktop.v2.service;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.NodeEntity;
import com.nextgen.desktop.v2.db.entities.ServerEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationServiceTest {
    private RegistrationService service;
    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.getInstance();
        service = new RegistrationService(dbManager);
    }

    @AfterEach
    void tearDown() {
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testRegisterServer() {
        ServerEntity server = service.registerServer("TestServer", 50051);
        
        assertNotNull(server);
        assertNotNull(server.getId());
        assertEquals("TestServer", server.getName());
        assertEquals(50051, server.getGrpcPort());
        assertNotNull(server.getCpuCores());
        assertNotNull(server.getMemoryGb());
        assertNotNull(server.getOsInfo());
        assertNotNull(server.getTlsCertificate());
        assertNotNull(server.getConnectionToken());
        assertEquals(ServerEntity.ServerStatus.INACTIVE, server.getStatus());
    }

    @Test
    void testRegisterNode() {
        NodeEntity node = service.registerNode("TestNode");
        
        assertNotNull(node);
        assertNotNull(node.getId());
        assertEquals("TestNode", node.getName());
        assertNotNull(node.getHostname());
        assertNotNull(node.getCpuCores());
        assertNotNull(node.getMemoryGb());
        assertNotNull(node.getDiskGb());
        assertNotNull(node.getOsInfo());
        assertNotNull(node.getTlsCertificate());
        assertEquals(NodeEntity.NodeStatus.OFFLINE, node.getStatus());
    }

    @Test
    void testGetServer() {
        ServerEntity saved = service.registerServer("ServerForGet", 50052);
        
        Optional<ServerEntity> found = service.getServer(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("ServerForGet", found.get().getName());
    }

    @Test
    void testGetNode() {
        NodeEntity saved = service.registerNode("NodeForGet");
        
        Optional<NodeEntity> found = service.getNode(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("NodeForGet", found.get().getName());
    }

    @Test
    void testGetAllServers() {
        service.registerServer("Server1", 50051);
        service.registerServer("Server2", 50052);
        
        List<ServerEntity> servers = service.getAllServers();
        assertTrue(servers.size() >= 2);
    }

    @Test
    void testGetAllNodes() {
        service.registerNode("Node1");
        service.registerNode("Node2");
        
        List<NodeEntity> nodes = service.getAllNodes();
        assertTrue(nodes.size() >= 2);
    }

    @Test
    void testUpdateServerStatus() {
        ServerEntity server = service.registerServer("StatusServer", 50053);
        String id = server.getId();
        
        service.updateServerStatus(id, ServerEntity.ServerStatus.ACTIVE);
        
        Optional<ServerEntity> updated = service.getServer(id);
        assertTrue(updated.isPresent());
        assertEquals(ServerEntity.ServerStatus.ACTIVE, updated.get().getStatus());
    }

    @Test
    void testUpdateNodeStatus() {
        NodeEntity node = service.registerNode("StatusNode");
        String id = node.getId();
        
        service.updateNodeStatus(id, NodeEntity.NodeStatus.ONLINE);
        
        Optional<NodeEntity> updated = service.getNode(id);
        assertTrue(updated.isPresent());
        assertEquals(NodeEntity.NodeStatus.ONLINE, updated.get().getStatus());
    }

    @Test
    void testGetServerNotFound() {
        Optional<ServerEntity> found = service.getServer("non-existent-id");
        assertFalse(found.isPresent());
    }
}
