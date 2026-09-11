package com.nextgen.controlplane.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.risk.RiskScorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RiskSnapshotLogger} — the opt-in, continuous (node properties, risk assessment)
 * training-data sink Stage G adds to backfill the negative-example gap {@link RiskOutcomeLogger} alone
 * cannot close. Mirrors {@link RiskOutcomeLoggerTest}'s style.
 */
class RiskSnapshotLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NodeHistory.Sample sample(long atMillis) {
        return new NodeHistory.Sample(atMillis, 55.0f, true, 60.0f, true,
                20.0f, true, false, true, false, true, 0.25, true);
    }

    @Test
    void oneSnapshotWritesExactlyOneJsonLineThatRoundTrips(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("snapshots.jsonl");
        RiskSnapshotLogger logger = new RiskSnapshotLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "10.0.0.5", 50051, "host1", null, "v1", 1_000L);
        RiskScorer.RiskAssessment assessment = new RiskScorer.RiskAssessment(0.3, false, List.of());

        logger.logSnapshot(node, assessment, List.of(sample(900L), sample(950L)));

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(1, lines.size());
        JsonNode json = MAPPER.readTree(lines.get(0));
        assertEquals("node1", json.get("nodeId").asText());
        assertEquals(0.3, json.get("riskScore").asDouble(), 1e-9);
        assertEquals(false, json.get("atRisk").asBoolean());
        assertEquals("ALIVE", json.get("status").asText());
        assertEquals(2, json.get("recentHistory").size());
    }

    @Test
    void everySweepAppendsAnotherLineRegardlessOfAtRiskStatus(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("snapshots.jsonl");
        RiskSnapshotLogger logger = new RiskSnapshotLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L);

        logger.logSnapshot(node, new RiskScorer.RiskAssessment(0.0, false, List.of()), List.of());
        logger.logSnapshot(node, new RiskScorer.RiskAssessment(0.9, true, List.of("low battery")), List.of());
        logger.logSnapshot(node, new RiskScorer.RiskAssessment(0.1, false, List.of()), List.of());

        List<String> lines = Files.readAllLines(outputFile);
        assertEquals(3, lines.size(), "every sweep must be logged, not just rising-edge or at-risk ones");
        assertTrue(MAPPER.readTree(lines.get(1)).get("atRisk").asBoolean());
    }

    @Test
    void parentDirectoryIsAutoCreatedWhenMissing(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("nested/does/not/exist/snapshots.jsonl");
        RiskSnapshotLogger logger = new RiskSnapshotLogger(outputFile);
        NodeRecord node = NodeRecord.fresh("node1", "ip", 1, "h", null, "v", 0L);

        logger.logSnapshot(node, new RiskScorer.RiskAssessment(0.0, false, List.of()), List.of());

        assertTrue(Files.exists(outputFile));
    }
}
