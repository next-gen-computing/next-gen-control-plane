package com.nextgen.controlplane;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
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
    private static final int DASHBOARD_PORT = 8080;

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

        // ── Dashboard API server (JSON data for frontend) ─────
        HttpServer dashboardServer = HttpServer.create(new InetSocketAddress(DASHBOARD_PORT), 0);
        dashboardServer.createContext("/api/nodes", new DashboardApiHandler(registry));
        dashboardServer.setExecutor(null); // default executor
        dashboardServer.start();
        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  📡 Dashboard API on port {}                    ", DASHBOARD_PORT);
        LOG.info("══════════════════════════════════════════════════");

        // gRPC server
        Server grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new ControlPlaneServiceImpl(registry))
                .build()
                .start();

        LOG.info("══════════════════════════════════════════════════");
        LOG.info("  🚀 ControlPlane gRPC server RUNNING on port {}  ", GRPC_PORT);
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
}
