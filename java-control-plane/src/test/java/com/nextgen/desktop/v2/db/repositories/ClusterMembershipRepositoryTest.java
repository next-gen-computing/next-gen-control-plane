package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClusterMembershipRepositoryTest {
    private ClusterMembershipRepository repository;
    private ServerRepository serverRepo;
    private NodeRepository nodeRepo;
    private ServerEntity server;
    private NodeEntity node;

    @BeforeEach
    void setUp() {
        EntityManager em = DatabaseManager.getInstance().createEntityManager();
        repository = new ClusterMembershipRepository(em);
        serverRepo = new ServerRepository(em);
        nodeRepo = new NodeRepository(em);
        
        server = new ServerEntity();
        server.setId("server-1");
        server.setName("TestServer");
        server.setGrpcPort(50051);
        server.setConnectionToken("token-1");
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        serverRepo.save(server);
        
        node = new NodeEntity();
        node.setId("node-1");
        node.setName("TestNode");
        node.setStatus(NodeEntity.NodeStatus.ONLINE);
        nodeRepo.save(node);
    }

    @AfterEach
    void tearDown() {
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testSaveAndFindById() {
        ClusterMembershipEntity membership = createTestMembership("m1");
        ClusterMembershipEntity saved = repository.save(membership);
        
        assertNotNull(saved);
        
        Optional<ClusterMembershipEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
    }

    @Test
    void testFindByServerId() {
        ClusterMembershipEntity m1 = createTestMembership("m1");
        repository.save(m1);
        
        List<ClusterMembershipEntity> found = repository.findByServerId("server-1");
        assertTrue(found.size() >= 1);
    }

    @Test
    void testFindByNodeId() {
        ClusterMembershipEntity m1 = createTestMembership("m1");
        repository.save(m1);
        
        List<ClusterMembershipEntity> found = repository.findByNodeId("node-1");
        assertTrue(found.size() >= 1);
    }

    @Test
    void testFindByNodeAndServer() {
        ClusterMembershipEntity m1 = createTestMembership("m1");
        repository.save(m1);
        
        Optional<ClusterMembershipEntity> found = repository.findByNodeAndServer("node-1", "server-1");
        assertTrue(found.isPresent());
    }

    @Test
    void testFindByStatus() {
        ClusterMembershipEntity m1 = createTestMembership("m1");
        m1.setStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
        repository.save(m1);
        
        List<ClusterMembershipEntity> approved = repository.findByStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
        assertTrue(approved.size() >= 1);
    }

    @Test
    void testUpdateMetrics() {
        ClusterMembershipEntity m1 = createTestMembership("m1");
        ClusterMembershipEntity saved = repository.save(m1);
        
        saved.setCpuUsagePercent(75.0);
        saved.setMemoryUsageMb(80.0 * 1024.0);
        saved.setLastHeartbeat(LocalDateTime.now());
        repository.save(saved);
        
        Optional<ClusterMembershipEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals(75.0, found.get().getCpuUsagePercent());
        assertEquals(80.0 * 1024.0, found.get().getMemoryUsageMb());
    }

    private ClusterMembershipEntity createTestMembership(String id) {
        ClusterMembershipEntity m = new ClusterMembershipEntity();
        m.setId(id);
        m.setServerId(server.getId());
        m.setNodeId(node.getId());
        m.setStatus(ClusterMembershipEntity.MembershipStatus.PENDING);
        m.setCpuUsagePercent(50.0);
        m.setMemoryUsageMb(60.0 * 1024.0); // Convert GB to MB
        m.setJoinedAt(LocalDateTime.now());
        m.setLastHeartbeat(LocalDateTime.now());
        return m;
    }
}
