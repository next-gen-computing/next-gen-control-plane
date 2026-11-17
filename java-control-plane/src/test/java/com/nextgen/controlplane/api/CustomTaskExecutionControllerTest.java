package com.nextgen.controlplane.api;

import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.db.AuditRepository;
import com.nextgen.controlplane.db.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CustomTaskExecutionControllerTest {

    private CustomTaskExecutionController controller;
    private TaskRepository taskRepository;
    private AuditRepository auditRepository;
    private NodeRegistry nodeRegistry;

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(TaskRepository.class);
        auditRepository = Mockito.mock(AuditRepository.class);
        nodeRegistry = Mockito.mock(NodeRegistry.class);
        controller = new CustomTaskExecutionController(taskRepository, auditRepository, nodeRegistry);
    }

    @Test
    void testExecuteCsvDatasetAnalysis() {
        CustomTaskExecutionController.CustomTaskRequest request = new CustomTaskExecutionController.CustomTaskRequest();
        request.targetNodeId = "test-node-analytics";
        request.entrypoint = "data.csv";
        request.files = List.of(
                new CustomTaskExecutionController.TaskFileItem(
                        "data.csv",
                        "id,metric_a,metric_b\n1,10.5,20.2\n2,30.1,40.9\n"
                )
        );

        ResponseEntity<Map<String, Object>> response = controller.executeCustomTask(request);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        assertTrue((Boolean) body.get("ok"));
        assertEquals("COMPLETED", body.get("status"));
        String stdout = (String) body.get("stdout");
        assertTrue(stdout.contains("Total Lines: 3"));
        assertTrue(stdout.contains("Header / Columns: id,metric_a,metric_b"));
        assertNotNull(body.get("sha256"));
    }

    @Test
    void testExecuteGenericDataFile() {
        CustomTaskExecutionController.CustomTaskRequest request = new CustomTaskExecutionController.CustomTaskRequest();
        request.targetNodeId = "test-node-file";
        request.entrypoint = "payload.bin";
        request.files = List.of(
                new CustomTaskExecutionController.TaskFileItem(
                        "payload.bin",
                        "BINARY_RAW_CONTENT_TEST"
                )
        );

        ResponseEntity<Map<String, Object>> response = controller.executeCustomTask(request);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        assertTrue((Boolean) body.get("ok"));
        assertEquals("COMPLETED", body.get("status"));
        String stdout = (String) body.get("stdout");
        assertTrue(stdout.contains("Processed file: payload.bin"));
    }
}
