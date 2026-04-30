package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.NodeEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NodeRepositoryTest {
    private NodeRepository repository;

    @BeforeEach
    void setUp() {
        EntityManager em = DatabaseManager.getInstance().createEntityManager();
        repository = new NodeRepository(em);
    }

    @AfterEach
    void tearDown() {
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testSaveAndFindById() {
        NodeEntity node = createTestNode("node-1");
        NodeEntity saved = repository.save(node);
        
        assertNotNull(saved);
        assertEquals("node-1", saved.getId());
        
        Optional<NodeEntity> found = repository.findById("node-1");
        assertTrue(found.isPresent());
        assertEquals("TestNode", found.get().getName());
    }

    @Test
    void testFindByStatus() {
        NodeEntity online = createTestNode("online-node");
        online.setStatus(NodeEntity.NodeStatus.ONLINE);
        repository.save(online);

        NodeEntity offline = createTestNode("offline-node");
        offline.setStatus(NodeEntity.NodeStatus.OFFLINE);
        repository.save(offline);

        List<NodeEntity> onlineNodes = repository.findByStatus(NodeEntity.NodeStatus.ONLINE);
        assertTrue(onlineNodes.size() >= 1);
        assertTrue(onlineNodes.stream().anyMatch(n -> n.getId().equals("online-node")));
    }

    @Test
    void testFindAll() {
        repository.save(createTestNode("n1"));
        repository.save(createTestNode("n2"));
        
        List<NodeEntity> all = repository.findAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    void testDelete() {
        NodeEntity node = createTestNode("delete-me");
        repository.save(node);
        
        repository.delete("delete-me");
        
        Optional<NodeEntity> found = repository.findById("delete-me");
        assertFalse(found.isPresent());
    }

    private NodeEntity createTestNode(String id) {
        NodeEntity node = new NodeEntity();
        node.setId(id);
        node.setName("TestNode");
        node.setHostname("test-host");
        node.setCpuCores(4);
        node.setMemoryGb(16.0);
        node.setDiskGb(500.0);
        node.setOsInfo("Ubuntu");
        node.setTlsCertificate("cert");
        node.setStatus(NodeEntity.NodeStatus.OFFLINE);
        return node;
    }
}
