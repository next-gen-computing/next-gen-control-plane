package com.nextgen.controlplane.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.NodeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RiskOutcomeLogger} — the real (signal snapshot &rarr; eventual outcome)
 * training-data sink Stage E of the project plan adds.
 */
class RiskOutcomeLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NodeRegistry.StatusTransition transition(String nodeId) {
        return new NodeRegistry.StatusTransition(nodeId, NodeStatus.ALIVE, NodeStatus.SUSPECTED_DEAD);
    }

    private static NodeHistory.Sample sample(long atMillis) {
        return new NodeHistory.Sample(atMillis,
                55.0f, true, 60.0f, true,
                20.0f, true, false, true, false, true,
                0.25, true);
    }

    @Test
    void oneCallWritesExactlyOneJsonLineThatRoundTrips(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger logger = new RiskOutcomeLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "10.0.0.5", 50051, "host1", null, "v1", 1_000L)
                .withRisk(0.7, true, List.of("battery_low"), 1_500L);

        logger.logSuspectedDeath(transition("node1"), node, List.of(sample(900L), sample(950L)));

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(1, lines.size());
        JsonNode json = MAPPER.readTree(lines.get(0));
        assertEquals("node1", json.get("nodeId").asText());
        assertEquals("ALIVE", json.get("fromStatus").asText());
        assertEquals("SUSPECTED_DEAD", json.get("toStatus").asText());
        assertEquals(0.7, json.get("lastRiskScore").asDouble(), 1e-9);
        assertTrue(json.get("wasAtRisk").asBoolean());
        assertEquals("battery_low", json.get("riskReasons").get(0).asText());
        assertEquals(2, json.get("recentHistory").size());
    }

    @Test
    void twoCallsAppendTwoLinesAndTheFirstLineIsUnchanged(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger logger = new RiskOutcomeLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L);

        logger.logSuspectedDeath(transition("node1"), node, List.of());
        String firstLineAfterFirstCall = Files.readAllLines(outputFile).get(0);
        logger.logSuspectedDeath(transition("node1"), node, List.of());

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(2, lines.size());
        assertEquals(firstLineAfterFirstCall, lines.get(0));
    }

    @Test
    void recentHistorySerializesInTheSameOldestFirstOrder(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger logger = new RiskOutcomeLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L);

        logger.logSuspectedDeath(transition("node1"), node, List.of(sample(100L), sample(200L), sample(300L)));

        JsonNode history = MAPPER.readTree(Files.readAllLines(outputFile).get(0)).get("recentHistory");
        assertEquals(100L, history.get(0).get("recordedAtMillis").asLong());
        assertEquals(200L, history.get(1).get("recordedAtMillis").asLong());
        assertEquals(300L, history.get(2).get("recordedAtMillis").asLong());
    }

    @Test
    void aNodeWithNoRiskAssessmentYetLogsCleanly(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger logger = new RiskOutcomeLogger(outputFile);
        NodeRecord neverAssessed = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L);

        assertDoesNotThrow(() -> logger.logSuspectedDeath(transition("node1"), neverAssessed, List.of()));

        JsonNode json = MAPPER.readTree(Files.readAllLines(outputFile).get(0));
        assertEquals(0.0, json.get("lastRiskScore").asDouble());
        assertFalse(json.get("wasAtRisk").asBoolean());
        assertEquals(0, json.get("riskReasons").size());
        assertEquals(0L, json.get("riskAssessedAtMillis").asLong());
    }

    @Test
    void parentDirectoryIsAutoCreatedWhenMissing(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("nested/does/not/exist/outcomes.jsonl");
        RiskOutcomeLogger logger = new RiskOutcomeLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L);

        logger.logSuspectedDeath(transition("node1"), node, List.of());

        assertTrue(Files.exists(outputFile));
        assertEquals(1, Files.readAllLines(outputFile).size());
    }

    @Test
    void concurrentCallsProduceCompleteNonInterleavedLines(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("outcomes.jsonl");
        RiskOutcomeLogger logger = new RiskOutcomeLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L)
                .withRisk(0.5, true, List.of("a", "b", "c"), 10L);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    logger.logSuspectedDeath(transition("node" + idx), node, List.of(sample(idx)));
                });
            }
            ready.await();
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(threads, lines.size());
        for (String line : lines) {
            assertDoesNotThrow(() -> MAPPER.readTree(line), "each line must be complete, valid JSON");
        }
    }
}
