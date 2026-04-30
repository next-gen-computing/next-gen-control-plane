package com.nextgen.desktop.v2.util;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SystemSpecDetectorTest {

    @Test
    void testDetectSystemSpecs() {
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        assertNotNull(specs);
        assertFalse(specs.isEmpty());
        
        // Verify all expected keys are present
        assertTrue(specs.containsKey("cpuCores"));
        assertTrue(specs.containsKey("memoryGb"));
        assertTrue(specs.containsKey("diskGb"));
        assertTrue(specs.containsKey("osInfo"));
        assertTrue(specs.containsKey("hostname"));
    }

    @Test
    void testCpuCoresDetection() {
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        Object cpuCores = specs.get("cpuCores");
        assertNotNull(cpuCores);
        assertTrue(cpuCores instanceof Integer);
        assertTrue((Integer) cpuCores > 0);
        
        // Verify against JVM reported value
        int expectedCores = Runtime.getRuntime().availableProcessors();
        assertEquals(expectedCores, cpuCores);
    }

    @Test
    void testMemoryDetection() {
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        Object memoryGb = specs.get("memoryGb");
        assertNotNull(memoryGb);
        assertTrue(memoryGb instanceof Double);
        assertTrue((Double) memoryGb > 0);
        
        // Just verify it's a reasonable value (between 1GB and 1TB)
        double memory = (Double) memoryGb;
        assertTrue(memory >= 1.0 && memory <= 1024.0);
    }

    @Test
    void testOsInfoDetection() {
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        Object osInfo = specs.get("osInfo");
        assertNotNull(osInfo);
        assertTrue(osInfo instanceof String);
        assertFalse(((String) osInfo).isEmpty());
        
        // Should contain OS name and version
        String os = (String) osInfo;
        assertTrue(os.toLowerCase().contains("windows") || 
                   os.toLowerCase().contains("linux") || 
                   os.toLowerCase().contains("mac"));
    }

    @Test
    void testHostnameDetection() {
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        Object hostname = specs.get("hostname");
        assertNotNull(hostname);
        assertTrue(hostname instanceof String);
        assertFalse(((String) hostname).isEmpty());
    }

    @Test
    void testDiskSpaceDetection() {
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        Object diskGb = specs.get("diskGb");
        assertNotNull(diskGb);
        assertTrue(diskGb instanceof Double);
        assertTrue((Double) diskGb > 0);
    }

    @Test
    void testRealOsValuesNotRandom() {
        // Run detection multiple times and verify consistency
        Map<String, Object> specs1 = SystemSpecDetector.detectSystemSpecs();
        Map<String, Object> specs2 = SystemSpecDetector.detectSystemSpecs();
        
        assertEquals(specs1.get("cpuCores"), specs2.get("cpuCores"));
        assertEquals(specs1.get("memoryGb"), specs2.get("memoryGb"));
        assertEquals(specs1.get("osInfo"), specs2.get("osInfo"));
    }
}
