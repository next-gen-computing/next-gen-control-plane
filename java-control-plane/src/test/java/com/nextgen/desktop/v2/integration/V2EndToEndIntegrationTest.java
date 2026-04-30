package com.nextgen.desktop.v2.integration;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.*;
import com.nextgen.desktop.v2.db.repositories.*;
import com.nextgen.desktop.v2.service.RegistrationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for V2 full flow:
 * Server registration -> Node registration -> Join request -> Membership
 */
class V2EndToEndIntegrationTest {

    private DatabaseManager dbManager;
    private RegistrationService registrationService;
    private ServerRepository serverRepo;
    private NodeRepository nodeRepo;
    private JoinRequestRepository joinRequestRepo;
    private ClusterMembershipRepository membershipRepo;
    private EntityManager em;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.getInstance();
        em = dbManager.createEntityManager();
        registrationService = new RegistrationService(dbManager);
        serverRepo = new ServerRepository(em);
        nodeRepo = new NodeRepository(em);
        joinRequestRepo = new JoinRequestRepository(em);
        membershipRepo = new ClusterMembershipRepository(em);
    }

    @AfterEach
    void tearDown() {
        if (em != null && em.isOpen()) {
            em.close();
        }
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testFullServerNodeJoinFlow() {
        // Use unique IDs to avoid conflicts with leftover data
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);

        // Step 1: Register a Server
        ServerEntity server = registrationService.registerServer("TestServer-" + uniqueId, 50051);
        assertNotNull(server);
        assertNotNull(server.getId());
        assertNotNull(server.getConnectionToken());

        // Verify server is in database
        Optional<ServerEntity> savedServer = serverRepo.findById(server.getId());
        assertTrue(savedServer.isPresent());
        assertEquals("TestServer-" + uniqueId, savedServer.get().getName());

        // Step 2: Register a node
        NodeEntity node = registrationService.registerNode("TestNode-" + uniqueId);
        assertNotNull(node);
        assertNotNull(node.getId());
        assertNotNull(node.getTlsCertificate());

        // Verify node is in database
        Optional<NodeEntity> savedNode = nodeRepo.findById(node.getId());
        assertTrue(savedNode.isPresent());
        assertEquals("TestNode-" + uniqueId, savedNode.get().getName());

        // Step 3: Node sends join request to Server
        JoinRequestEntity joinRequest = new JoinRequestEntity();
        joinRequest.setId("jr-" + uniqueId);
        joinRequest.setServerId(server.getId());
        joinRequest.setNodeId(node.getId());
        joinRequest.setStatus(JoinRequestEntity.RequestStatus.PENDING);
        joinRequest.setRequestedAt(LocalDateTime.now());
        joinRequestRepo.save(joinRequest);

        // Verify join request is pending
        List<JoinRequestEntity> pendingRequests = joinRequestRepo.findPendingByServerId(server.getId());
        assertTrue(pendingRequests.stream().anyMatch(r -> r.getNodeId().equals(node.getId())));

        // Step 4: Server approves the join request
        Optional<JoinRequestEntity> foundRequest = joinRequestRepo.findById(joinRequest.getId());
        assertTrue(foundRequest.isPresent());
        foundRequest.get().setStatus(JoinRequestEntity.RequestStatus.APPROVED);
        foundRequest.get().setRespondedAt(LocalDateTime.now());
        joinRequestRepo.save(foundRequest.get());

        // Step 5: Update node status to ONLINE before creating membership
        node.setStatus(NodeEntity.NodeStatus.ONLINE);
        nodeRepo.save(node);

        // Step 6: Create cluster membership
        ClusterMembershipEntity membership = new ClusterMembershipEntity();
        membership.setId("cm-" + uniqueId);
        membership.setServerId(server.getId());
        membership.setNodeId(node.getId());
        membership.setStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
        membership.setJoinedAt(LocalDateTime.now());
        membership.setLastHeartbeat(LocalDateTime.now());
        membershipRepo.save(membership);

        // Verify membership exists
        List<ClusterMembershipEntity> serverMemberships = membershipRepo.findByServerId(server.getId());
        assertTrue(serverMemberships.stream().anyMatch(m -> m.getNodeId().equals(node.getId())));

        // Verify node status is ONLINE
        Optional<NodeEntity> updatedNode = nodeRepo.findById(node.getId());
        assertTrue(updatedNode.isPresent());
        assertEquals(NodeEntity.NodeStatus.ONLINE, updatedNode.get().getStatus());
    }

    @Test
    void testMultipleNodesJoiningSameServer() {
        // Register server
        ServerEntity server = registrationService.registerServer("MultiNodeServer", 50053);
        
        // Register 3 nodes
        NodeEntity node1 = registrationService.registerNode("Node1");
        NodeEntity node2 = registrationService.registerNode("Node2");
        NodeEntity node3 = registrationService.registerNode("Node3");
        
        // Create join requests for all nodes
        for (NodeEntity node : List.of(node1, node2, node3)) {
            JoinRequestEntity request = new JoinRequestEntity();
            request.setId("jr-" + node.getId());
            request.setServerId(server.getId());
            request.setNodeId(node.getId());
            request.setStatus(JoinRequestEntity.RequestStatus.PENDING);
            request.setRequestedAt(LocalDateTime.now());
            joinRequestRepo.save(request);
        }
        
        // Verify all 3 pending requests
        List<JoinRequestEntity> pending = joinRequestRepo.findPendingByServerId(server.getId());
        assertEquals(3, pending.size());
        
        // Approve all requests
        for (JoinRequestEntity req : pending) {
            req.setStatus(JoinRequestEntity.RequestStatus.APPROVED);
            joinRequestRepo.save(req);
            
            ClusterMembershipEntity membership = new ClusterMembershipEntity();
            membership.setId("cm-" + req.getNodeId());
            membership.setServerId(server.getId());
            membership.setNodeId(req.getNodeId());
            membership.setStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
            membershipRepo.save(membership);
        }
        
        // Verify all memberships
        List<ClusterMembershipEntity> memberships = membershipRepo.findByServerId(server.getId());
        assertEquals(3, memberships.size());
    }
}
