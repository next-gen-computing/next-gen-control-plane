package com.nextgen.desktop;

import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launcher for the Desktop Application
 * Detects if running in headless mode and falls back to CLI if needed
 */
public class DesktopLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopLauncher.class);
    
    public static void main(String[] args) {
        // Check if GUI should be used
        boolean useGui = shouldUseGui(args);
        
        if (useGui) {
            try {
                LOG.info("Starting Desktop GUI...");
                Application.launch(DesktopApp.class, args);
            } catch (Exception e) {
                LOG.warn("Failed to start GUI, falling back to CLI mode", e);
                startCliMode(args);
            }
        } else {
            startCliMode(args);
        }
    }
    
    private static boolean shouldUseGui(String[] args) {
        // Check for --cli flag
        for (String arg : args) {
            if ("--cli".equals(arg) || "-c".equals(arg)) {
                return false;
            }
        }
        
        // Check if headless environment
        if (System.getProperty("java.awt.headless", "false").equals("true")) {
            return false;
        }
        
        // Check if DISPLAY is set (Linux)
        String display = System.getenv("DISPLAY");
        if (display == null || display.isEmpty()) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                return false;
            }
        }
        
        // Default to GUI on Windows and macOS
        return true;
    }
    
    private static void startCliMode(String[] args) {
        LOG.info("Starting CLI mode...");
        com.nextgen.Main.main(args);
    }
}
