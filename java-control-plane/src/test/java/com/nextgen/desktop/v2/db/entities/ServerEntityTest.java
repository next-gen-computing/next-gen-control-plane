package com.nextgen.desktop.v2.db.entities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServerEntity.
 */
class ServerEntityTest {

    private ServerEntity server;

    @BeforeEach
    void setUp() {
        server = new ServerEntity();
        server.setId("server-123");
        server.setName("TestServer");
        server.setGrpcPort(50051);
        server.setCpuCores(8);
        server.setMemoryGb(32.0);
        server.setOsInfo("Windows 11");
        server.setTlsCertificate("cert-data");
        server.setConnectionToken("token-123");
        server.setStatus(ServerEntity.ServerStatus.INACTIVE);
        server.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void testGettersAndSetters() {
        assertEquals("server-123", server.getId());
        assertEquals("TestServer", server.getName());
        assertEquals(50051, server.getGrpcPort());
        assertEquals(8, server.getCpuCores());
        assertEquals(32.0, server.getMemoryGb());
        assertEquals("Windows 11", server.getOsInfo());
        assertEquals("cert-data", server.getTlsCertificate());
        assertEquals("token-123", server.getConnectionToken());
        assertEquals(ServerEntity.ServerStatus.INACTIVE, server.getStatus());
        assertNotNull(server.getCreatedAt());
    }

    @Test
    void testStatusTransitions() {
        // Initial status
        assertEquals(ServerEntity.ServerStatus.INACTIVE, server.getStatus());
        
        // Transition to ACTIVE
        server.setStatus(ServerEntity.ServerStatus.ACTIVE);
        assertEquals(ServerEntity.ServerStatus.ACTIVE, server.getStatus());
        
        // Transition back to INACTIVE
        server.setStatus(ServerEntity.ServerStatus.INACTIVE);
        assertEquals(ServerEntity.ServerStatus.INACTIVE, server.getStatus());
    }

    @Test
    void testNoArgsConstructor() {
        ServerEntity emptyServer = new ServerEntity();
        
        assertNull(emptyServer.getId());
        assertNull(emptyServer.getName());
        assertNull(emptyServer.getGrpcPort());
        assertNull(emptyServer.getCpuCores());
        assertNull(emptyServer.getMemoryGb());
        assertNull(emptyServer.getOsInfo());
        assertNull(emptyServer.getTlsCertificate());
        assertNull(emptyServer.getConnectionToken());
        assertNull(emptyServer.getStatus());
        assertNull(emptyServer.getCreatedAt());
    }

    @Test
    void testSetId() {
        server.setId("new-id");
        assertEquals("new-id", server.getId());
    }

    @Test
    void testSetName() {
        server.setName("NewName");
        assertEquals("NewName", server.getName());
    }

    @Test
    void testSetGrpcPort() {
        server.setGrpcPort(8080);
        assertEquals(8080, server.getGrpcPort());
    }

    @Test
    void testSetCpuCores() {
        server.setCpuCores(16);
        assertEquals(16, server.getCpuCores());
    }

    @Test
    void testSetMemoryGb() {
        server.setMemoryGb(64.0);
        assertEquals(64.0, server.getMemoryGb());
    }

    @Test
    void testSetOsInfo() {
        server.setOsInfo("Linux");
        assertEquals("Linux", server.getOsInfo());
    }

    @Test
    void testSetTlsCertificate() {
        server.setTlsCertificate("new-cert");
        assertEquals("new-cert", server.getTlsCertificate());
    }

    @Test
    void testSetConnectionToken() {
        server.setConnectionToken("new-token");
        assertEquals("new-token", server.getConnectionToken());
    }

    @Test
    void testSetCreatedAt() {
        LocalDateTime now = LocalDateTime.now();
        server.setCreatedAt(now);
        assertEquals(now, server.getCreatedAt());
    }

    @Test
    void testStatusEnumValues() {
        assertNotNull(ServerEntity.ServerStatus.ACTIVE);
        assertNotNull(ServerEntity.ServerStatus.INACTIVE);
    }
}
