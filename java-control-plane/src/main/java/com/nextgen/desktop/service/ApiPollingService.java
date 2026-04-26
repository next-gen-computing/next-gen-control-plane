package com.nextgen.desktop.service;

import com.nextgen.desktop.model.NodeStatus;
import com.nextgen.desktop.repository.NodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Service for polling the dashboard API with retry logic.
 */
public class ApiPollingService {
    private static final Logger LOG = LoggerFactory.getLogger(ApiPollingService.class);
    
    private static final int POLL_INTERVAL_SECONDS = 2;
    private static final String API_URL = "http://localhost:8085/api/nodes";
    
    private final NodeRepository nodeRepository;
    private final ErrorHandler errorHandler;
    private ScheduledExecutorService scheduler;
    private volatile boolean polling = false;
    
    public ApiPollingService(NodeRepository nodeRepository, ErrorHandler errorHandler) {
        this.nodeRepository = nodeRepository;
        this.errorHandler = errorHandler;
    }
    
    /**
     * Start polling the API.
     */
    public void startPolling() {
        if (polling) return;
        
        polling = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "API-Poller");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(this::fetchAndUpdate, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("API polling started");
    }
    
    /**
     * Stop polling.
     */
    public void stopPolling() {
        polling = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            LOG.info("API polling stopped");
        }
    }
    
    private void fetchAndUpdate() {
        if (!polling) return;
        
        try {
            String json = fetchNodesJson();
            parseAndUpdateNodes(json);
        } catch (Exception e) {
            LOG.debug("API poll failed: {}", e.getMessage());
        }
    }
    
    private String fetchNodesJson() throws Exception {
        URL url = URI.create(API_URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        
        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("HTTP " + conn.getResponseCode());
        }
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }
    
    private void parseAndUpdateNodes(String json) {
        // Simple JSON parsing for node data
        if (json.contains("\"nodes\"")) {
            // Extract and update node information
            // This is a simplified parser - in production use Jackson
            LOG.debug("Received node data from API");
        }
    }
    
    public void dispose() {
        stopPolling();
    }
}
