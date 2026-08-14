package com.nextgen.controlplane.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.controlplane.task.TaskStateDomain;
import com.nextgen.proto.ControlPlaneProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only JSONL sink for real (node properties + allocated share &rarr; duration + outcome)
 * examples — the dataset a future learned throughput/capacity model would train on, mirroring
 * {@link RiskOutcomeLogger}'s discipline exactly. Fires once per sub-task terminal report, from
 * {@link com.nextgen.controlplane.job.JobCoordinator#onSubTaskTerminal}.
 *
 * <p>The node snapshot is read at the moment the terminal event is processed, not literally frozen at
 * dispatch time — {@code TaskRecord} carries no dispatch-time capability snapshot field, and adding one
 * is a larger change than this logger's scope. A known approximation, not silently assumed exact.
 */
public final class JobOutcomeLogger {
    private static final Logger LOG = LoggerFactory.getLogger(JobOutcomeLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code null} for the {@link #noop()} instance — every {@link #log} call is then a no-op. */
    private final Path outputFile;

    private JobOutcomeLogger(Path outputFile) {
        this.outputFile = outputFile;
        if (outputFile != null) {
            try {
                Path parent = outputFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                LOG.warn("Could not create parent directory for {}: {}", outputFile, e.getMessage());
            }
        }
    }

    public static JobOutcomeLogger toFile(Path outputFile) {
        return new JobOutcomeLogger(outputFile);
    }

    /** Writes nothing — the default {@link com.nextgen.controlplane.job.JobCoordinator} uses. */
    public static JobOutcomeLogger noop() {
        return new JobOutcomeLogger(null);
    }

    /**
     * Logs one terminal-report record — {@code COMPLETED} or {@code FAILED} — including a
     * since-retried failure, which is still a real, informative data point about that specific dispatch
     * attempt. A no-op for any other state, and for the {@link #noop()} instance. Never throws: a
     * logging failure must not disrupt job reduction.
     */
    public synchronized void log(TaskRecord record, NodeRecord nodeAtCompletion) {
        if (outputFile == null) {
            return;
        }
        if (record.getState() != TaskStateDomain.COMPLETED && record.getState() != TaskStateDomain.FAILED) {
            return;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("taskId", record.getTaskId());
        fields.put("jobId", record.getJobId());
        fields.put("nodeId", record.getAssignedNodeId());
        fields.put("attempt", record.getAttempt());
        fields.put("rangeSize", extractRangeSize(record.getPayloadJson()));
        fields.put("dispatchedAtMillis", record.getDispatchedAtMillis());
        fields.put("completedAtMillis", record.getCompletedAtMillis());
        fields.put("durationMillis", record.getCompletedAtMillis() - record.getDispatchedAtMillis());
        fields.put("outcome", record.getState().name());
        fields.put("errorMessage", record.getError());
        if (nodeAtCompletion != null) {
            fields.put("nodeCapabilities", projectCapabilities(nodeAtCompletion.getCapabilities()));
            fields.put("nodeCpuUsageAtCompletion", nodeAtCompletion.getCpuUsage());
            fields.put("nodeCpuStale", nodeAtCompletion.isCpuStale());
            fields.put("nodeMemoryUsageAtCompletion", nodeAtCompletion.getMemoryUsage());
            fields.put("nodeMemoryStale", nodeAtCompletion.isMemoryStale());
            fields.put("nodeRiskScoreAtCompletion", nodeAtCompletion.getRiskScore());
        }
        appendJsonLine(fields);
    }

    private static long extractRangeSize(String payloadJson) {
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            long start = node.path("range_start").asLong();
            long end = node.path("range_end").asLong();
            return end - start;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Map<String, Object> projectCapabilities(ControlPlaneProto.NodeCapabilities capabilities) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuCores", capabilities.getCpuCores());
        out.put("totalMemoryBytes", capabilities.getTotalMemoryBytes());
        out.put("totalDiskBytes", capabilities.getTotalDiskBytes());
        out.put("osName", capabilities.getOsName());
        out.put("osArch", capabilities.getOsArch());
        return out;
    }

    private void appendJsonLine(Map<String, Object> fields) {
        try {
            String line = MAPPER.writeValueAsString(fields) + System.lineSeparator();
            Files.writeString(outputFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Failed to append job outcome record: {}", e.getMessage());
        }
    }
}
