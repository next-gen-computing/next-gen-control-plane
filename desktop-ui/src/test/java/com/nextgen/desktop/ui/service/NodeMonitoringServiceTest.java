package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.NodeModel;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NodeMonitoringService.
 *
 * <p>Uses the package-private constructor with {@code fxThreadDispatch=false}, so model updates run
 * synchronously on the test thread and no JavaFX toolkit is required.
 */
class NodeMonitoringServiceTest {

    private GrpcConnectionManager connectionManager;
    private ControlPlaneClient client;
    private ConnectionStateManager connectionState;
    private NodeMonitoringService service;

    private static ControlPlaneProto.NodeInfo node(String id, ControlPlaneProto.NodeStatus status,
                                                   float cpu, boolean cpuStale) {
        return ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId(id)
                .setHostname(id + "-host")
                .setIp("10.0.0.1")
                .setPort(50051)
                .setCpu(cpu)
                .setMemory(50f)
                .setStatus(status)
                .setCpuStale(cpuStale)
                .setLastHeartbeatEpochMillis(1_700_000_000_000L)
                .build();
    }

    @BeforeEach
    void setUp() {
        connectionManager = mock(GrpcConnectionManager.class);
        client = mock(ControlPlaneClient.class);
        when(connectionManager.getControlPlaneClient()).thenReturn(client);
        when(connectionManager.getPredictorClient()).thenReturn(null);

        connectionState = new ConnectionStateManager(3, java.time.Instant::now, false);
        service = new NodeMonitoringService(connectionManager, connectionState, false);
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void successfulPollPopulatesNodesAndMarksConnected() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 40f, false)));

        service.refresh();

        assertEquals(1, service.getNodes().size());
        assertEquals("HEALTHY", service.getNodes().get(0).getStatus());
        assertEquals(ConnectionState.CONNECTED, connectionState.getState());
    }

    @Test
    void deadNodeIsShownAsOfflineNotHealthy() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD, 40f, false)));

        service.refresh();

        // The previous implementation stamped "HEALTHY" on every node it received, because NodeInfo
        // carried no status at all — a dead node rendered as healthy.
        assertEquals("OFFLINE", service.getNodes().get(0).getStatus());
    }

    @Test
    void statusMappingCoversEveryProtoValueIncludingUnrecognised() {
        assertEquals("HEALTHY",
                NodeMonitoringService.mapStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE));
        assertEquals("OFFLINE",
                NodeMonitoringService.mapStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD));
        assertEquals("WARNING",
                NodeMonitoringService.mapStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_DRAINING));
        assertEquals("OFFLINE",
                NodeMonitoringService.mapStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_DEREGISTERED));
        assertEquals("UNKNOWN",
                NodeMonitoringService.mapStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_UNSPECIFIED));
        // protobuf generates UNRECOGNIZED for every proto3 enum; a switch without a default arm
        // would not compile, and getNumber() on it throws.
        assertEquals("UNKNOWN",
                NodeMonitoringService.mapStatus(ControlPlaneProto.NodeStatus.UNRECOGNIZED));
        assertEquals("UNKNOWN", NodeMonitoringService.mapStatus(null));
    }

    @Test
    void heartbeatTimeComesFromTheControlPlaneNotTheLocalClock() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 40f, false)));

        service.refresh();

        // Using LocalDateTime.now() here (as the old code did) made every node look like it had just
        // reported, even one that had been silent for minutes.
        String expected = NodeMonitoringService.formatHeartbeat(1_700_000_000_000L);
        assertEquals(expected, service.getNodes().get(0).getLastHeartbeat());
    }

    @Test
    void neverHeartbeatedRendersAsNeverNotAsAnEpochDate() {
        assertEquals("Never", NodeMonitoringService.formatHeartbeat(0));
        assertEquals("Never", NodeMonitoringService.formatHeartbeat(-1));
    }

    @Test
    void staleReadingIsFlaggedOnTheModel() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 62f, true)));

        service.refresh();

        NodeModel model = service.getNodes().get(0);
        assertTrue(model.isCpuStale());
        assertEquals("n/a", model.getCpuUsageText(),
                "a stale reading must not be displayed as a number");
    }

    @Test
    void nodesNoLongerReportedAreRemoved() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 10f, false),
                node("node2", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 10f, false)));
        service.refresh();
        assertEquals(2, service.getNodes().size());

        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 10f, false)));
        service.refresh();

        assertEquals(1, service.getNodes().size());
        assertEquals("node1", service.getNodes().get(0).getId());
    }

    @Test
    void deadNodesStayVisibleRatherThanDisappearing() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD, 10f, false)));

        service.refresh();

        // An operator needs to see a failed node. Only nodes the control plane stops reporting
        // entirely are dropped from the list.
        assertEquals(1, service.getNodes().size());
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    void rpcFailureDoesNotClearTheNodeListAndReportsDisconnected() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 40f, false)));
        service.refresh();
        assertEquals(1, service.getNodes().size());

        when(client.getNodes()).thenThrow(new ControlPlaneUnavailableException(
                "getNodes", new StatusRuntimeException(Status.UNAVAILABLE)));
        service.refresh();
        service.refresh();
        service.refresh();

        // Clearing the list would render as a healthy cluster with zero nodes — exactly the failure
        // mode that returning List.of() on error used to produce.
        assertEquals(1, service.getNodes().size(),
                "a failed poll must not be mistaken for an empty cluster");
        assertEquals(ConnectionState.DISCONNECTED, connectionState.getState());
    }

    @Test
    void missingClientIsReportedAsAFailure() {
        when(connectionManager.getControlPlaneClient()).thenReturn(null);

        service.refresh();

        assertNotEquals(ConnectionState.CONNECTED, connectionState.getState());
    }

    @Test
    void unexpectedRuntimeErrorIsReportedRatherThanSwallowed() {
        when(client.getNodes()).thenThrow(new IllegalStateException("boom"));

        service.refresh();

        // The old code caught Exception and only logged, so the UI silently kept its last frame.
        assertNotEquals(ConnectionState.CONNECTED, connectionState.getState());
        assertTrue(connectionState.getDetail().contains("Unexpected"));
    }

    // ── Cluster summary ──────────────────────────────────────────────────────

    @Test
    void emptyClusterReportsNoReadingRatherThanZero() {
        when(client.getNodes()).thenReturn(List.of());

        service.refresh();

        var summary = service.getClusterSummary();
        assertEquals(0, summary.getTotalNodes());
        assertFalse(summary.hasCpuReading());
        assertEquals("n/a", summary.getAvgCpuUsageText());
        assertFalse(summary.hasHealthReading(),
                "an empty cluster has no health to report; 100% would be a fabrication");
    }

    @Test
    void averagesExcludeStaleReadings() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 80f, false),
                node("node2", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 0f, true)));

        service.refresh();

        assertEquals(80.0, service.getClusterSummary().getAvgCpuUsage(), 0.01);
    }

    // ── Stage RR: richer per-node data (battery, risk, RTT, declared hardware) ─

    @Test
    void richerNodeFieldsAreMappedOntoTheModel() {
        ControlPlaneProto.NodeInfo info = ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1")
                .setHostname("node1-host")
                .setIp("10.0.0.1")
                .setPort(50051)
                .setCpu(40f)
                .setMemory(50f)
                .setStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE)
                .setBatteryPercent(72f)
                .setBatteryAvailable(true)
                .setOnAcPower(false)
                .setOnAcPowerKnown(true)
                .setRiskScore(0.75f)
                .setAtRisk(true)
                .addRiskReasons("memory non-decreasing and above 90% ceiling")
                .setPreviousRttSeconds(0.042)
                .setPreviousRttAvailable(true)
                .setCapabilities(ControlPlaneProto.NodeCapabilities.newBuilder()
                        .setCpuCores(8)
                        .setTotalMemoryBytes(16_000_000_000L)
                        .build())
                .build();
        when(client.getNodes()).thenReturn(List.of(info));

        service.refresh();

        NodeModel model = service.getNodes().get(0);
        assertEquals(72f, model.getBatteryPercent(), 0.01);
        assertTrue(model.isBatteryAvailable());
        assertFalse(model.isOnAcPower());
        assertEquals(0.75, model.getRiskScore(), 0.001);
        assertTrue(model.isAtRisk());
        assertEquals(List.of("memory non-decreasing and above 90% ceiling"), model.getRiskReasons());
        assertEquals(0.042, model.getPreviousRttSeconds(), 0.0001);
        assertTrue(model.isPreviousRttAvailable());
        assertEquals(8, model.getCpuCores());
        assertEquals(16_000_000_000L, model.getTotalMemoryBytes());
    }

    @Test
    void unavailableBatteryAndRttReportAsUnavailableNotZero() {
        // A desktop with no battery, or a node on its very first heartbeat before any RTT trend
        // exists, must be distinguishable from "0% battery"/"0ms RTT" — both real, alarming values.
        ControlPlaneProto.NodeInfo info = ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId("node1")
                .setStatus(ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE)
                .setBatteryAvailable(false)
                .setPreviousRttAvailable(false)
                .build();
        when(client.getNodes()).thenReturn(List.of(info));

        service.refresh();

        NodeModel model = service.getNodes().get(0);
        assertFalse(model.isBatteryAvailable());
        assertFalse(model.isPreviousRttAvailable());
    }

    @Test
    void healthPercentReflectsTheRealHealthyRatio() {
        when(client.getNodes()).thenReturn(List.of(
                node("node1", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 10f, false),
                node("node2", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE, 10f, false),
                node("node3", ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD, 10f, false),
                node("node4", ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD, 10f, false)));

        service.refresh();

        var summary = service.getClusterSummary();
        assertEquals(2, summary.getHealthyNodes());
        assertEquals(2, summary.getOfflineNodes());
        assertEquals(50.0, summary.getHealthPercent(), 0.01);
    }
}
