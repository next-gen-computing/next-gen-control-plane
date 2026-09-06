package com.nextgen.controlplane.persistence;

import com.nextgen.controlplane.job.JobRecord;
import com.nextgen.controlplane.job.JobRegistry;
import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real file I/O throughout (no mocking) — matches this project's established discipline. Proves the
 * actual acceptance scenario the project plan names for Stage HH: submit a task, let it reach a real
 * terminal state, "restart" (fresh registries built from a loaded snapshot), and confirm the state
 * survived — not just that individual fields round-trip in isolation.
 */
class RegistrySnapshotStoreTest {

    @Test
    void aCompletedTaskAndItsJobSurviveARealSnapshotAndReloadRoundTrip(@TempDir Path tempDir) {
        TaskRegistry taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), () -> 5000L);
        JobRegistry jobRegistry = new JobRegistry(new ConcurrentHashMap<>(), () -> 5000L);

        taskRegistry.createAndQueue("t1", "job1", TaskKindDomain.PRIME_COUNT_RANGE, "{\"range_start\":0,\"range_end\":100}");
        taskRegistry.markDispatched("t1", "node-a");
        taskRegistry.markRunning("t1", "node-a");
        taskRegistry.markCompleted("t1", "node-a", "{\"prime_count\":25}");
        jobRegistry.createJob("job1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("t1"));
        jobRegistry.completeJob("job1", JobStateDomain.COMPLETED, "{\"prime_count\":25}");

        Path snapshotFile = tempDir.resolve("registry_snapshot.json");
        RegistrySnapshotStore store = new RegistrySnapshotStore(snapshotFile, taskRegistry, jobRegistry, 60_000L);
        store.snapshotNow();

        // "Restart": fresh registries built entirely from what was loaded off disk, exactly how
        // ControlPlaneServer.start() wires this in non-Raft mode.
        RegistrySnapshotStore.Loaded restored = RegistrySnapshotStore.load(snapshotFile);
        TaskRegistry restoredTasks = new TaskRegistry(restored.tasks(), () -> 6000L);
        JobRegistry restoredJobs = new JobRegistry(restored.jobs(), () -> 6000L);

        TaskRecord task = restoredTasks.get("t1").orElseThrow();
        assertEquals("COMPLETED", task.getState().name());
        assertEquals("node-a", task.getAssignedNodeId());
        assertEquals("{\"prime_count\":25}", task.getResultJson());
        assertEquals("job1", task.getJobId());
        assertEquals(1, task.getAttempt());

        JobRecord job = restoredJobs.get("job1").orElseThrow();
        assertEquals(JobStateDomain.COMPLETED, job.getState());
        assertEquals("{\"prime_count\":25}", job.getCombinedResultJson());
        assertEquals(List.of("t1"), job.getTaskIds());
    }

    @Test
    void aDispatchedInFlightTaskSurvivesWithItsRealAssignedNode(@TempDir Path tempDir) {
        TaskRegistry taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), () -> 1000L);
        JobRegistry jobRegistry = new JobRegistry(new ConcurrentHashMap<>(), () -> 1000L);
        taskRegistry.createAndQueue("t2", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        taskRegistry.markDispatched("t2", "node-b");

        Path snapshotFile = tempDir.resolve("registry_snapshot.json");
        new RegistrySnapshotStore(snapshotFile, taskRegistry, jobRegistry, 60_000L).snapshotNow();

        RegistrySnapshotStore.Loaded restored = RegistrySnapshotStore.load(snapshotFile);
        TaskRecord task = new TaskRegistry(restored.tasks(), () -> 2000L).get("t2").orElseThrow();
        assertEquals("DISPATCHED", task.getState().name());
        assertEquals("node-b", task.getAssignedNodeId());
    }

    @Test
    void loadingAMissingSnapshotFileReturnsEmptyMapsRatherThanThrowing(@TempDir Path tempDir) {
        Path neverWritten = tempDir.resolve("does-not-exist.json");

        RegistrySnapshotStore.Loaded loaded = RegistrySnapshotStore.load(neverWritten);

        assertTrue(loaded.tasks().isEmpty());
        assertTrue(loaded.jobs().isEmpty());
    }

    @Test
    void loadingACorruptedSnapshotFileReturnsEmptyMapsRatherThanCrashingStartup(@TempDir Path tempDir)
            throws Exception {
        Path corrupted = tempDir.resolve("registry_snapshot.json");
        Files.writeString(corrupted, "{ this is not valid json at all");

        RegistrySnapshotStore.Loaded loaded = RegistrySnapshotStore.load(corrupted);

        assertTrue(loaded.tasks().isEmpty());
        assertTrue(loaded.jobs().isEmpty());
    }

    @Test
    void snapshotNowOverwritesAPreviousSnapshotAtomicallyWithoutLeavingATempFileBehind(@TempDir Path tempDir) {
        TaskRegistry taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), () -> 1000L);
        JobRegistry jobRegistry = new JobRegistry(new ConcurrentHashMap<>(), () -> 1000L);
        Path snapshotFile = tempDir.resolve("registry_snapshot.json");
        RegistrySnapshotStore store = new RegistrySnapshotStore(snapshotFile, taskRegistry, jobRegistry, 60_000L);

        taskRegistry.createAndQueue("t3", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        store.snapshotNow();
        taskRegistry.markCompleted("t3", "", "{}"); // no assigned node -> fenced, but proves a SECOND write works
        store.snapshotNow();

        assertTrue(Files.exists(snapshotFile));
        assertTrue(Files.notExists(tempDir.resolve("registry_snapshot.json.tmp")),
                "the atomic-move temp file must never linger after a successful snapshot");
    }

    @Test
    void aRetriedSubTasksRetryHistorySurvivesTheRoundTrip(@TempDir Path tempDir) {
        TaskRegistry taskRegistry = new TaskRegistry(new ConcurrentHashMap<>(), () -> 1000L);
        JobRegistry jobRegistry = new JobRegistry(new ConcurrentHashMap<>(), () -> 1000L);
        taskRegistry.createAndQueue("t4", "job2", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        jobRegistry.createJob("job2", TaskKindDomain.PRIME_COUNT_RANGE, List.of("t4"));
        jobRegistry.markTaskRetried("job2", "t4");

        Path snapshotFile = tempDir.resolve("registry_snapshot.json");
        new RegistrySnapshotStore(snapshotFile, taskRegistry, jobRegistry, 60_000L).snapshotNow();

        RegistrySnapshotStore.Loaded restored = RegistrySnapshotStore.load(snapshotFile);
        JobRecord job = new JobRegistry(restored.jobs(), () -> 2000L).get("job2").orElseThrow();
        assertTrue(job.hasBeenRetried("t4"), "retry history must survive a snapshot/reload round trip");
    }
}
