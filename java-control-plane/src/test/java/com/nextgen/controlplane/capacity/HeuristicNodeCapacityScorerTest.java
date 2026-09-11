package com.nextgen.controlplane.capacity;

import com.nextgen.controlplane.NodeRecord;
import com.nextgen.proto.ControlPlaneProto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link HeuristicNodeCapacityScorer} — the Stage F capability-splitting weight. */
class HeuristicNodeCapacityScorerTest {

    private final HeuristicNodeCapacityScorer scorer = new HeuristicNodeCapacityScorer();

    private static NodeRecord freshNode(String nodeId, int cpuCores, long totalMemoryBytes) {
        ControlPlaneProto.NodeCapabilities capabilities = ControlPlaneProto.NodeCapabilities.newBuilder()
                .setCpuCores(cpuCores)
                .setTotalMemoryBytes(totalMemoryBytes)
                .build();
        return NodeRecord.fresh(nodeId, "10.0.0.1", 50051, nodeId, capabilities, "1.0.0", 0L);
    }

    @Test
    void equalCapabilityNodesScoreEqually() {
        NodeRecord a = freshNode("a", 4, 8_000_000_000L);
        NodeRecord b = freshNode("b", 4, 8_000_000_000L);

        assertEquals(scorer.scoreCapacity(a), scorer.scoreCapacity(b), 1e-9);
    }

    @Test
    void moreCoresAndMemoryScoreHigher() {
        NodeRecord weak = freshNode("weak", 2, 4_000_000_000L);
        NodeRecord strong = freshNode("strong", 16, 32_000_000_000L);

        assertTrue(scorer.scoreCapacity(strong) > scorer.scoreCapacity(weak),
                "a node with 8x the cores and memory must score higher");
    }

    @Test
    void highCurrentUsageLowersScoreButNeverBelowTheHeadroomFloor() {
        NodeRecord idle = freshNode("idle", 4, 8_000_000_000L)
                .withHeartbeat(1f, true, 1f, true, 1L, 1L);
        NodeRecord loaded = freshNode("loaded", 4, 8_000_000_000L)
                .withHeartbeat(99f, true, 99f, true, 1L, 1L);

        double idleScore = scorer.scoreCapacity(idle);
        double loadedScore = scorer.scoreCapacity(loaded);

        assertTrue(loadedScore < idleScore, "a nearly-saturated node must score lower than an idle one");
        assertTrue(loadedScore > 0, "a loaded node must still receive a strictly positive, non-zero weight");
    }

    @Test
    void staleReadingsAreNeitherTreatedAsIdleNorAsFullyLoaded() {
        NodeRecord idle = freshNode("idle", 4, 8_000_000_000L)
                .withHeartbeat(0f, true, 0f, true, 1L, 1L);
        NodeRecord loaded = freshNode("loaded", 4, 8_000_000_000L)
                .withHeartbeat(100f, true, 100f, true, 1L, 1L);
        // A never-heartbeated node is stale by construction (NodeRecord.fresh marks cpu/memory stale).
        NodeRecord stale = freshNode("stale", 4, 8_000_000_000L);

        double idleScore = scorer.scoreCapacity(idle);
        double loadedScore = scorer.scoreCapacity(loaded);
        double staleScore = scorer.scoreCapacity(stale);

        assertTrue(staleScore < idleScore, "stale must not be treated as confirmed-idle");
        assertTrue(staleScore > loadedScore, "stale must not be treated as confirmed-fully-loaded");
    }

    @Test
    void anAtRiskNodeScoresLowerButNeverReceivesAZeroWeight() {
        NodeRecord healthy = freshNode("healthy", 4, 8_000_000_000L)
                .withHeartbeat(10f, true, 10f, true, 1L, 1L);
        NodeRecord atRisk = freshNode("at-risk", 4, 8_000_000_000L)
                .withHeartbeat(10f, true, 10f, true, 1L, 1L)
                .withRisk(1.0, true, List.of("battery_low"), 2L);

        double healthyScore = scorer.scoreCapacity(healthy);
        double atRiskScore = scorer.scoreCapacity(atRisk);

        assertTrue(atRiskScore < healthyScore, "maximal risk must reduce the node's share");
        assertTrue(atRiskScore > 0, "an at-risk node must still receive some work, never zero");
    }

    @Test
    void unreportedCapabilitiesFallBackToOneCoreAndOneGigabyteRatherThanZero() {
        NodeRecord unreported = NodeRecord.fresh("unreported", "10.0.0.1", 50051, "unreported",
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "1.0.0", 0L);
        NodeRecord explicitOneAndOne = freshNode("explicit", 1, 1_000_000_000L);

        assertEquals(scorer.scoreCapacity(explicitOneAndOne), scorer.scoreCapacity(unreported), 1e-9,
                "an unreported capabilities message must score identically to an explicit 1 core / 1GB node");
        assertTrue(scorer.scoreCapacity(unreported) > 0, "unreported capabilities must never zero the weight");
    }
}
