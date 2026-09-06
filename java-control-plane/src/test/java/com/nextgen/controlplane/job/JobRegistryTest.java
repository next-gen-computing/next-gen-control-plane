package com.nextgen.controlplane.job;

import com.nextgen.controlplane.task.TaskKindDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers JobRegistry's own state-corruption guard — Stage Y's job-side counterpart to
 * {@code TaskRegistryTest}'s {@code createAndQueue} coverage. */
class JobRegistryTest {

    private AtomicLong clockMillis;
    private JobRegistry registry;

    @BeforeEach
    void setUp() {
        clockMillis = new AtomicLong(1_000L);
        registry = new JobRegistry(new ConcurrentHashMap<>(), clockMillis::get);
    }

    @Test
    void createJobStartsInRunningState() {
        JobRecord job = registry.createJob("j1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("j1-0", "j1-1"));

        assertEquals(JobStateDomain.RUNNING, job.getState());
        assertEquals(List.of("j1-0", "j1-1"), job.getTaskIds());
    }

    /** Stage Y: the real bug — an unconditional {@code put} let a retried {@code SubmitJob} call
     * silently reset an already-reduced job's record back to a fresh RUNNING one, discarding its real
     * combined result. */
    @Test
    void createJobDoesNotResetAnAlreadyCompletedJob() {
        registry.createJob("j1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("j1-0"));
        registry.completeJob("j1", JobStateDomain.COMPLETED, "{\"prime_count\":99}");

        JobRecord result = registry.createJob("j1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("j1-0"));

        assertEquals(JobStateDomain.COMPLETED, result.getState(),
                "a retried SubmitJob must not wipe an already-COMPLETED job's real result");
        assertEquals("{\"prime_count\":99}", result.getCombinedResultJson());
    }

    @Test
    void createJobDoesNotResetAnAlreadyDispatchedJobEitherWithDifferentTaskIds() {
        registry.createJob("j1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("j1-0", "j1-1"));

        // A retry might even carry a DIFFERENT sub-task-id plan (e.g. a different split) — the existing
        // record, taskIds included, must still win.
        JobRecord result = registry.createJob("j1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("j1-99"));

        assertEquals(List.of("j1-0", "j1-1"), result.getTaskIds());
    }

    @Test
    void createUpdateJobDoesNotResetAnAlreadyCompletedUpdateJob() {
        registry.createUpdateJob("j2", TaskKindDomain.DOCKER_COMPOSE_SERVICE, List.of("j2-0"), "j1");
        registry.completeJob("j2", JobStateDomain.COMPLETED, "{}");

        JobRecord result = registry.createUpdateJob("j2", TaskKindDomain.DOCKER_COMPOSE_SERVICE,
                List.of("j2-0"), "j1");

        assertEquals(JobStateDomain.COMPLETED, result.getState());
    }

    @Test
    void completeJobIsIdempotentAndAppliesOnlyOnce() {
        registry.createJob("j1", TaskKindDomain.PRIME_COUNT_RANGE, List.of("j1-0"));

        var first = registry.completeJob("j1", JobStateDomain.COMPLETED, "{\"a\":1}");
        var second = registry.completeJob("j1", JobStateDomain.FAILED, "{\"a\":2}");

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty(), "a second completeJob call on an already-terminal job must be a no-op");
        assertEquals(JobStateDomain.COMPLETED, registry.get("j1").orElseThrow().getState());
        assertEquals("{\"a\":1}", registry.get("j1").orElseThrow().getCombinedResultJson());
    }
}
