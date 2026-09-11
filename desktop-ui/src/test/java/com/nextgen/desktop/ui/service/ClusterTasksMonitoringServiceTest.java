package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.server.dto.ClusterTaskDto;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClusterTasksMonitoringService} — mirrors {@link NodeMonitoringServiceTest}'s
 * mocked-{@code ControlPlaneClient} pattern (the closest existing template; no test previously existed
 * for the structurally-identical {@code DockerResourcesMonitoringService} either).
 */
class ClusterTasksMonitoringServiceTest {

    private GrpcConnectionManager connectionManager;
    private ControlPlaneClient client;
    private ClusterTasksMonitoringService service;

    private static ControlPlaneProto.TaskStatusResponse task(String taskId, String jobId,
                                                              ControlPlaneProto.TaskState state, String node) {
        return ControlPlaneProto.TaskStatusResponse.newBuilder()
                .setTaskId(taskId)
                .setJobId(jobId)
                .setState(state)
                .setAssignedNode(node)
                .setKind(ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE)
                .setAttempt(1)
                .build();
    }

    @BeforeEach
    void setUp() {
        connectionManager = mock(GrpcConnectionManager.class);
        client = mock(ControlPlaneClient.class);
        when(connectionManager.getControlPlaneClient()).thenReturn(client);
        service = new ClusterTasksMonitoringService(connectionManager);
    }

    @Test
    void successfulPollPopulatesTheClusterWideTaskList() {
        when(client.listAllTasks()).thenReturn(List.of(
                task("t1", "", ControlPlaneProto.TaskState.TASK_STATE_RUNNING, "node1"),
                task("t2", "job1", ControlPlaneProto.TaskState.TASK_STATE_COMPLETED, "node2")));

        service.refresh();

        assertEquals(2, service.getTasks().size());
        ClusterTaskDto t1 = service.getTasks().stream().filter(t -> t.taskId().equals("t1")).findFirst().orElseThrow();
        assertEquals("node1", t1.assignedNodeId());
        assertEquals("RUNNING", t1.state());
        assertEquals("PRIME_COUNT_RANGE", t1.kind());
    }

    @Test
    void rpcFailureLeavesTheLastKnownListInPlace() {
        when(client.listAllTasks()).thenReturn(List.of(
                task("t1", "", ControlPlaneProto.TaskState.TASK_STATE_RUNNING, "node1")));
        service.refresh();
        assertEquals(1, service.getTasks().size());

        when(client.listAllTasks()).thenThrow(new ControlPlaneUnavailableException(
                "listAllTasks", new StatusRuntimeException(Status.UNAVAILABLE)));
        service.refresh();

        // Clearing the list on a transient failure would render as "the whole cluster is now idle" —
        // exactly the same false-empty failure mode NodeMonitoringService/DockerResourcesMonitoringService
        // already guard against.
        assertEquals(1, service.getTasks().size(),
                "a failed poll must not be mistaken for an empty/idle cluster");
    }

    @Test
    void missingClientIsANoOpNotACrash() {
        when(connectionManager.getControlPlaneClient()).thenReturn(null);

        assertDoesNotThrow(() -> service.refresh());
        assertEquals(0, service.getTasks().size());
    }

    @Test
    void unexpectedRuntimeErrorDoesNotPropagate() {
        when(client.listAllTasks()).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> service.refresh());
    }
}
