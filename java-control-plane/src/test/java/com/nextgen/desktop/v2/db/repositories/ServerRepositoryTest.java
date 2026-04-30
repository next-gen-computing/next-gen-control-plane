package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.ServerEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerRepositoryTest {
    private ServerRepository repository;

    @BeforeEach
    void setUp() {
        EntityManager em = DatabaseManager.getInstance().createEntityManager();
        repository = new ServerRepository(em);
    }

    @AfterEach
    void tearDown() {
        // Don't shutdown singleton - it will be reused by other tests
    }

    @Test
    void testSaveAndFindById() {
        ServerEntity server = createTestServer("server-1");
        ServerEntity saved = repository.save(server);
        
        assertNotNull(saved);
        assertEquals("server-1", saved.getId());
        
        Optional<ServerEntity> found = repository.findById("server-1");
        assertTrue(found.isPresent());
        assertEquals("TestServer", found.get().getName());
    }

    @Test
    void testFindByConnectionToken() {
        ServerEntity server = createTestServer("server-2");
        server.setConnectionToken("token-abc-123");
        repository.save(server);
        
        Optional<ServerEntity> found = repository.findByConnectionToken("token-abc-123");
        assertTrue(found.isPresent());
        assertEquals("server-2", found.get().getId());
    }

    @Test
    void testFindByStatus() {
        ServerEntity active = createTestServer("active-server");
        active.setStatus(ServerEntity.ServerStatus.ACTIVE);
        repository.save(active);

        ServerEntity inactive = createTestServer("inactive-server");
        inactive.setStatus(ServerEntity.ServerStatus.INACTIVE);
        repository.save(inactive);

        List<ServerEntity> activeServers = repository.findByStatus(ServerEntity.ServerStatus.ACTIVE);
        assertTrue(activeServers.size() >= 1);
        assertTrue(activeServers.stream().anyMatch(s -> s.getId().equals("active-server")));
    }

    @Test
    void testFindAll() {
        repository.save(createTestServer("s1"));
        repository.save(createTestServer("s2"));
        
        List<ServerEntity> all = repository.findAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    void testDelete() {
        ServerEntity server = createTestServer("delete-me");
        repository.save(server);
        
        repository.delete("delete-me");
        
        Optional<ServerEntity> found = repository.findById("delete-me");
        assertFalse(found.isPresent());
    }

    private ServerEntity createTestServer(String id) {
        ServerEntity server = new ServerEntity();
        server.setId(id);
        server.setName("TestServer");
        server.setGrpcPort(50051);
        server.setCpuCores(8);
        server.setMemoryGb(32.0);
        server.setOsInfo("TestOS");
        server.setTlsCertificate("cert");
        server.setConnectionToken("token-" + id);
        server.setStatus(ServerEntity.ServerStatus.INACTIVE);
        return server;
    }
}
