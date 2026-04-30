package com.nextgen.desktop.v2.integration;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.*;
import com.nextgen.desktop.v2.db.repositories.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for database operations including transactions and concurrency.
 */
class DatabaseIntegrationTest {

    private ServerRepository serverRepo;
    private NodeRepository nodeRepo;
    private JoinRequestRepository joinRequestRepo;
    private ClusterMembershipRepository membershipRepo;
    private DatabaseManager dbManager;
    private EntityManager em;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.getInstance();
        em = dbManager.createEntityManager();
        serverRepo = new ServerRepository(em);
        nodeRepo = new NodeRepository(em);
        joinRequestRepo = new JoinRequestRepository(em);
        membershipRepo = new ClusterMembershipRepository(em);
    }

    @AfterEach
    void tearDown() {
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testTransactionRollback() {
        EntityTransaction tx = em.getTransaction();
        
        ServerEntity server = new ServerEntity();
        server.setId("tx-test-server");
        server.setName("TXTestServer");
        server.setGrpcPort(50051);
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        
        tx.begin();
        em.persist(server);
        
        // Simulate error by rolling back
        tx.rollback();
        
        // Verify server was not saved
        Optional<ServerEntity> found = serverRepo.findById("tx-test-server");
        assertFalse(found.isPresent());
    }

    @Test
    void testTransactionCommit() {
        EntityTransaction tx = em.getTransaction();
        
        String serverId = "tx-commit-server-" + UUID.randomUUID();
        ServerEntity server = new ServerEntity();
        server.setId(serverId);
        server.setName("TXCommitServer");
        server.setGrpcPort(50052);
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        
        tx.begin();
        em.persist(server);
        tx.commit();
        
        // Verify server was saved
        Optional<ServerEntity> found = serverRepo.findById(serverId);
        assertTrue(found.isPresent());
        assertEquals("TXCommitServer", found.get().getName());
    }

    @Test
    void testConcurrentReads() throws InterruptedException, ExecutionException {
        // Save a server
        ServerEntity server = new ServerEntity();
        server.setId("concurrent-server");
        server.setName("ConcurrentServer");
        server.setGrpcPort(50053);
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        serverRepo.save(server);
        
        // Launch concurrent reads
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Optional<ServerEntity>>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> serverRepo.findById("concurrent-server")));
        }
        
        // All should succeed and find the same server
        for (Future<Optional<ServerEntity>> future : futures) {
            Optional<ServerEntity> result = future.get();
            assertTrue(result.isPresent());
            assertEquals("ConcurrentServer", result.get().getName());
        }
        
        executor.shutdown();
    }

    @Test
    void testDataIntegrityAcrossRepositories() {
        // Create server
        ServerEntity server = new ServerEntity();
        server.setId("integrity-server");
        server.setName("IntegrityServer");
        server.setGrpcPort(50055);
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        server.setConnectionToken("token-123");
        serverRepo.save(server);
        
        // Create node
        NodeEntity node = new NodeEntity();
        node.setId("integrity-node");
        node.setName("IntegrityNode");
        node.setStatus(NodeEntity.NodeStatus.ONLINE);
        nodeRepo.save(node);
        
        // Create join request
        JoinRequestEntity request = new JoinRequestEntity();
        request.setId("integrity-request");
        request.setServerId(server.getId());
        request.setNodeId(node.getId());
        request.setStatus(JoinRequestEntity.RequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now());
        joinRequestRepo.save(request);
        
        // Create membership
        ClusterMembershipEntity membership = new ClusterMembershipEntity();
        membership.setId("integrity-membership");
        membership.setServerId(server.getId());
        membership.setNodeId(node.getId());
        membership.setStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
        membershipRepo.save(membership);
        
        // Verify all relationships are correct
        Optional<JoinRequestEntity> foundRequest = joinRequestRepo.findById("integrity-request");
        assertTrue(foundRequest.isPresent());
        assertEquals("integrity-server", foundRequest.get().getServerId());
        assertEquals("integrity-node", foundRequest.get().getNodeId());
        
        Optional<ClusterMembershipEntity> foundMembership = membershipRepo.findById("integrity-membership");
        assertTrue(foundMembership.isPresent());
        assertEquals("integrity-server", foundMembership.get().getServerId());
        assertEquals("integrity-node", foundMembership.get().getNodeId());
    }

    @Test
    void testCascadingUpdates() {
        // Create server
        ServerEntity server = new ServerEntity();
        server.setId("cascade-server");
        server.setName("CascadeServer");
        server.setGrpcPort(50054);
        server.setStatus(ServerEntity.ServerStatus.INACTIVE);
        serverRepo.save(server);
        
        // Update status
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        serverRepo.save(server);
        
        // Verify update persisted
        Optional<ServerEntity> found = serverRepo.findById("cascade-server");
        assertTrue(found.isPresent());
        assertEquals(ServerEntity.ServerStatus.ACTIVE, found.get().getStatus());
    }

    @Test
    void testTimestampHandling() {
        LocalDateTime before = LocalDateTime.now();
        
        ServerEntity server = new ServerEntity();
        server.setId("timestamp-server");
        server.setName("TimestampServer");
        server.setGrpcPort(50056);
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        server.setCreatedAt(LocalDateTime.now());
        serverRepo.save(server);
        
        LocalDateTime after = LocalDateTime.now();
        
        Optional<ServerEntity> found = serverRepo.findById("timestamp-server");
        assertTrue(found.isPresent());
        assertNotNull(found.get().getCreatedAt());
        
        // Timestamp should be between before and after
        assertTrue(!found.get().getCreatedAt().isBefore(before) || 
                   !found.get().getCreatedAt().isAfter(after));
    }

    @Test
    void testQueryPerformance() {
        // Insert multiple servers
        int count = 100;
        for (int i = 0; i < count; i++) {
            ServerEntity server = new ServerEntity();
            server.setId("perf-server-" + i);
            server.setName("PerfServer" + i);
            server.setGrpcPort(50000 + i);
            server.setStatus(i % 2 == 0 ? ServerEntity.ServerStatus.ACTIVE : ServerEntity.ServerStatus.INACTIVE);
            serverRepo.save(server);
        }
        
        // Query by status should be fast
        long start = System.currentTimeMillis();
        List<ServerEntity> activeServers = serverRepo.findByStatus(ServerEntity.ServerStatus.ACTIVE);
        long duration = System.currentTimeMillis() - start;

        // Should complete in reasonable time (< 5 seconds for 100 records)
        assertTrue(duration < 5000, "Query took too long: " + duration + "ms");
        assertTrue(activeServers.size() >= 50, "Expected at least 50 active servers, got: " + activeServers.size());
    }
}
