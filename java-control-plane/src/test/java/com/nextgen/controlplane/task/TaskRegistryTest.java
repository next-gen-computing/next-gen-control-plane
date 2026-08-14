package com.nextgen.controlplane.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers TaskRegistry's state transitions and the fencing rule that makes proactive migration
 * (Stage D) safe: a report from a node that no longer owns a task must be dropped, not applied.
 */
class TaskRegistryTest {

    private AtomicLong clockMillis;
    private TaskRegistry registry;

    @BeforeEach
    void setUp() {
        clockMillis = new AtomicLong(1_000L);
        registry = new TaskRegistry(new ConcurrentHashMap<>(), clockMillis::get);
    }

    @Test
    void createAndQueueStartsInQueuedStateWithNoNodeAssigned() {
        TaskRecord record = registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        assertEquals(TaskStateDomain.QUEUED, record.getState());
        assertEquals("", record.getAssignedNodeId());
        assertEquals(0, record.getAttempt());
        assertFalse(record.hasJob());
    }

    @Test
    void createIfAbsentCreatesAFreshRecordWhenNoneExists() {
        TaskRecord record = registry.createIfAbsent("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        assertEquals(TaskStateDomain.QUEUED, record.getState());
        assertEquals("", record.getAssignedNodeId());
    }

    @Test
    void createIfAbsentDoesNotResetAnAlreadyDispatchedTask() {
        registry.createIfAbsent("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node-a");

        TaskRecord result = registry.createIfAbsent("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        assertEquals(TaskStateDomain.DISPATCHED, result.getState(),
                "a retried create must not reset real progress back to QUEUED");
        assertEquals("node-a", result.getAssignedNodeId());
    }

    @Test
    void markDispatchedAssignsNodeAndBumpsAttempt() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        Optional<TaskRecord> dispatched = registry.markDispatched("t1", "node1");

        assertTrue(dispatched.isPresent());
        assertEquals("node1", dispatched.get().getAssignedNodeId());
        assertEquals(TaskStateDomain.DISPATCHED, dispatched.get().getState());
        assertEquals(1, dispatched.get().getAttempt());
    }

    @Test
    void markRunningAppliesWhenReportingNodeOwnsTheTask() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");

        Optional<TaskRecord> running = registry.markRunning("t1", "node1");

        assertTrue(running.isPresent());
        assertEquals(TaskStateDomain.RUNNING, running.get().getState());
    }

    @Test
    void markRunningIsDroppedWhenReportingNodeDoesNotOwnTheTask() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");

        // node2 never had this task — e.g. it's a stray/duplicate message, or an attacker guessing IDs.
        Optional<TaskRecord> result = registry.markRunning("t1", "node2");

        assertTrue(result.isEmpty(), "a report from a non-owning node must be dropped");
        assertEquals(TaskStateDomain.DISPATCHED, registry.get("t1").orElseThrow().getState(),
                "the real state must be untouched by the stale report");
    }

    @Test
    void markCompletedIsDroppedAfterTaskWasReassignedToAnotherNode() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");

        // Simulate a proactive migration: the task is redispatched to node2 while node1's old
        // execution is still in flight.
        registry.markDispatched("t1", "node2");

        // node1's late result must not clobber whatever node2 eventually reports.
        Optional<TaskRecord> stale = registry.markCompleted("t1", "node1", "{\"prime_count\":999}");

        assertTrue(stale.isEmpty(), "a late result from the task's PREVIOUS node must be dropped");
        assertEquals("node2", registry.get("t1").orElseThrow().getAssignedNodeId());
        assertEquals(TaskStateDomain.DISPATCHED, registry.get("t1").orElseThrow().getState());
    }

    @Test
    void markCompletedAppliesResultAndTimestampWhenOwningNodeReports() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");
        clockMillis.set(5_000L);

        Optional<TaskRecord> completed = registry.markCompleted("t1", "node1", "{\"prime_count\":168}");

        assertTrue(completed.isPresent());
        assertEquals(TaskStateDomain.COMPLETED, completed.get().getState());
        assertEquals("{\"prime_count\":168}", completed.get().getResultJson());
        assertEquals(5_000L, completed.get().getCompletedAtMillis());
        assertTrue(completed.get().getState().isTerminal());
    }

    @Test
    void markFailedIsDroppedWhenReportingNodeDoesNotOwnTheTask() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");

        Optional<TaskRecord> result = registry.markFailed("t1", "node2", "boom");

        assertTrue(result.isEmpty());
        assertEquals(TaskStateDomain.DISPATCHED, registry.get("t1").orElseThrow().getState());
    }

    @Test
    void markFailedAppliesWhenReportingNodeOwnsTheTask() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");

        Optional<TaskRecord> failed = registry.markFailed("t1", "node1", "out of memory");

        assertTrue(failed.isPresent());
        assertEquals(TaskStateDomain.FAILED, failed.get().getState());
        assertEquals("out of memory", failed.get().getError());
    }

    @Test
    void anEmptyAssignedNodeIdFencesAFreshlyQueuedTaskAgainstAnEmptyReportingNodeId() {
        // TaskDispatcher relies on this: failing a never-dispatched task calls markFailed(id, "", ...)
        // directly, matching the "" assignedNodeId a freshly-queued record already has.
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        Optional<TaskRecord> failed = registry.markFailed("t1", "", "no channel");

        assertTrue(failed.isPresent());
        assertEquals(TaskStateDomain.FAILED, failed.get().getState());
    }

    @Test
    void getReturnsEmptyForUnknownTask() {
        assertTrue(registry.get("ghost").isEmpty());
    }

    @Test
    void snapshotReturnsAllTasksNewestFirst() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        clockMillis.set(2_000L);
        registry.createAndQueue("t2", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        clockMillis.set(3_000L);
        registry.createAndQueue("t3", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");

        List<TaskRecord> snapshot = registry.snapshot();

        assertEquals(3, snapshot.size());
        assertEquals("t3", snapshot.get(0).getTaskId());
        assertEquals("t2", snapshot.get(1).getTaskId());
        assertEquals("t1", snapshot.get(2).getTaskId());
    }

    @Test
    void mutatingAnUnknownTaskIdIsANoOp() {
        assertTrue(registry.markDispatched("ghost", "node1").isEmpty());
        assertTrue(registry.markRunning("ghost", "node1").isEmpty());
        assertTrue(registry.markCompleted("ghost", "node1", "{}").isEmpty());
        assertTrue(registry.markFailed("ghost", "node1", "err").isEmpty());
    }

    // ── tasksOnNode / markMigrating (Stage D) ──────────────────────────────────

    @Test
    void tasksOnNodeReturnsOnlyDispatchedAndRunningTasksForThatNode() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");
        registry.createAndQueue("t2", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t2", "node1");
        registry.markRunning("t2", "node1");
        registry.createAndQueue("t3", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t3", "node2"); // different node

        List<TaskRecord> onNode1 = registry.tasksOnNode("node1");

        assertEquals(2, onNode1.size());
        assertTrue(onNode1.stream().anyMatch(t -> t.getTaskId().equals("t1")));
        assertTrue(onNode1.stream().anyMatch(t -> t.getTaskId().equals("t2")));
    }

    @Test
    void tasksOnNodeExcludesQueuedAndTerminalTasks() {
        registry.createAndQueue("queued", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}"); // never dispatched
        registry.createAndQueue("completed", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("completed", "node1");
        registry.markCompleted("completed", "node1", "{}");
        registry.createAndQueue("failed", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("failed", "node1");
        registry.markFailed("failed", "node1", "boom");

        assertTrue(registry.tasksOnNode("node1").isEmpty(),
                "a queued (no node yet) or terminal task must never be reported as in-flight on a node");
    }

    @Test
    void tasksOnNodeForANodeWithNothingAssignedIsEmpty() {
        assertTrue(registry.tasksOnNode("ghost").isEmpty());
    }

    @Test
    void markMigratingTransitionsToMigratingState() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");

        Optional<TaskRecord> migrating = registry.markMigrating("t1");

        assertTrue(migrating.isPresent());
        assertEquals(TaskStateDomain.MIGRATING, migrating.get().getState());
        assertEquals("node1", migrating.get().getAssignedNodeId(), "still (briefly) attributed to the old node");
    }

    @Test
    void markMigratingOnAnUnknownTaskIsANoOp() {
        assertTrue(registry.markMigrating("ghost").isEmpty());
    }

    @Test
    void aMigratingTaskCanStillBeRedispatchedToANewNode() {
        registry.createAndQueue("t1", "", TaskKindDomain.PRIME_COUNT_RANGE, "{}");
        registry.markDispatched("t1", "node1");
        registry.markMigrating("t1");

        Optional<TaskRecord> redispatched = registry.markDispatched("t1", "node2");

        assertTrue(redispatched.isPresent());
        assertEquals("node2", redispatched.get().getAssignedNodeId());
        assertEquals(TaskStateDomain.DISPATCHED, redispatched.get().getState());
        assertEquals(2, redispatched.get().getAttempt(), "the migration redispatch must bump attempt");
    }
}
