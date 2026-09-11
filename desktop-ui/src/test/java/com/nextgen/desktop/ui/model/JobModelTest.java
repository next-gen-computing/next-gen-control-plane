package com.nextgen.desktop.ui.model;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JobModel.
 */
class JobModelTest {

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
    }

    @Test
    void testJobCreation() {
        JobModel job = new JobModel("job-1", 0, 1000, 4);
        assertEquals("job-1", job.getId());
        assertEquals(0, job.getRangeStart());
        assertEquals(1000, job.getRangeEnd());
        assertEquals(4, job.getSubTaskCount());
        assertEquals(JobModel.JobStatus.RUNNING, job.getStatus());
    }

    @Test
    void testStatusDisplayName() {
        JobModel job = new JobModel("j", 0, 100, 1);
        assertEquals("Running", job.getStatusDisplayName());

        job.setStatus(JobModel.JobStatus.COMPLETED);
        assertEquals("Completed", job.getStatusDisplayName());

        job.setStatus(JobModel.JobStatus.PARTIAL_FAILURE);
        assertEquals("Partial Failure", job.getStatusDisplayName());

        job.setStatus(JobModel.JobStatus.FAILED);
        assertEquals("Failed", job.getStatusDisplayName());
    }

    @Test
    void testSubTaskCounters() {
        JobModel job = new JobModel("j", 0, 100, 5);
        assertEquals(0, job.getCompletedCount());
        assertEquals(0, job.getFailedCount());

        job.setCompletedCount(3);
        job.setFailedCount(1);

        assertEquals(3, job.getCompletedCount());
        assertEquals(1, job.getFailedCount());
    }

    @Test
    void testCombinedResultAndProgress() {
        JobModel job = new JobModel("j", 0, 100, 2);
        assertEquals(0.0, job.getProgress());

        job.setProgress(50.0);
        assertEquals(50.0, job.getProgress());

        job.setCombinedResult("{\"prime_count\":25}");
        assertEquals("{\"prime_count\":25}", job.getCombinedResult());
    }
}
