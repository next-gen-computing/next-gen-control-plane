package com.nextgen.controlplane.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.controlplane.task.TaskRecord;
import com.nextgen.proto.ControlPlaneProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JobOutcomeLogger} — the real (node properties + allocated share &rarr;
 * duration/outcome) training-data sink Stage F adds, mirroring {@link RiskOutcomeLoggerTest}'s style.
 */
class JobOutcomeLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NodeRecord node(String nodeId) {
        ControlPlaneProto.NodeCapabilities capabilities = ControlPlaneProto.NodeCapabilities.newBuilder()
                .setCpuCores(8).setTotalMemoryBytes(16_000_000_000L).build();
        return NodeRecord.fresh(nodeId, "10.0.0.1", 50051, nodeId, capabilities, "1.0.0", 0L)
                .withHeartbeat(20f, true, 30f, true, 100L, 1L);
    }

    private static TaskRecord completed(String taskId, String nodeId, long dispatchedAt, long completedAt) {
        return TaskRecord.queued(taskId, "job1", TaskKindDomain.PRIME_COUNT_RANGE,
                        "{\"range_start\":0,\"range_end\":100}", dispatchedAt)
                .withDispatched(nodeId, dispatchedAt)
                .withCompleted("{\"prime_count\":25}", completedAt);
    }

    private static TaskRecord failed(String taskId, String nodeId, long dispatchedAt, long completedAt) {
        return TaskRecord.queued(taskId, "job1", TaskKindDomain.PRIME_COUNT_RANGE,
                        "{\"range_start\":0,\"range_end\":100}", dispatchedAt)
                .withDispatched(nodeId, dispatchedAt)
                .withFailed("boom", completedAt);
    }

    @Test
    void aCompletedTaskLogsOneLineWithCorrectDurationAndRangeSize(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("job_outcomes.jsonl");
        JobOutcomeLogger logger = JobOutcomeLogger.toFile(outputFile);

        logger.log(completed("job1-0", "node1", 1_000L, 1_490L), node("node1"));

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(1, lines.size());
        JsonNode json = MAPPER.readTree(lines.get(0));
        assertEquals("job1-0", json.get("taskId").asText());
        assertEquals("job1", json.get("jobId").asText());
        assertEquals("node1", json.get("nodeId").asText());
        assertEquals(1, json.get("attempt").asInt());
        assertEquals(100, json.get("rangeSize").asLong());
        assertEquals(490, json.get("durationMillis").asLong());
        assertEquals("COMPLETED", json.get("outcome").asText());
        assertEquals(8, json.get("nodeCapabilities").get("cpuCores").asInt());
    }

    @Test
    void aFailedTaskLogsOneLineWithTheErrorMessage(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("job_outcomes.jsonl");
        JobOutcomeLogger logger = JobOutcomeLogger.toFile(outputFile);

        logger.log(failed("job1-0", "node1", 1_000L, 1_200L), node("node1"));

        JsonNode json = MAPPER.readTree(Files.readAllLines(outputFile).get(0));
        assertEquals("FAILED", json.get("outcome").asText());
        assertEquals("boom", json.get("errorMessage").asText());
    }

    @Test
    void theNoopLoggerWritesNothing(@TempDir Path tempDir) {
        JobOutcomeLogger logger = JobOutcomeLogger.noop();

        assertDoesNotThrow(() -> logger.log(completed("job1-0", "node1", 1_000L, 1_200L), node("node1")));

        assertFalse(Files.exists(tempDir.resolve("job_outcomes.jsonl")));
    }

    @Test
    void aNonTerminalTaskIsNotLogged(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("job_outcomes.jsonl");
        JobOutcomeLogger logger = JobOutcomeLogger.toFile(outputFile);
        TaskRecord dispatched = TaskRecord.queued("job1-0", "job1", TaskKindDomain.PRIME_COUNT_RANGE,
                "{\"range_start\":0,\"range_end\":100}", 1_000L).withDispatched("node1", 1_000L);

        logger.log(dispatched, node("node1"));

        assertFalse(Files.exists(outputFile), "only COMPLETED/FAILED reports are real outcomes worth logging");
    }

    @Test
    void aRetriedTaskLogsTwiceOnceForEachAttempt(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("job_outcomes.jsonl");
        JobOutcomeLogger logger = JobOutcomeLogger.toFile(outputFile);

        TaskRecord firstAttempt = TaskRecord.queued("job1-0", "job1", TaskKindDomain.PRIME_COUNT_RANGE,
                        "{\"range_start\":0,\"range_end\":100}", 1_000L)
                .withDispatched("node1", 1_000L)
                .withFailed("transient failure", 1_100L);
        logger.log(firstAttempt, node("node1"));

        TaskRecord secondAttempt = firstAttempt.withDispatched("node2", 1_200L)
                .withCompleted("{\"prime_count\":25}", 1_600L);
        logger.log(secondAttempt, node("node2"));

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(2, lines.size());
        assertEquals(1, MAPPER.readTree(lines.get(0)).get("attempt").asInt());
        assertEquals("FAILED", MAPPER.readTree(lines.get(0)).get("outcome").asText());
        assertEquals(2, MAPPER.readTree(lines.get(1)).get("attempt").asInt());
        assertEquals("COMPLETED", MAPPER.readTree(lines.get(1)).get("outcome").asText());
    }

    @Test
    void aMissingNodeSnapshotStillLogsCleanlyWithoutNodeFields(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("job_outcomes.jsonl");
        JobOutcomeLogger logger = JobOutcomeLogger.toFile(outputFile);

        assertDoesNotThrow(() -> logger.log(completed("job1-0", "node1", 1_000L, 1_200L), null));

        JsonNode json = MAPPER.readTree(Files.readAllLines(outputFile).get(0));
        assertTrue(json.get("nodeCapabilities") == null, "no node snapshot means no node fields, not fabricated ones");
    }
}
