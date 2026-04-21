package com.nextgen.agent;

import com.nextgen.proto.ControlPlaneProto.*;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import com.sun.management.OperatingSystemMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NodeAgent — registers with ControlPlane and sends real OS heartbeats every 2
 * seconds.
 * Uses ONLY real readings from OperatingSystemMXBean (never random/fake
 * values).
 */
public class NodeAgent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeAgent.class);

    private static final int HEARTBEAT_INTERVAL_SECONDS = 2;
    private static final int METRICS_PORT = 9090;

    // ── Prometheus Metrics ──────────────────────────
    private static final Counter HEARTBEAT_COUNT = Counter.build()
            .name("node_heartbeat_count_total")
            .help("Total heartbeats sent by this node agent")
            .register();

    private static final Gauge CPU_USAGE = Gauge.build()
            .name("node_cpu_usage")
            .help("Current CPU usage percentage (real OS reading)")
            .register();

    private static final Gauge MEMORY_USAGE = Gauge.build()
            .name("node_memory_usage")
            .help("Current memory usage percentage (real OS reading)")
            .register();

    public static void start() throws IOException, InterruptedException {
        String nodeId = System.getenv().getOrDefault("NODE_ID", "unknown");
        String controlPlaneHost = System.getenv().getOrDefault("CONTROL_PLANE_HOST", "control-plane");
        String hostname = InetAddress.getLocalHost().getHostName();
        String ip = InetAddress.getLocalHost().getHostAddress();

        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  🖥  NodeAgent '{}' starting...", nodeId);
        LOG.info("  Hostname: {}, IP: {}", hostname, ip);
        LOG.info("  ControlPlane: {}:50051", controlPlaneHost);
        LOG.info("══════════════════════════════════════════════════");

        // Prometheus
        DefaultExports.initialize();
        new HTTPServer.Builder().withPort(METRICS_PORT).build();
        LOG.info("📊 Prometheus metrics on port {}", METRICS_PORT);

        // gRPC channel to ControlPlane
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(controlPlaneHost, 50051)
                .usePlaintext()
                .build();
        ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub = ControlPlaneServiceGrpc.newBlockingStub(channel);

        // ── Register ────────────────────────────────
        registerWithRetry(stub, nodeId, ip, hostname);

        // ── Real OS metrics bean ────────────────────
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // ── Heartbeat loop ──────────────────────────
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-sender");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                // REAL readings — never fake
                double cpuLoad = osBean.getCpuLoad(); // 0.0–1.0
                long totalMem = osBean.getTotalMemorySize();
                long freeMem = osBean.getFreeMemorySize();

                float cpuPercent = (cpuLoad < 0) ? 0.0f : (float) (cpuLoad * 100.0);
                float memPercent = (totalMem > 0) ? (float) ((totalMem - freeMem) * 100.0 / totalMem) : 0.0f;

                // Update Prometheus gauges
                CPU_USAGE.set(cpuPercent);
                MEMORY_USAGE.set(memPercent);

                HeartbeatRequest hb = HeartbeatRequest.newBuilder()
                        .setNodeId(nodeId)
                        .setCpu(cpuPercent)
                        .setMemory(memPercent)
                        .build();

                HeartbeatResponse resp = stub.sendHeartbeat(hb);
                HEARTBEAT_COUNT.inc();

                LOG.info("💓 Heartbeat #{}: cpu={}%, mem={}% → {}",
                        (long) HEARTBEAT_COUNT.get(),
                        String.format("%.1f", cpuPercent),
                        String.format("%.1f", memPercent),
                        resp.getStatus());

            } catch (Exception e) {
                LOG.error("❌ Heartbeat failed: {}", e.getMessage());
            }
        }, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Keep main thread alive
        Thread.currentThread().join();
    }

    /**
     * Registers with the ControlPlane, retrying up to 10 times with 2-second
     * delays.
     */
    private static void registerWithRetry(
            ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub,
            String nodeId, String ip, String hostname) {

        NodeInfo nodeInfo = NodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setIp(ip)
                .setPort(50051)
                .setHostname(hostname)
                .build();

        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                RegisterResponse resp = stub.registerNode(nodeInfo);
                LOG.info("✅ Registered with ControlPlane: status={}, assigned_id={}",
                        resp.getStatus(), resp.getAssignedId());
                return;
            } catch (Exception e) {
                LOG.warn("⚠ Registration attempt {}/10 failed: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        LOG.error("❌ Could not register after 10 attempts. Exiting.");
        System.exit(1);
    }
}
