package com.nextgen.desktop.v2;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.view.registration.RegistrationView;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Next-Gen Control Plane V2 Desktop Application.
 * Modern glassmorphism UI with SQLite persistence and mTLS support.
 */
public class DesktopAppV2 extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopAppV2.class);
    
    private DatabaseManager dbManager;
    
    @Override
    public void init() throws Exception {
        super.init();
        // Initialize database
        dbManager = DatabaseManager.getInstance();
        LOG.info("Database initialized");
    }
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Next-Gen Control Plane V2");
        primaryStage.setMinWidth(1200);
        primaryStage.setMinHeight(800);
        
        // Show registration view
        RegistrationView registrationView = new RegistrationView(primaryStage);
        registrationView.show();
        
        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down V2 desktop app...");
            if (dbManager != null) {
                dbManager.shutdown();
            }
        }));
    }
    
    @Override
    public void stop() throws Exception {
        super.stop();
        if (dbManager != null) {
            dbManager.shutdown();
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
