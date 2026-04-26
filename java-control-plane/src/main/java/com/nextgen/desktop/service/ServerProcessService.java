package com.nextgen.desktop.service;

import com.nextgen.controlplane.ControlPlaneServer;
import com.nextgen.desktop.exception.ServerStartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Service for managing the ControlPlane server process lifecycle.
 */
public class ServerProcessService {
    private static final Logger LOG = LoggerFactory.getLogger(ServerProcessService.class);
    
    private final ErrorHandler errorHandler;
    private ExecutorService executor;
    private Future<?> serverFuture;
    private volatile boolean running = false;
    
    public ServerProcessService(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }
    
    /**
     * Start the ControlPlane server.
     */
    public void start(String predictorHost) throws ServerStartException {
        if (running) {
            LOG.warn("Server is already running");
            return;
        }
        
        try {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ControlPlane-Server");
                t.setDaemon(true);
                return t;
            });
            
            System.setProperty("PREDICTOR_HOST", predictorHost);
            
            serverFuture = executor.submit(() -> {
                try {
                    LOG.info("Starting ControlPlane server...");
                    ControlPlaneServer.start();
                } catch (Exception e) {
                    LOG.error("Server process failed", e);
                    running = false;
                }
            });
            
            running = true;
            LOG.info("ControlPlane server started successfully");
            
        } catch (Exception e) {
            throw new ServerStartException("Failed to start server: " + e.getMessage(), e);
        }
    }
    
    /**
     * Stop the ControlPlane server.
     */
    public void stop() {
        if (!running) return;
        
        LOG.info("Stopping ControlPlane server...");
        
        if (serverFuture != null && !serverFuture.isDone()) {
            serverFuture.cancel(true);
        }
        
        if (executor != null) {
            executor.shutdownNow();
        }
        
        running = false;
        LOG.info("ControlPlane server stopped");
    }
    
    public boolean isRunning() {
        return running && serverFuture != null && !serverFuture.isDone();
    }
    
    public void dispose() {
        stop();
    }
}
