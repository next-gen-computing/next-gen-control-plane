package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.client.ControlPlaneClient;
import com.nextgen.desktop.ui.client.ControlPlaneUnavailableException;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.model.JobModel;
import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import com.nextgen.desktop.ui.profile.HistoryEntry;
import com.nextgen.proto.ControlPlaneProto;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Submits jobs to the control plane and tracks their real, reduced outcome.
 *
 * <p>Mirrors {@link TaskExecutionService} exactly: {@code SubmitJob} is an acceptance, not a final
 * result, so this service submits then polls {@code GetJobStatus} until the job — every one of its
 * real sub-tasks — reaches a terminal state. There is no client-side splitting or reduction; the
 * control plane does both and this service reports what it says.
 */
public class JobExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(JobExecutionService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final long POLL_INTERVAL_MS = 1_000;
    /** Safety valve: a job must never sit RUNNING in the UI forever with no way out. */
    private static final long MAX_POLL_DURATION_MS = 10 * 60 * 1_000;

    private final GrpcConnectionManager connectionManager;
    private final DesktopHistoryStore historyStore;
    private final ObservableList<JobModel> jobs = FXCollections.observableArrayList();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "job-exec");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> submittedAtByJobId = new java.util.concurrent.ConcurrentHashMap<>();

    public JobExecutionService(GrpcConnectionManager connectionManager, DesktopHistoryStore historyStore) {
        this.connectionManager = connectionManager;
        this.historyStore = historyStore;
    }

    /** Submits a real job: {@code subTaskCount} prime-count-range sub-tasks over {@code [rangeStart, rangeEnd)}. */
    public JobModel submitJob(long rangeStart, long rangeEnd, int subTaskCount) {
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        JobModel job = new JobModel(jobId, rangeStart, rangeEnd, subTaskCount);
        job.setCreatedAt(LocalDateTime.now().format(TIME_FORMATTER));
        job.setStatus(JobModel.JobStatus.RUNNING);

        jobs.add(job);
        String payload = String.format("{\"range_start\":%d,\"range_end\":%d}", rangeStart, rangeEnd);
        recordHistory(job, JobModel.JobStatus.RUNNING, payload, 0L);

        executor.submit(() -> submitAndPoll(job, payload, subTaskCount));
        return job;
    }

    /** Takes status/result explicitly rather than reading {@code job}'s properties back — {@link
     * #finish} updates those via {@code Platform.runLater}, so a synchronous read right after queuing
     * that update would race and could persist the stale pre-completion state. */
    private void recordHistory(JobModel job, JobModel.JobStatus status, String resultOrPayload,
            long completedAtEpochMillis) {
        String typeLabel = job.getSubTaskCount() + " sub-task" + (job.getSubTaskCount() == 1 ? "" : "s")
                + " · prime count [" + job.getRangeStart() + ", " + job.getRangeEnd() + ")";
        historyStore.upsert(new HistoryEntry(
                job.getId(), "job", typeLabel, status.name(),
                clusterLabel(), submittedAtMillis(job), completedAtEpochMillis, resultOrPayload));
    }

    private long submittedAtMillis(JobModel job) {
        return submittedAtByJobId.computeIfAbsent(job.getId(), id -> System.currentTimeMillis());
    }

    private String clusterLabel() {
        return connectionManager.getCurrentHost() + ":" + connectionManager.getCurrentControlPlanePort();
    }

    private void submitAndPoll(JobModel job, String payload, int subTaskCount) {
        ControlPlaneClient client = connectionManager.getControlPlaneClient();
        if (client == null) {
            finish(job, JobModel.JobStatus.FAILED, "No gRPC connection available");
            return;
        }

        try {
            var response = client.submitJob(
                    job.getId(), ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE, payload, subTaskCount);

            if (!response.getResult().startsWith("ACCEPTED")) {
                finish(job, JobModel.JobStatus.FAILED, response.getResult());
                return;
            }

            Platform.runLater(() -> {
                job.setSubTaskCount(response.getSubTaskCount());
                job.setProgress(10.0);
            });
            pollUntilTerminal(job);

        } catch (ControlPlaneUnavailableException e) {
            LOG.warn("Job {} could not be submitted: {}", job.getId(), e.shortReason());
            finish(job, JobModel.JobStatus.FAILED, e.shortReason());
        } catch (Exception e) {
            LOG.error("Job {} failed", job.getId(), e);
            finish(job, JobModel.JobStatus.FAILED, "Error: " + e.getMessage());
        }
    }

    private void pollUntilTerminal(JobModel job) {
        long deadline = System.currentTimeMillis() + MAX_POLL_DURATION_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                var status = connectionManager.getControlPlaneClient().getJobStatus(job.getId());
                int subTaskCount = Math.max(1, status.getSubTaskCount());
                double liveProgress = 100.0 * (status.getCompletedCount() + status.getFailedCount()) / subTaskCount;

                switch (status.getState()) {
                    case JOB_STATE_COMPLETED, JOB_STATE_PARTIAL_FAILURE, JOB_STATE_FAILED -> {
                        JobModel.JobStatus finalStatus = toModelStatus(status.getState());
                        Platform.runLater(() -> {
                            job.setCompletedCount(status.getCompletedCount());
                            job.setFailedCount(status.getFailedCount());
                        });
                        finish(job, finalStatus, status.getCombinedResultJson());
                        return;
                    }
                    default -> Platform.runLater(() -> {
                        job.setCompletedCount(status.getCompletedCount());
                        job.setFailedCount(status.getFailedCount());
                        job.setProgress(Math.max(10.0, liveProgress));
                    });
                }
            } catch (ControlPlaneUnavailableException e) {
                // A transient network hiccup mid-poll must not fail a job that may still be running
                // for real — log and keep trying until MAX_POLL_DURATION_MS gives up honestly.
                LOG.warn("Poll for job {} failed: {}", job.getId(), e.shortReason());
            }
        }

        LOG.warn("Job {} never reached a terminal state within {}ms; giving up", job.getId(), MAX_POLL_DURATION_MS);
        finish(job, JobModel.JobStatus.FAILED, "Polling timed out — control plane never reported completion");
    }

    private static JobModel.JobStatus toModelStatus(ControlPlaneProto.JobState state) {
        return switch (state) {
            case JOB_STATE_COMPLETED -> JobModel.JobStatus.COMPLETED;
            case JOB_STATE_PARTIAL_FAILURE -> JobModel.JobStatus.PARTIAL_FAILURE;
            case JOB_STATE_FAILED -> JobModel.JobStatus.FAILED;
            default -> JobModel.JobStatus.RUNNING;
        };
    }

    private void finish(JobModel job, JobModel.JobStatus status, String combinedResult) {
        Platform.runLater(() -> {
            job.setStatus(status);
            job.setCombinedResult(combinedResult);
            job.setUpdatedAt(LocalDateTime.now().format(TIME_FORMATTER));
            job.setProgress(status == JobModel.JobStatus.RUNNING ? job.getProgress() : 100.0);
        });
        recordHistory(job, status, combinedResult, System.currentTimeMillis());
        LOG.info("Job {} finished: status={}, result={}", job.getId(), status, combinedResult);
    }

    public ObservableList<JobModel> getJobs() {
        return jobs;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
