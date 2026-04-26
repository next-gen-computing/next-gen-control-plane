package com.nextgen.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.desktop.model.ServerConfig;
import com.nextgen.desktop.model.NodeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service for persisting and loading application configuration.
 * Stores configuration in JSON format in the user's home directory.
 */
public class ConfigurationService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationService.class);
    
    private static final String CONFIG_DIR = ".nextgen-cp";
    private static final String SERVER_CONFIG_FILE = "server-config.json";
    private static final String NODE_CONFIG_FILE = "node-config.json";
    
    private final ObjectMapper objectMapper;
    private final Path configPath;
    
    public ConfigurationService() {
        this.objectMapper = new ObjectMapper();
        this.configPath = Paths.get(System.getProperty("user.home"), CONFIG_DIR);
        ensureConfigDirectory();
    }
    
    private void ensureConfigDirectory() {
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
                LOG.info("Created config directory: {}", configPath);
            }
        } catch (IOException e) {
            LOG.error("Failed to create config directory", e);
        }
    }
    
    /**
     * Save server configuration to disk.
     */
    public void saveServerConfig(ServerConfig config) {
        try {
            Path filePath = configPath.resolve(SERVER_CONFIG_FILE);
            objectMapper.writeValue(filePath.toFile(), config);
            LOG.info("Server configuration saved to {}", filePath);
        } catch (IOException e) {
            LOG.error("Failed to save server configuration", e);
        }
    }
    
    /**
     * Load server configuration from disk.
     */
    public ServerConfig loadServerConfig() {
        Path filePath = configPath.resolve(SERVER_CONFIG_FILE);
        if (Files.exists(filePath)) {
            try {
                ServerConfig config = objectMapper.readValue(filePath.toFile(), ServerConfig.class);
                LOG.info("Server configuration loaded from {}", filePath);
                return config;
            } catch (IOException e) {
                LOG.error("Failed to load server configuration, using defaults", e);
            }
        }
        return new ServerConfig();
    }
    
    /**
     * Save node configuration to disk.
     */
    public void saveNodeConfig(NodeConfig config) {
        try {
            Path filePath = configPath.resolve(NODE_CONFIG_FILE);
            objectMapper.writeValue(filePath.toFile(), config);
            LOG.info("Node configuration saved to {}", filePath);
        } catch (IOException e) {
            LOG.error("Failed to save node configuration", e);
        }
    }
    
    /**
     * Load node configuration from disk.
     */
    public NodeConfig loadNodeConfig() {
        Path filePath = configPath.resolve(NODE_CONFIG_FILE);
        if (Files.exists(filePath)) {
            try {
                NodeConfig config = objectMapper.readValue(filePath.toFile(), NodeConfig.class);
                LOG.info("Node configuration loaded from {}", filePath);
                return config;
            } catch (IOException e) {
                LOG.error("Failed to load node configuration, using defaults", e);
            }
        }
        return new NodeConfig();
    }
    
    /**
     * Clear all saved configurations.
     */
    public void clearAll() {
        try {
            Files.deleteIfExists(configPath.resolve(SERVER_CONFIG_FILE));
            Files.deleteIfExists(configPath.resolve(NODE_CONFIG_FILE));
            LOG.info("All configurations cleared");
        } catch (IOException e) {
            LOG.error("Failed to clear configurations", e);
        }
    }
    
    public void dispose() {
        // Nothing to dispose
    }
}
