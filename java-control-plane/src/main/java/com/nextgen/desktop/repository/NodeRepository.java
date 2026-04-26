package com.nextgen.desktop.repository;

import com.nextgen.desktop.model.NodeStatus;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Repository for managing node data with caching.
 * Provides reactive collections for UI binding.
 */
public class NodeRepository {
    private static final Logger LOG = LoggerFactory.getLogger(NodeRepository.class);
    
    // Thread-safe storage
    private final ConcurrentHashMap<String, NodeStatus> nodeCache = new ConcurrentHashMap<>();
    private final ObservableMap<String, NodeStatus> observableNodes;
    private final ObservableList<NodeStatus> nodeList;
    
    public NodeRepository() {
        this.observableNodes = FXCollections.observableMap(nodeCache);
        this.nodeList = FXCollections.observableArrayList();
    }
    
    /**
     * Update or add a node to the repository.
     */
    public void save(NodeStatus node) {
        nodeCache.put(node.getNodeId(), node);
        refreshList();
        LOG.debug("Node {} saved to repository", node.getNodeId());
    }
    
    /**
     * Find a node by ID.
     */
    public Optional<NodeStatus> findById(String nodeId) {
        return Optional.ofNullable(nodeCache.get(nodeId));
    }
    
    /**
     * Get all nodes as a list.
     */
    public List<NodeStatus> findAll() {
        return List.copyOf(nodeCache.values());
    }
    
    /**
     * Get observable list for UI binding.
     */
    public ObservableList<NodeStatus> getObservableList() {
        return nodeList;
    }
    
    /**
     * Get observable map for direct access.
     */
    public ObservableMap<String, NodeStatus> getObservableMap() {
        return observableNodes;
    }
    
    /**
     * Remove a node from the repository.
     */
    public void delete(String nodeId) {
        nodeCache.remove(nodeId);
        refreshList();
        LOG.debug("Node {} removed from repository", nodeId);
    }
    
    /**
     * Clear all nodes.
     */
    public void clear() {
        nodeCache.clear();
        refreshList();
        LOG.info("Repository cleared");
    }
    
    /**
     * Get count of all nodes.
     */
    public int count() {
        return nodeCache.size();
    }
    
    /**
     * Get count of alive nodes.
     */
    public long countAlive() {
        return nodeCache.values().stream()
            .filter(NodeStatus::isAlive)
            .count();
    }
    
    /**
     * Get count of dead/offline nodes.
     */
    public long countDead() {
        return nodeCache.values().stream()
            .filter(n -> !n.isAlive())
            .count();
    }
    
    /**
     * Calculate average CPU usage across all alive nodes.
     */
    public double getAverageCpuUsage() {
        return nodeCache.values().stream()
            .filter(NodeStatus::isAlive)
            .mapToDouble(NodeStatus::getCpuUsage)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Calculate average memory usage across all alive nodes.
     */
    public double getAverageMemoryUsage() {
        return nodeCache.values().stream()
            .filter(NodeStatus::isAlive)
            .mapToDouble(NodeStatus::getMemoryUsage)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Update node heartbeat timestamp.
     */
    public void updateHeartbeat(String nodeId) {
        NodeStatus node = nodeCache.get(nodeId);
        if (node != null) {
            node.setLastHeartbeat(Instant.now());
            node.setAlive(true);
            refreshList();
        }
    }
    
    /**
     * Mark nodes as dead if no heartbeat received within timeout.
     */
    public void checkTimeouts(long timeoutSeconds) {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);
        for (NodeStatus node : nodeCache.values()) {
            if (node.getLastHeartbeat().isBefore(cutoff)) {
                node.setAlive(false);
            }
        }
        refreshList();
    }
    
    private void refreshList() {
        nodeList.setAll(nodeCache.values());
    }
    
    /**
     * Dispose resources.
     */
    public void dispose() {
        clear();
    }
}
