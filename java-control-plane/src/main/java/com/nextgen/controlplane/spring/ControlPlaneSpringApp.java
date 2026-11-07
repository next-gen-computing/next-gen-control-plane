package com.nextgen.controlplane.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot application context for the Next-Gen Control Plane REST API layer.
 *
 * <p>This class is NOT the main entry point for the gRPC server — that remains
 * {@link com.nextgen.Main} (started via {@code ROLE=server}). This Spring context runs
 * <em>alongside</em> the existing gRPC server in the same JVM process, adding:
 * <ul>
 *   <li>Spring MVC REST controllers on port 8080 (nodes, tasks, jobs, accounts, audit)</li>
 *   <li>Spring JDBC / HikariCP / Flyway for real database persistence</li>
 *   <li>Spring WebSocket (STOMP) for real-time dashboard push</li>
 * </ul>
 *
 * <p>Integration: {@link com.nextgen.controlplane.ControlPlaneServer#start()} calls
 * {@link #startSpringContext(String[])} immediately after its own initialisation sequence,
 * so the Spring context shares the same JVM lifetime as the gRPC server.
 *
 * <p>Component scan is deliberately scoped to {@code com.nextgen.controlplane.spring} and
 * {@code com.nextgen.controlplane.api} — the existing gRPC service implementations live under
 * {@code com.nextgen.controlplane} and must NOT be picked up as Spring beans (they are
 * manually wired inside {@code ControlPlaneServer}).
 */
@SpringBootApplication(exclude = {FlywayAutoConfiguration.class})
@ComponentScan(basePackages = {
        "com.nextgen.controlplane.spring",
        "com.nextgen.controlplane.api",
        "com.nextgen.controlplane.db",
        "com.nextgen.controlplane.ws"
})
public class ControlPlaneSpringApp {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ControlPlaneSpringApp.class);

    /**
     * Called from {@link com.nextgen.controlplane.ControlPlaneServer#start()} once the gRPC
     * server is fully initialised. Starts the Spring context (REST API + DB + WebSocket) in
     * a non-blocking background daemon thread — Spring's embedded Tomcat runs on its own threads and
     * the gRPC server continues to block its own main thread via {@code grpcServer.awaitTermination()}.
     */
    public static void startSpringContext(String[] args) {
        Thread springThread = new Thread(() -> {
            try {
                LOG.info("Starting Spring Boot REST API + DB context...");
                SpringApplication app = new SpringApplication(ControlPlaneSpringApp.class);
                app.setRegisterShutdownHook(true);
                app.run(args);
                LOG.info("✅ Spring Boot REST API + DB + WebSocket context started successfully");
            } catch (Throwable t) {
                LOG.error("Spring Boot context initialization failed (gRPC server and core control plane remain operational): {}",
                        t.getMessage(), t);
            }
        }, "spring-context-bootstrap");
        springThread.setDaemon(true);
        springThread.start();
    }
}
