package com.nextgen.desktop.v2.db.repositories;

import com.nextgen.desktop.v2.db.entities.NodeEntity;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for NodeEntity operations.
 */
public class NodeRepository {
    private static final Logger LOG = LoggerFactory.getLogger(NodeRepository.class);
    
    private final EntityManager em;
    
    public NodeRepository(EntityManager em) {
        this.em = em;
    }
    
    public NodeEntity save(NodeEntity node) {
        em.getTransaction().begin();
        try {
            if (node.getId() == null) {
                node.setId(UUID.randomUUID().toString());
                em.persist(node);
                LOG.info("Created node: {}", node.getId());
            } else {
                em.merge(node);
                LOG.info("Updated node: {}", node.getId());
            }
            em.getTransaction().commit();
            return node;
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to save node", e);
            throw e;
        }
    }
    
    public Optional<NodeEntity> findById(String id) {
        return Optional.ofNullable(em.find(NodeEntity.class, id));
    }
    
    public Optional<NodeEntity> findByHostname(String hostname) {
        try {
            return em.createQuery(
                    "SELECT n FROM NodeEntity n WHERE n.hostname = :hostname", NodeEntity.class)
                    .setParameter("hostname", hostname)
                    .getResultStream()
                    .findFirst();
        } catch (Exception e) {
            LOG.error("Failed to find node by hostname", e);
            return Optional.empty();
        }
    }
    
    public List<NodeEntity> findAll() {
        return em.createQuery("SELECT n FROM NodeEntity n", NodeEntity.class)
                .getResultList();
    }
    
    public List<NodeEntity> findByStatus(NodeEntity.NodeStatus status) {
        return em.createQuery(
                "SELECT n FROM NodeEntity n WHERE n.status = :status", NodeEntity.class)
                .setParameter("status", status)
                .getResultList();
    }
    
    public void delete(String id) {
        em.getTransaction().begin();
        try {
            NodeEntity node = em.find(NodeEntity.class, id);
            if (node != null) {
                em.remove(node);
                LOG.info("Deleted node: {}", id);
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            em.getTransaction().rollback();
            LOG.error("Failed to delete node", e);
            throw e;
        }
    }
}
