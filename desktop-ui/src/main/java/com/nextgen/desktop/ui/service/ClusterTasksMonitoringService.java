package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.server.dto.ClusterTaskDto;
import com.nextgen.proto.ControlPlaneProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls {@code ListTasks} and maintains the whole cluster's real task/sub-task list — the Stage RR
 * sibling of {@link DockerResourcesMonitoringService}, following its exact poll/replace pattern.
 * Deliberately separate from {@code TaskExecutionService}, which only ever tracks tasks this desktop-ui
 * instance personally submitted; this service is the cluster-wide view a Task-Manager-style page needs.
 *
 * <p>A poll failure leaves the last-known list in place rather than clearing it — the same "an empty
 * list must mean an empty cluster, never that we couldn't ask" discipline {@link NodeMonitoringService}
 * and {@link DockerResourcesMonitoringService} already apply.
 */
public class ClusterTasksMonitoringService {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterTasksMonitoringService.class);

    private final GrpcConnectionManager connectionManager;
    private final List<ClusterTaskDto> tasks = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile long refreshIntervalMs = 3_000;

    public ClusterTasksMonitoringService(GrpcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public synchronized void startMonitoring() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-tasks-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0, refreshIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("Cluster tasks monitoring started with {}ms interval", refreshIntervalMs);
    }

    public synchronized void stopMonitoring() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
            scheduler = null;
        }
    }

    /** One polling cycle. Package-private so tests can drive it deterministically. */
    void refresh() {
        ControlPlaneClient client = connectionManager.getControlPlaneClient();
        if (client == null) {
            return;
        }

        List<ControlPlaneProto.TaskStatusResponse> responses;
        try {
            responses = client.listAllTasks();
        } catch (ControlPlaneUnavailableException e) {
            LOG.warn("Cluster tasks poll failed: {}", e.shortReason());
            return;
        } catch (RuntimeException e) {
            LOG.error("Unexpected error polling cluster tasks", e);
            return;
        }

        List<ClusterTaskDto> fresh = new ArrayList<>();
        responses.forEach(r -> fresh.add(ClusterTaskDto.from(r)));

        tasks.clear();
        tasks.addAll(fresh);
    }

    public List<ClusterTaskDto> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public void shutdown() {
        stopMonitoring();
    }
}
