package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.entities.JoinRequestEntity;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for JoinRequestEntity operations.
 */
public class JoinRequestRepository {
    private static final Logger LOG = LoggerFactory.getLogger(JoinRequestRepository.class);
    
    private final EntityManager em;
    
    public JoinRequestRepository(EntityManager em) {
        this.em = em;
    }
    
    public JoinRequestEntity save(JoinRequestEntity request) {
        em.getTransaction().begin();
        try {
            if (request.getId() == null) {
                request.setId(UUID.randomUUID().toString());
                em.persist(request);
                LOG.info("Created join request: {}", request.getId());
            } else {
                em.merge(request);
                LOG.info("Updated join request: {}", request.getId());
            }
            em.getTransaction().commit();
            return request;
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to save join request", e);
            throw e;
        }
    }
    
    public Optional<JoinRequestEntity> findById(String id) {
        return Optional.ofNullable(em.find(JoinRequestEntity.class, id));
    }
    
    public List<JoinRequestEntity> findByServerId(String serverId) {
        return em.createQuery(
                "SELECT jr FROM JoinRequestEntity jr WHERE jr.serverId = :serverId ORDER BY jr.requestedAt DESC",
                JoinRequestEntity.class)
                .setParameter("serverId", serverId)
                .getResultList();
    }
    
    public List<JoinRequestEntity> findByNodeId(String nodeId) {
        return em.createQuery(
                "SELECT jr FROM JoinRequestEntity jr WHERE jr.nodeId = :nodeId ORDER BY jr.requestedAt DESC",
                JoinRequestEntity.class)
                .setParameter("nodeId", nodeId)
                .getResultList();
    }
    
    public List<JoinRequestEntity> findByStatus(JoinRequestEntity.RequestStatus status) {
        return em.createQuery(
                "SELECT jr FROM JoinRequestEntity jr WHERE jr.status = :status ORDER BY jr.requestedAt DESC",
                JoinRequestEntity.class)
                .setParameter("status", status)
                .getResultList();
    }
    
    public List<JoinRequestEntity> findPendingByServerId(String serverId) {
        return em.createQuery(
                "SELECT jr FROM JoinRequestEntity jr WHERE jr.serverId = :serverId AND jr.status = :status ORDER BY jr.requestedAt DESC",
                JoinRequestEntity.class)
                .setParameter("serverId", serverId)
                .setParameter("status", JoinRequestEntity.RequestStatus.PENDING)
                .getResultList();
    }
    
    public void delete(String id) {
        em.getTransaction().begin();
        try {
            JoinRequestEntity request = em.find(JoinRequestEntity.class, id);
            if (request != null) {
                em.remove(request);
                LOG.info("Deleted join request: {}", id);
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to delete join request", e);
            throw e;
        }
    }
}
