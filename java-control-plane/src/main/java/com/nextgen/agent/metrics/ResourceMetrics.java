package com.nextgen.agent.metrics;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public record ResourceMetrics(float cpuPercent, float heapPercent, float diskPercent) {
    public static ResourceMetrics collectLive() {
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        
        double cpuLoad = osBean.getProcessCpuLoad();
        if (cpuLoad < 0) { cpuLoad = 0.0; }

        long maxMemory = Runtime.getRuntime().maxMemory();
        long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        float heap = (maxMemory > 0) ? (float) usedMemory / maxMemory : 0.0f;

        // Mock disk for phase 1
        float disk = 0.5f;

        return new ResourceMetrics((float) cpuLoad, heap, disk);
    }
}
