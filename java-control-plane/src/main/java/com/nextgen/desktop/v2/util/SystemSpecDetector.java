package com.nextgen.desktop.v2.util;

import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for auto-detecting system specifications.
 * Detects CPU, memory, disk, OS, and network information.
 */
public class SystemSpecDetector {
    private static final Logger LOG = LoggerFactory.getLogger(SystemSpecDetector.class);
    
    /**
     * Detect system specifications.
     * 
     * @return Map containing system specs
     */
    public static Map<String, Object> detectSystemSpecs() {
        Map<String, Object> specs = new HashMap<>();
        
        try {
            // CPU information
            int cpuCores = Runtime.getRuntime().availableProcessors();
            specs.put("cpuCores", cpuCores);
            
            // Memory information
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long totalMemoryBytes = osBean.getTotalMemorySize();
            double totalMemoryGb = totalMemoryBytes / (1024.0 * 1024.0 * 1024.0);
            specs.put("memoryGb", Math.round(totalMemoryGb * 100.0) / 100.0);
            
            // Disk information (total disk space)
            long totalDiskBytes = 0;
            try {
                java.nio.file.FileStore store = java.nio.file.Files.getFileStore(
                    java.nio.file.FileSystems.getDefault().getRootDirectories().iterator().next());
                totalDiskBytes = store.getTotalSpace();
            } catch (Exception e) {
                totalDiskBytes = 100 * 1024 * 1024 * 1024L; // Default 100GB
            }
            double totalDiskGb = totalDiskBytes / (1024.0 * 1024.0 * 1024.0);
            specs.put("diskGb", Math.round(totalDiskGb * 100.0) / 100.0);
            
            // OS information
            String osName = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");
            String osArch = System.getProperty("os.arch");
            specs.put("osInfo", String.format("%s %s (%s)", osName, osVersion, osArch));
            
            // Hostname
            String hostname = InetAddress.getLocalHost().getHostName();
            specs.put("hostname", hostname);
            
            // IP address
            String ipAddress = InetAddress.getLocalHost().getHostAddress();
            specs.put("ipAddress", ipAddress);
            
            LOG.info("Detected system specs: CPU={}, Memory={}GB, Disk={}GB, OS={}", 
                    cpuCores, totalMemoryGb, totalDiskGb, osName);
            
        } catch (Exception e) {
            LOG.error("Failed to detect system specs", e);
            // Provide default values on error
            specs.put("cpuCores", Runtime.getRuntime().availableProcessors());
            specs.put("memoryGb", 8.0);
            specs.put("diskGb", 100.0);
            specs.put("osInfo", System.getProperty("os.name"));
            specs.put("hostname", "unknown");
            specs.put("ipAddress", "127.0.0.1");
        }
        
        return specs;
    }
    
    /**
     * Get a suggested name based on hostname.
     * 
     * @return Suggested name
     */
    public static String getSuggestedName() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            // Remove domain if present
            return hostname.split("\\.")[0];
        } catch (UnknownHostException e) {
            return "nextgen-node";
        }
    }
    
    /**
     * Check if a port is available.
     * 
     * @param port The port to check
     * @return true if available, false otherwise
     */
    public static boolean isPortAvailable(int port) {
        try {
            java.net.ServerSocket socket = new java.net.ServerSocket(port);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Find an available port starting from the given port.
     * 
     * @param startPort The starting port
     * @return An available port
     */
    public static int findAvailablePort(int startPort) {
        int port = startPort;
        while (port < startPort + 100) {
            if (isPortAvailable(port)) {
                return port;
            }
            port++;
        }
        throw new RuntimeException("No available port found");
    }
}
