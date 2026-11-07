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

    /**
     * Called from {@link com.nextgen.controlplane.ControlPlaneServer#start()} once the gRPC
     * server is fully initialised. Starts the Spring context (REST API + DB + WebSocket) in
     * a non-blocking way — Spring's embedded Tomcat runs on its own threads and the gRPC
     * server continues to block its own main thread via {@code grpcServer.awaitTermination()}.
     */
    public static void startSpringContext(String[] args) {
        SpringApplication app = new SpringApplication(ControlPlaneSpringApp.class);
        // Run in background — do not let Spring's own lifecycle block the gRPC main thread
        app.setRegisterShutdownHook(true);
        app.run(args);
    }
}
