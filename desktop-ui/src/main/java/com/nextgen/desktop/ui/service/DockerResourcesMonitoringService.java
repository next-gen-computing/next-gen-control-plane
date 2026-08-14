package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.server.dto.DockerContainerDto;
import com.nextgen.desktop.ui.server.dto.DockerImageDto;
import com.nextgen.desktop.ui.server.dto.DockerNetworkDto;
import com.nextgen.desktop.ui.server.dto.DockerVolumeDto;
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
 * Polls {@code GetDockerResources} and maintains the desktop UI's real container/image/volume/network
 * lists — the Stage T sibling of {@link NodeMonitoringService}, kept separate rather than folded in
 * since Docker inventory is a different concern from node liveness and a node can report one without
 * the other.
 *
 * <p>A poll failure leaves the last-known lists in place rather than clearing them — the same "an empty
 * list must mean an empty cluster, never that we couldn't ask" discipline {@link NodeMonitoringService}
 * already applies.
 */
public class DockerResourcesMonitoringService {
    private static final Logger LOG = LoggerFactory.getLogger(DockerResourcesMonitoringService.class);

    private final GrpcConnectionManager connectionManager;
    private final List<DockerContainerDto> containers = new CopyOnWriteArrayList<>();
    private final List<DockerImageDto> images = new CopyOnWriteArrayList<>();
    private final List<DockerVolumeDto> volumes = new CopyOnWriteArrayList<>();
    private final List<DockerNetworkDto> networks = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile long refreshIntervalMs = 3_000;

    public DockerResourcesMonitoringService(GrpcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public synchronized void startMonitoring() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docker-resources-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0, refreshIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("Docker resources monitoring started with {}ms interval", refreshIntervalMs);
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

        ControlPlaneProto.DockerResourcesSnapshot snapshot;
        try {
            snapshot = client.getDockerResources();
        } catch (ControlPlaneUnavailableException e) {
            LOG.warn("Docker resources poll failed: {}", e.shortReason());
            return;
        } catch (RuntimeException e) {
            LOG.error("Unexpected error polling Docker resources", e);
            return;
        }

        List<DockerContainerDto> newContainers = new ArrayList<>();
        List<DockerImageDto> newImages = new ArrayList<>();
        List<DockerVolumeDto> newVolumes = new ArrayList<>();
        List<DockerNetworkDto> newNetworks = new ArrayList<>();
        for (ControlPlaneProto.NodeDockerState nodeState : snapshot.getNodesList()) {
            String nodeId = nodeState.getNodeId();
            ControlPlaneProto.DockerStateReport report = nodeState.getReport();
            report.getContainersList().forEach(c -> newContainers.add(DockerContainerDto.from(nodeId, c)));
            report.getImagesList().forEach(i -> newImages.add(DockerImageDto.from(nodeId, i)));
            report.getVolumesList().forEach(v -> newVolumes.add(DockerVolumeDto.from(nodeId, v)));
            report.getNetworksList().forEach(n -> newNetworks.add(DockerNetworkDto.from(nodeId, n)));
        }

        replaceAll(containers, newContainers);
        replaceAll(images, newImages);
        replaceAll(volumes, newVolumes);
        replaceAll(networks, newNetworks);
    }

    private static <T> void replaceAll(List<T> target, List<T> fresh) {
        target.clear();
        target.addAll(fresh);
    }

    /** Real start/stop/restart/rm — blocks until the target node's actual {@code docker} invocation
     * finishes, then refreshes immediately so the effect is visible without waiting for the next
     * scheduled poll tick. */
    public ControlPlaneProto.DockerControlResult controlContainer(
            String nodeId, String containerId, ControlPlaneProto.DockerControlAction action) {
        ControlPlaneClient client = connectionManager.getControlPlaneClient();
        if (client == null) {
            return ControlPlaneProto.DockerControlResult.newBuilder()
                    .setOk(false)
                    .setMessage("No gRPC connection available")
                    .build();
        }
        ControlPlaneProto.DockerControlResult result = client.controlDockerContainer(nodeId, containerId, action);
        refresh();
        return result;
    }

    public List<DockerContainerDto> getContainers() {
        return Collections.unmodifiableList(containers);
    }

    public List<DockerImageDto> getImages() {
        return Collections.unmodifiableList(images);
    }

    public List<DockerVolumeDto> getVolumes() {
        return Collections.unmodifiableList(volumes);
    }

    public List<DockerNetworkDto> getNetworks() {
        return Collections.unmodifiableList(networks);
    }

    public void shutdown() {
        stopMonitoring();
    }
}
