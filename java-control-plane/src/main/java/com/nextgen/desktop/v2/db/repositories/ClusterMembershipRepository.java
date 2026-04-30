package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.entities.ClusterMembershipEntity;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ClusterMembershipEntity operations.
 */
public class ClusterMembershipRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterMembershipRepository.class);
    
    private final EntityManager em;
    
    public ClusterMembershipRepository(EntityManager em) {
        this.em = em;
    }
    
    public ClusterMembershipEntity save(ClusterMembershipEntity membership) {
        em.getTransaction().begin();
        try {
            if (membership.getId() == null) {
                membership.setId(UUID.randomUUID().toString());
                em.persist(membership);
                LOG.info("Created cluster membership: {}", membership.getId());
            } else {
                em.merge(membership);
                LOG.info("Updated cluster membership: {}", membership.getId());
            }
            em.getTransaction().commit();
            return membership;
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to save cluster membership", e);
            throw e;
        }
    }
    
    public Optional<ClusterMembershipEntity> findById(String id) {
        return Optional.ofNullable(em.find(ClusterMembershipEntity.class, id));
    }
    
    public Optional<ClusterMembershipEntity> findByNodeAndServer(String nodeId, String serverId) {
        try {
            return em.createQuery(
                    "SELECT cm FROM ClusterMembershipEntity cm WHERE cm.nodeId = :nodeId AND cm.serverId = :serverId",
                    ClusterMembershipEntity.class)
                    .setParameter("nodeId", nodeId)
                    .setParameter("serverId", serverId)
                    .getResultStream()
                    .findFirst();
        } catch (Exception e) {
            LOG.error("Failed to find membership by node and server", e);
            return Optional.empty();
        }
    }
    
    public List<ClusterMembershipEntity> findByServerId(String serverId) {
        return em.createQuery(
                "SELECT cm FROM ClusterMembershipEntity cm WHERE cm.serverId = :serverId",
                ClusterMembershipEntity.class)
                .setParameter("serverId", serverId)
                .getResultList();
    }
    
    public List<ClusterMembershipEntity> findByNodeId(String nodeId) {
        return em.createQuery(
                "SELECT cm FROM ClusterMembershipEntity cm WHERE cm.nodeId = :nodeId",
                ClusterMembershipEntity.class)
                .setParameter("nodeId", nodeId)
                .getResultList();
    }
    
    public List<ClusterMembershipEntity> findByStatus(ClusterMembershipEntity.MembershipStatus status) {
        return em.createQuery(
                "SELECT cm FROM ClusterMembershipEntity cm WHERE cm.status = :status",
                ClusterMembershipEntity.class)
                .setParameter("status", status)
                .getResultList();
    }
    
    public void delete(String id) {
        em.getTransaction().begin();
        try {
            ClusterMembershipEntity membership = em.find(ClusterMembershipEntity.class, id);
            if (membership != null) {
                em.remove(membership);
                LOG.info("Deleted cluster membership: {}", id);
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to delete cluster membership", e);
            throw e;
        }
    }
}
