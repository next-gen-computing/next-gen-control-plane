package com.nextgen.desktop.v2.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Database manager for SQLite + Hibernate.
 * Handles database initialization and provides EntityManager access.
 */
public class DatabaseManager {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseManager.class);
    
    private static final String PERSISTENCE_UNIT_NAME = "nextgen-cp-v2";
    private static final String DB_DIR = System.getProperty("user.home") + "/.nextgen-cp-v2";
    private static final String DB_FILE = DB_DIR + "/cluster.db";
    
    private static EntityManagerFactory emf;
    private static DatabaseManager instance;
    
    private DatabaseManager() {
        initializeDatabase();
    }
    
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }
    
    private void initializeDatabase() {
        try {
            // Create database directory if it doesn't exist
            Path dbDir = Paths.get(DB_DIR);
            if (!Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
                LOG.info("Created database directory: {}", DB_DIR);
            }
            
            // Configure Hibernate for SQLite
            Map<String, Object> properties = new HashMap<>();
            properties.put("jakarta.persistence.jdbc.driver", "org.sqlite.JDBC");
            properties.put("jakarta.persistence.jdbc.url", "jdbc:sqlite:" + DB_FILE);
            properties.put("jakarta.persistence.jdbc.user", "");
            properties.put("jakarta.persistence.jdbc.password", "");
            properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
            properties.put("hibernate.hbm2ddl.auto", "update");
            properties.put("hibernate.show_sql", "false");
            properties.put("hibernate.format_sql", "false");
            // Enable WAL mode for better concurrent access
            properties.put("hibernate.connection.url", "jdbc:sqlite:" + DB_FILE + "?journal_mode=WAL");
            properties.put("hibernate.jdbc.batch_size", "20");
            
            // Create EntityManagerFactory
            emf = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT_NAME, properties);
            
            LOG.info("Database initialized: {}", DB_FILE);
            
        } catch (Exception e) {
            LOG.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    public EntityManager createEntityManager() {
        if (emf == null || !emf.isOpen()) {
            initializeDatabase();
        }
        return emf.createEntityManager();
    }
    
    public void shutdown() {
        if (emf != null && emf.isOpen()) {
            emf.close();
            LOG.info("Database connection closed");
        }
        // Clean up WAL files to prevent locked file issues on next launch
        cleanupWalFiles();
    }

    private void cleanupWalFiles() {
        try {
            Path dbPath = Paths.get(DB_FILE);
            Path walPath = Paths.get(DB_FILE + "-wal");
            Path shmPath = Paths.get(DB_FILE + "-shm");

            // Delete WAL files if they exist
            if (Files.exists(walPath)) {
                Files.delete(walPath);
                LOG.info("Deleted WAL file: {}", walPath);
            }
            if (Files.exists(shmPath)) {
                Files.delete(shmPath);
                LOG.info("Deleted SHM file: {}", shmPath);
            }
        } catch (Exception e) {
            LOG.warn("Failed to cleanup WAL files (may be in use by another process): {}", e.getMessage());
        }
    }
}
