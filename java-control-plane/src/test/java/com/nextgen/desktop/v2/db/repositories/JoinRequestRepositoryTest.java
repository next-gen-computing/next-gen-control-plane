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

class JoinRequestRepositoryTest {
    private JoinRequestRepository repository;
    private ServerRepository serverRepo;
    private ServerEntity server;

    @BeforeEach
    void setUp() {
        EntityManager em = DatabaseManager.getInstance().createEntityManager();
        repository = new JoinRequestRepository(em);
        serverRepo = new ServerRepository(em);
        
        server = new ServerEntity();
        server.setId("server-1");
        server.setName("TestServer");
        server.setGrpcPort(50051);
        server.setConnectionToken("token-1");
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        serverRepo.save(server);
    }

    @AfterEach
    void tearDown() {
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testSaveAndFindById() {
        JoinRequestEntity request = createTestRequest("req-1", "node-1");
        JoinRequestEntity saved = repository.save(request);
        
        assertNotNull(saved);
        
        Optional<JoinRequestEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("node-1", found.get().getNodeId());
    }

    @Test
    void testFindByServerId() {
        JoinRequestEntity req = createTestRequest("req-1", "node-1");
        repository.save(req);
        
        List<JoinRequestEntity> found = repository.findByServerId("server-1");
        assertTrue(found.size() >= 1);
    }

    @Test
    void testFindByNodeId() {
        JoinRequestEntity req = createTestRequest("req-1", "node-1");
        repository.save(req);
        
        List<JoinRequestEntity> found = repository.findByNodeId("node-1");
        assertTrue(found.size() >= 1);
    }

    @Test
    void testFindPendingByServerId() {
        JoinRequestEntity pending = createTestRequest("req-pending", "node-pending");
        pending.setStatus(JoinRequestEntity.RequestStatus.PENDING);
        repository.save(pending);
        
        JoinRequestEntity approved = createTestRequest("req-approved", "node-approved");
        approved.setStatus(JoinRequestEntity.RequestStatus.APPROVED);
        repository.save(approved);
        
        List<JoinRequestEntity> pendingRequests = repository.findPendingByServerId("server-1");
        assertTrue(pendingRequests.stream().anyMatch(r -> r.getNodeId().equals("node-pending")));
    }

    @Test
    void testApproveRequest() {
        JoinRequestEntity req = createTestRequest("req-1", "node-1");
        JoinRequestEntity saved = repository.save(req);
        
        saved.setStatus(JoinRequestEntity.RequestStatus.APPROVED);
        saved.setRespondedAt(LocalDateTime.now());
        repository.save(saved);
        
        Optional<JoinRequestEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals(JoinRequestEntity.RequestStatus.APPROVED, found.get().getStatus());
        assertNotNull(found.get().getRespondedAt());
    }

    @Test
    void testFindByStatus() {
        JoinRequestEntity req = createTestRequest("req-1", "node-1");
        req.setStatus(JoinRequestEntity.RequestStatus.PENDING);
        repository.save(req);
        
        List<JoinRequestEntity> pending = repository.findByStatus(JoinRequestEntity.RequestStatus.PENDING);
        assertTrue(pending.size() >= 1);
    }

    private JoinRequestEntity createTestRequest(String id, String nodeId) {
        JoinRequestEntity req = new JoinRequestEntity();
        req.setId(id);
        req.setServerId(server.getId());
        req.setNodeId(nodeId);
        req.setStatus(JoinRequestEntity.RequestStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        return req;
    }
}
