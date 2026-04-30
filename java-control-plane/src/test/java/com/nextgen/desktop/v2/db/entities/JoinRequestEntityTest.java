package com.nextgen.desktop.v2.db.entities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class JoinRequestEntityTest {
    private JoinRequestEntity request;
    private ServerEntity server;

    @BeforeEach
    void setUp() {
        server = new ServerEntity();
        server.setId("server-123");
        
        request = new JoinRequestEntity();
        request.setId("request-123");
        request.setServerId("server-123");
        request.setNodeId("node-123");
        request.setStatus(JoinRequestEntity.RequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now());
        request.setRespondedAt(null);
    }

    @Test
    void testGettersAndSetters() {
        assertEquals("request-123", request.getId());
        assertEquals("server-123", request.getServerId());
        assertEquals("node-123", request.getNodeId());
        assertEquals(JoinRequestEntity.RequestStatus.PENDING, request.getStatus());
        assertNotNull(request.getRequestedAt());
        assertNull(request.getRespondedAt());
    }

    @Test
    void testStatusTransitions() {
        assertEquals(JoinRequestEntity.RequestStatus.PENDING, request.getStatus());
        request.setStatus(JoinRequestEntity.RequestStatus.APPROVED);
        assertEquals(JoinRequestEntity.RequestStatus.APPROVED, request.getStatus());
        request.setRespondedAt(LocalDateTime.now());
        assertNotNull(request.getRespondedAt());
    }

    @Test
    void testApproveRequest() {
        request.setStatus(JoinRequestEntity.RequestStatus.APPROVED);
        request.setRespondedAt(LocalDateTime.now());
        assertEquals(JoinRequestEntity.RequestStatus.APPROVED, request.getStatus());
        assertNotNull(request.getRespondedAt());
    }

    @Test
    void testRejectRequest() {
        request.setStatus(JoinRequestEntity.RequestStatus.REJECTED);
        request.setRespondedAt(LocalDateTime.now());
        assertEquals(JoinRequestEntity.RequestStatus.REJECTED, request.getStatus());
        assertNotNull(request.getRespondedAt());
    }

    @Test
    void testTimestampUpdates() {
        LocalDateTime now = LocalDateTime.now();
        request.setRequestedAt(now);
        assertEquals(now, request.getRequestedAt());
        
        request.setRespondedAt(now.plusMinutes(5));
        assertEquals(now.plusMinutes(5), request.getRespondedAt());
    }

    @Test
    void testMessageFields() {
        request.setMessage("Please join my cluster");
        assertEquals("Please join my cluster", request.getMessage());
        
        request.setResponseMessage("Welcome aboard!");
        assertEquals("Welcome aboard!", request.getResponseMessage());
    }

    @Test
    void testResponseTimeNullInitially() {
        assertNull(request.getRespondedAt());
        request.setRespondedAt(LocalDateTime.now());
        assertNotNull(request.getRespondedAt());
    }
}
