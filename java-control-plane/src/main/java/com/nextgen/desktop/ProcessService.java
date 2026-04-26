package com.nextgen.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Manages the lifecycle of ControlPlane Server and NodeAgent processes
 */
public class ProcessService {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessService.class);
    
    private ExecutorService executor;
    private Future<?> serverFuture;
    private Future<?> nodeFuture;
    private boolean serverRunning = false;
    private boolean nodeRunning = false;
    
    public ProcessService() {
        this.executor = Executors.newFixedThreadPool(2);
    }
    
    public void startServer(Runnable serverTask) {
        if (serverRunning) {
            LOG.warn("Server is already running");
            return;
        }
        serverRunning = true;
        serverFuture = executor.submit(() -> {
            try {
                serverTask.run();
            } catch (Exception e) {
                LOG.error("Server process failed", e);
                serverRunning = false;
            }
        });
        LOG.info("Server process started");
    }
    
    public void startNode(Runnable nodeTask) {
        if (nodeRunning) {
            LOG.warn("Node is already running");
            return;
        }
        nodeRunning = true;
        nodeFuture = executor.submit(() -> {
            try {
                nodeTask.run();
            } catch (Exception e) {
                LOG.error("Node process failed", e);
                nodeRunning = false;
            }
        });
        LOG.info("Node process started");
    }
    
    public void stopServer() {
        if (serverFuture != null && !serverFuture.isDone()) {
            serverFuture.cancel(true);
            LOG.info("Server process stopped");
        }
        serverRunning = false;
    }
    
    public void stopNode() {
        if (nodeFuture != null && !nodeFuture.isDone()) {
            nodeFuture.cancel(true);
            LOG.info("Node process stopped");
        }
        nodeRunning = false;
    }
    
    public void stopAll() {
        stopServer();
        stopNode();
        executor.shutdownNow();
        LOG.info("All processes stopped");
    }
    
    public boolean isServerRunning() {
        return serverRunning && serverFuture != null && !serverFuture.isDone();
    }
    
    public boolean isNodeRunning() {
        return nodeRunning && nodeFuture != null && !nodeFuture.isDone();
    }
}
