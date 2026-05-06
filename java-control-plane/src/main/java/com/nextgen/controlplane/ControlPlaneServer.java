package com.nextgen.controlplane;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts the ControlPlane gRPC server on port 50051,
 * Prometheus metrics exporter on port 9090,
 * and the Dashboard HTTP server on port 8080.
 */
public class ControlPlaneServer {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneServer.class);

    private static final int GRPC_PORT = 50051;
    private static final int METRICS_PORT = 9090;
    private static final int DASHBOARD_PORT = 8085;

    public static void start() throws IOException, InterruptedException {
        // Shared node registry
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();

        // Start heartbeat monitor daemon
        Thread monitorThread = new Thread(new HeartbeatMonitor(registry), "heartbeat-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        // Prometheus JVM metrics
        DefaultExports.initialize();
        HTTPServer metricsServer = new HTTPServer.Builder().withPort(METRICS_PORT).build();
        LOG.info("📊 Prometheus metrics server started on port {}", METRICS_PORT);

        // ── Dashboard HTTP server (static files + API) ─────
        HttpServer dashboardServer = HttpServer.create(new InetSocketAddress(DASHBOARD_PORT), 0);
        
        // Static files (HTML, CSS, JS) - production-grade for physical nodes
        String dashboardBase = System.getProperty("user.dir") + "/../dashboard/html";
        dashboardServer.createContext("/", new StaticFileHandler(dashboardBase));
        
        // API endpoint for live node data
        dashboardServer.createContext("/api/nodes", new DashboardApiHandler(registry));
        
        dashboardServer.setExecutor(null); // default executor
        dashboardServer.start();
        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  📡 Dashboard on http://localhost:{}             ", DASHBOARD_PORT);
        LOG.info("  📊 API endpoint: http://localhost:{}/api/nodes   ", DASHBOARD_PORT);
        LOG.info("══════════════════════════════════════════════════");

        // gRPC server
        Server grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new ControlPlaneServiceImpl(registry))
                .build()
                .start();

        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  🚀 ControlPlane gRPC server RUNNING on port {}  ", GRPC_PORT);
        LOG.info("  🌐 LAN IPs (use these from other laptops):");
        printLanIps();
        LOG.info("══════════════════════════════════════════════════");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down ControlPlane server...");
            grpcServer.shutdown();
            dashboardServer.stop(2);
            metricsServer.close();
            LOG.info("ControlPlane server stopped.");
        }));

        // Block main thread until server terminates
        grpcServer.awaitTermination();
    }

    private static void printLanIps() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getHostAddress().contains(":")) continue; // skip IPv6 for clarity
                    LOG.info("     → {} on {}", addr.getHostAddress(), ni.getDisplayName());
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate network interfaces", e);
        }
    }
}
