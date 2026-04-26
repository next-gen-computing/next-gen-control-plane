package com.nextgen.desktop.service;

import com.nextgen.agent.NodeAgent;
import com.nextgen.desktop.exception.ConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Service for managing the Node Agent process lifecycle.
 */
public class NodeProcessService {
    private static final Logger LOG = LoggerFactory.getLogger(NodeProcessService.class);
    
    private final ErrorHandler errorHandler;
    private ExecutorService executor;
    private Future<?> nodeFuture;
    private volatile boolean running = false;
    private String currentNodeId;
    private String currentServerHost;
    
    public NodeProcessService(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }
    
    /**
     * Start the Node Agent.
     */
    public void start(String nodeId, String serverHost, int serverPort) throws ConnectionException {
        if (running) {
            LOG.warn("Node agent is already running");
            return;
        }
        
        try {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Node-Agent-" + nodeId);
                t.setDaemon(true);
                return t;
            });
            
            this.currentNodeId = nodeId;
            this.currentServerHost = serverHost;
            
            System.setProperty("NODE_ID", nodeId);
            System.setProperty("CONTROL_PLANE_HOST", serverHost);
            
            nodeFuture = executor.submit(() -> {
                try {
                    LOG.info("Starting Node Agent {} connecting to {}...", nodeId, serverHost);
                    NodeAgent.start();
                } catch (Exception e) {
                    LOG.error("Node agent process failed", e);
                    running = false;
                }
            });
            
            running = true;
            LOG.info("Node Agent {} started successfully", nodeId);
            
        } catch (Exception e) {
            throw new ConnectionException(serverHost, serverPort, "Failed to start node: " + e.getMessage());
        }
    }
    
    /**
     * Stop the Node Agent.
     */
    public void stop() {
        if (!running) return;
        
        LOG.info("Stopping Node Agent...");
        
        if (nodeFuture != null && !nodeFuture.isDone()) {
            nodeFuture.cancel(true);
        }
        
        if (executor != null) {
            executor.shutdownNow();
        }
        
        running = false;
        LOG.info("Node Agent stopped");
    }
    
    public boolean isRunning() {
        return running && nodeFuture != null && !nodeFuture.isDone();
    }
    
    public String getCurrentNodeId() { return currentNodeId; }
    public String getCurrentServerHost() { return currentServerHost; }
    
    public void dispose() {
        stop();
    }
}
