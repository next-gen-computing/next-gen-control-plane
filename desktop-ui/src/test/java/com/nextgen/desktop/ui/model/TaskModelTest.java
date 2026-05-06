package com.nextgen.desktop.ui.model;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskModel.
 */
class TaskModelTest {

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
    }

    @Test
    void testTaskCreation() {
        TaskModel task = new TaskModel("task-1", TaskModel.TaskType.MATRIX_MULTIPLICATION, "1024");
        assertEquals("task-1", task.getId());
        assertEquals(TaskModel.TaskType.MATRIX_MULTIPLICATION, task.getType());
        assertEquals("1024", task.getPayload());
        assertEquals(TaskModel.TaskStatus.PENDING, task.getStatus());
    }

    @Test
    void testTypeDisplayName() {
        assertEquals("Matrix Multiplication",
                new TaskModel("t", TaskModel.TaskType.MATRIX_MULTIPLICATION, "").getTypeDisplayName());
        assertEquals("Large Array Sum",
                new TaskModel("t", TaskModel.TaskType.LARGE_ARRAY_SUM, "").getTypeDisplayName());
        assertEquals("Prime Counter",
                new TaskModel("t", TaskModel.TaskType.PRIME_COUNTER, "").getTypeDisplayName());
    }

    @Test
    void testStatusDisplayName() {
        TaskModel task = new TaskModel("t", TaskModel.TaskType.MATRIX_MULTIPLICATION, "");
        assertEquals("Pending", task.getStatusDisplayName());

        task.setStatus(TaskModel.TaskStatus.RUNNING);
        assertEquals("Running", task.getStatusDisplayName());

        task.setStatus(TaskModel.TaskStatus.COMPLETED);
        assertEquals("Completed", task.getStatusDisplayName());

        task.setStatus(TaskModel.TaskStatus.FAILED);
        assertEquals("Failed", task.getStatusDisplayName());
    }

    @Test
    void testProgressTracking() {
        TaskModel task = new TaskModel("t", TaskModel.TaskType.LARGE_ARRAY_SUM, "");
        assertEquals(0.0, task.getProgress());

        task.setProgress(50.0);
        assertEquals(50.0, task.getProgress());

        task.setResult("sum=12345");
        assertEquals("sum=12345", task.getResult());
    }
}
