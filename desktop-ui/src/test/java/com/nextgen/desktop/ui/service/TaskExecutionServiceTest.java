package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.TaskModel;
import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskExecutionService.
 */
class TaskExecutionServiceTest {

    @TempDir
    static Path tempDataDir;

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
        // Keeps this test's history writes out of the real ~/.nextgen/desktop directory.
        System.setProperty("NEXTGEN_DESKTOP_DATA_DIR", tempDataDir.toString());
    }

    @AfterAll
    static void clearDataDirOverride() {
        System.clearProperty("NEXTGEN_DESKTOP_DATA_DIR");
    }

    // Each created service's background executor keeps polling/writing history asynchronously until
    // shut down — left running past a test method's return, it can still be mid-write to a file under
    // tempDataDir when @TempDir tries to delete that directory at class teardown, which fails outright
    // on Windows (can't delete a file another thread still has open). Tracked here and shut down in
    // @AfterEach so no created service ever outlives its own test.
    private final List<TaskExecutionService> createdServices = new ArrayList<>();

    @AfterEach
    void shutdownCreatedServices() {
        createdServices.forEach(TaskExecutionService::shutdown);
        createdServices.clear();
    }

    private TaskExecutionService createService() {
        TaskExecutionService service = new TaskExecutionService(new GrpcConnectionManager(), new DesktopHistoryStore());
        createdServices.add(service);
        return service;
    }

    @Test
    void testSubmitTask() {
        TaskExecutionService service = createService();

        TaskModel task = service.submitTask(0, 1000);

        assertNotNull(task);
        assertNotNull(task.getId());
        assertEquals(TaskModel.TaskType.PRIME_COUNT_RANGE, task.getType());
        assertEquals("{\"range_start\":0,\"range_end\":1000}", task.getPayload());
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

        service.submitTask(0, 1000);
        service.submitTask(1000, 2000);

        assertEquals(2, service.getTasks().size());
    }
}
