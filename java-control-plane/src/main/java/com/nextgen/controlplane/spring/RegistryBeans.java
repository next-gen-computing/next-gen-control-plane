package com.nextgen.controlplane.spring;

import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.task.TaskRegistry;
import com.nextgen.controlplane.job.JobRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@code @Configuration} that exposes the gRPC server's live in-memory registries
 * as Spring beans, so REST controllers and the WebSocket broadcaster can access them.
 *
 * <p>The registry instances are created by {@link com.nextgen.controlplane.ControlPlaneServer}
 * before the Spring context starts and are set via static setters on this class. This avoids
 * any circular dependency between the Spring context and the manually-wired gRPC server.
 *
 * <p>Pattern: static holder → {@code @Bean} factory method → injected into controllers.
 * The Spring context is started AFTER the gRPC server has set all three registries, so all
 * beans are guaranteed to receive non-null instances.
 */
@Configuration
public class RegistryBeans {

    // ── Static holders — set by ControlPlaneServer before Spring context starts ──

    private static NodeRegistry  nodeRegistry;
    private static TaskRegistry  taskRegistry;
    private static JobRegistry   jobRegistry;

    /** Called by {@code ControlPlaneServer.start()} before {@code startSpringContext()}. */
    public static void setRegistries(NodeRegistry nodes, TaskRegistry tasks, JobRegistry jobs) {
        RegistryBeans.nodeRegistry = nodes;
        RegistryBeans.taskRegistry = tasks;
        RegistryBeans.jobRegistry  = jobs;
    }

    // ── Spring @Bean factory methods ─────────────────────────────────────────

    @Bean
    public NodeRegistry nodeRegistry() {
        if (nodeRegistry == null) {
            throw new IllegalStateException(
                    "RegistryBeans.setRegistries() must be called before the Spring context starts");
        }
        return nodeRegistry;
    }

    @Bean
    public TaskRegistry taskRegistry() {
        if (taskRegistry == null) {
            throw new IllegalStateException(
                    "RegistryBeans.setRegistries() must be called before the Spring context starts");
        }
        return taskRegistry;
    }

    @Bean
    public JobRegistry jobRegistry() {
        if (jobRegistry == null) {
            throw new IllegalStateException(
                    "RegistryBeans.setRegistries() must be called before the Spring context starts");
        }
        return jobRegistry;
    }
}
