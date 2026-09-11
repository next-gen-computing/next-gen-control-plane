package com.nextgen.controlplane.risk;

import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RuleBasedRiskScorer — each rule tested in isolation, then combined, then the honest
 * "abstain on missing data" behaviour that must hold for all three.
 */
class RuleBasedRiskScorerTest {

    private static final NodeRecord ANY_NODE = new NodeRecord("node1", "10.0.0.1", 50051, "host1");

    private final RuleBasedRiskScorer scorer = new RuleBasedRiskScorer();

    /** A healthy, unremarkable sample: plugged in, moderate memory, no RTT trend data. */
    private static NodeHistory.Sample healthySample(long atMillis) {
        return new NodeHistory.Sample(atMillis,
                20f, true,
                40f, true,
                80f, true,
                false, true,
                true, true,
                0.01, false);
    }

    @Test
    void emptyHistoryScoresZeroAndIsNeverAtRisk() {
        RiskScorer.RiskAssessment assessment = scorer.score(ANY_NODE, List.of());

        assertEquals(0.0, assessment.riskScore());
        assertFalse(assessment.atRisk());
        assertTrue(assessment.reasons().isEmpty());
    }

    // ── Rule 1: low battery, on battery power ─────────────────────────────────

    @Test
    void lowBatteryOnBatteryPowerAloneTriggersAtRisk() {
        NodeHistory.Sample sample = new NodeHistory.Sample(1000,
                20f, true, 40f, true,
                10f, true, false, true, false, true, // 10% battery, not charging, NOT on AC
                0.01, false);

        RiskScorer.RiskAssessment assessment = scorer.score(ANY_NODE, List.of(sample));

        assertTrue(assessment.atRisk());
        assertEquals(0.5, assessment.riskScore(), 0.001);
        assertEquals(1, assessment.reasons().size());
        assertTrue(assessment.reasons().get(0).contains("battery"));
    }

    @Test
    void highBatteryOnBatteryPowerDoesNotTrigger() {
        NodeHistory.Sample sample = new NodeHistory.Sample(1000,
                20f, true, 40f, true,
                80f, true, false, true, false, true, // 80% battery — well above threshold
                0.01, false);

        assertFalse(scorer.score(ANY_NODE, List.of(sample)).atRisk());
    }

    @Test
    void lowBatteryButOnAcPowerDoesNotTrigger() {
        NodeHistory.Sample sample = new NodeHistory.Sample(1000,
                20f, true, 40f, true,
                10f, true, false, true, true, true, // 10% battery but plugged in
                0.01, false);

        assertFalse(scorer.score(ANY_NODE, List.of(sample)).atRisk());
    }

    @Test
    void lowBatteryWithUnknownAcStateAbstainsRatherThanGuessing() {
        NodeHistory.Sample sample = new NodeHistory.Sample(1000,
                20f, true, 40f, true,
                10f, true, false, false, false, false, // AC state unknown
                0.01, false);

        assertFalse(scorer.score(ANY_NODE, List.of(sample)).atRisk());
    }

    @Test
    void noBatteryPresentDoesNotTrigger() {
        NodeHistory.Sample sample = new NodeHistory.Sample(1000,
                20f, true, 40f, true,
                0f, false, false, false, false, false, // desktop: no battery at all
                0.01, false);

        assertFalse(scorer.score(ANY_NODE, List.of(sample)).atRisk());
    }

    // ── Rule 2: rising heartbeat RTT ───────────────────────────────────────────

    @Test
    void strictlyIncreasingRttOverTheFullWindowTriggers() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        double[] rtts = {0.01, 0.02, 0.03, 0.04, 0.05}; // 5 = default trend window, strictly increasing
        for (int i = 0; i < rtts.length; i++) {
            history.add(new NodeHistory.Sample(i, 20f, true, 40f, true,
                    80f, true, false, true, true, true, rtts[i], true));
        }

        RiskScorer.RiskAssessment assessment = scorer.score(ANY_NODE, history);

        assertTrue(assessment.atRisk());
        assertEquals(0.5, assessment.riskScore(), 0.001);
    }

    @Test
    void rttThatPlateausAnywhereInTheWindowDoesNotTrigger() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        double[] rtts = {0.01, 0.02, 0.02, 0.04, 0.05}; // one non-increasing step
        for (int i = 0; i < rtts.length; i++) {
            history.add(new NodeHistory.Sample(i, 20f, true, 40f, true,
                    80f, true, false, true, true, true, rtts[i], true));
        }

        assertFalse(scorer.score(ANY_NODE, history).atRisk());
    }

    @Test
    void rttWithAGapInTheWindowAbstainsRatherThanGuessing() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            boolean available = i != 2; // one missing point in the middle
            history.add(new NodeHistory.Sample(i, 20f, true, 40f, true,
                    80f, true, false, true, true, true, 0.01 * (i + 1), available));
        }

        assertFalse(scorer.score(ANY_NODE, history).atRisk());
    }

    @Test
    void fewerSamplesThanTheTrendWindowNeverTriggersRtt() {
        List<NodeHistory.Sample> history = List.of(
                new NodeHistory.Sample(1, 20f, true, 40f, true, 80f, true, false, true, true, true, 0.01, true),
                new NodeHistory.Sample(2, 20f, true, 40f, true, 80f, true, false, true, true, true, 0.02, true));

        assertFalse(scorer.score(ANY_NODE, history).atRisk());
    }

    // ── Rule 3: memory pressure ─────────────────────────────────────────────

    @Test
    void nonDecreasingMemoryAboveTheCeilingTriggers() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        float[] mem = {91f, 92f, 92f, 93f, 95f}; // non-decreasing, all above the 90% ceiling
        for (int i = 0; i < mem.length; i++) {
            history.add(new NodeHistory.Sample(i, 20f, true, mem[i], true,
                    80f, true, false, true, true, true, 0.01, false));
        }

        RiskScorer.RiskAssessment assessment = scorer.score(ANY_NODE, history);

        assertTrue(assessment.atRisk());
        assertEquals(0.5, assessment.riskScore(), 0.001);
    }

    @Test
    void memoryThatDropsAnywhereInTheWindowDoesNotTriggerEvenIfCurrentlyHigh() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        float[] mem = {95f, 91f, 92f, 93f, 95f}; // dropped between sample 0 and 1
        for (int i = 0; i < mem.length; i++) {
            history.add(new NodeHistory.Sample(i, 20f, true, mem[i], true,
                    80f, true, false, true, true, true, 0.01, false));
        }

        assertFalse(scorer.score(ANY_NODE, history).atRisk());
    }

    @Test
    void memoryBelowTheCeilingDoesNotTriggerEvenIfRising() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        float[] mem = {50f, 55f, 60f, 65f, 70f}; // rising but never crosses 90%
        for (int i = 0; i < mem.length; i++) {
            history.add(new NodeHistory.Sample(i, 20f, true, mem[i], true,
                    80f, true, false, true, true, true, 0.01, false));
        }

        assertFalse(scorer.score(ANY_NODE, history).atRisk());
    }

    // ── Combined ────────────────────────────────────────────────────────────

    @Test
    void multipleTriggeredRulesRaiseTheScoreButCapAtOne() {
        List<NodeHistory.Sample> history = new ArrayList<>();
        float[] mem = {91f, 92f, 93f, 94f, 95f};
        for (int i = 0; i < mem.length; i++) {
            // Low battery AND memory pressure both true on every sample in the window.
            history.add(new NodeHistory.Sample(i, 20f, true, mem[i], true,
                    5f, true, false, true, false, true, 0.01, false));
        }

        RiskScorer.RiskAssessment assessment = scorer.score(ANY_NODE, history);

        assertTrue(assessment.atRisk());
        assertEquals(1.0, assessment.riskScore(), 0.001);
        assertEquals(2, assessment.reasons().size());
    }

    @Test
    void aSingleHealthySampleNeverTriggersAnyRule() {
        assertFalse(scorer.score(ANY_NODE, List.of(healthySample(1000))).atRisk());
    }

    // ── Configuration ───────────────────────────────────────────────────────

    @Test
    void customThresholdsAreRespected() {
        RuleBasedRiskScorer strict = new RuleBasedRiskScorer(
                50.0, // battery must be below 50% to count as low
                90.0, 5, 0.5);
        NodeHistory.Sample sample = new NodeHistory.Sample(1000,
                20f, true, 40f, true,
                45f, true, false, true, false, true, // 45% — below the custom 50% threshold
                0.01, false);

        assertTrue(strict.score(ANY_NODE, List.of(sample)).atRisk());
    }

    @Test
    void trendWindowBelowTwoIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RuleBasedRiskScorer(15.0, 90.0, 1, 0.5));
    }
}
