package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.entities.ServerEntity;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ServerEntity operations.
 */
public class ServerRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ServerRepository.class);
    
    private final EntityManager em;
    
    public ServerRepository(EntityManager em) {
        this.em = em;
    }
    
    public ServerEntity save(ServerEntity server) {
        em.getTransaction().begin();
        try {
            if (server.getId() == null) {
                server.setId(UUID.randomUUID().toString());
                em.persist(server);
                LOG.info("Created server: {}", server.getId());
            } else {
                em.merge(server);
                LOG.info("Updated server: {}", server.getId());
            }
            em.getTransaction().commit();
            return server;
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to save server", e);
            throw e;
        }
    }
    
    public Optional<ServerEntity> findById(String id) {
        return Optional.ofNullable(em.find(ServerEntity.class, id));
    }
    
    public Optional<ServerEntity> findByConnectionToken(String token) {
        try {
            return em.createQuery(
                    "SELECT s FROM ServerEntity s WHERE s.connectionToken = :token", ServerEntity.class)
                    .setParameter("token", token)
                    .getResultStream()
                    .findFirst();
        } catch (Exception e) {
            LOG.error("Failed to find server by token", e);
            return Optional.empty();
        }
    }
    
    public List<ServerEntity> findAll() {
        return em.createQuery("SELECT s FROM ServerEntity s", ServerEntity.class)
                .getResultList();
    }
    
    public List<ServerEntity> findByStatus(ServerEntity.ServerStatus status) {
        return em.createQuery(
                "SELECT s FROM ServerEntity s WHERE s.status = :status", ServerEntity.class)
                .setParameter("status", status)
                .getResultList();
    }
    
    public void delete(String id) {
        em.getTransaction().begin();
        try {
            ServerEntity server = em.find(ServerEntity.class, id);
            if (server != null) {
                em.remove(server);
                LOG.info("Deleted server: {}", id);
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to delete server", e);
            throw e;
        }
    }
}
