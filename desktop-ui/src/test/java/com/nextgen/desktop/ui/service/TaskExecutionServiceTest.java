package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.TaskModel;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskExecutionService.
 */
class TaskExecutionServiceTest {

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
    }

    private TaskExecutionService createService() {
        return new TaskExecutionService(new GrpcConnectionManager());
    }

    @Test
    void testSubmitTask() {
        TaskExecutionService service = createService();

        TaskModel task = service.submitTask(TaskModel.TaskType.MATRIX_MULTIPLICATION, "1024");

        assertNotNull(task);
        assertNotNull(task.getId());
        assertEquals(TaskModel.TaskType.MATRIX_MULTIPLICATION, task.getType());
        assertEquals("1024", task.getPayload());
        assertEquals(TaskModel.TaskStatus.PENDING, task.getStatus());

        // Should appear in the list
        assertTrue(service.getTasks().contains(task));
    }

    @Test
    void testTaskListInitiallyEmpty() {
        TaskExecutionService service = createService();
        assertTrue(service.getTasks().isEmpty());
    }

    @Test
    void testMultipleTasks() {
        TaskExecutionService service = createService();

        service.submitTask(TaskModel.TaskType.MATRIX_MULTIPLICATION, "1024");
        service.submitTask(TaskModel.TaskType.PRIME_COUNTER, "1-1000000");

        assertEquals(2, service.getTasks().size());
    }
}
