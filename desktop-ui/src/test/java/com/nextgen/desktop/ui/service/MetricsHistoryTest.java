package com.nextgen.desktop.ui.service;

import com.nextgen.desktop.ui.service.MetricsHistory.Metric;
import com.nextgen.desktop.ui.service.MetricsHistory.Sample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the chart time series.
 *
 * <p>The headline behaviour is gap handling: a node that stops reporting must produce a break in its
 * series, never a segment bridging the outage. A bridging line asserts the node was fine throughout,
 * which is the opposite of what happened.
 */
class MetricsHistoryTest {

    @Test
    void recordsRealReadings() {
        MetricsHistory history = new MetricsHistory();

        history.record("node1", Metric.CPU, 1_000L, 42.0, true);

        List<List<Sample>> runs = history.series("node1", Metric.CPU);
        assertEquals(1, runs.size());
        assertEquals(1, runs.get(0).size());
        assertEquals(42.0, runs.get(0).get(0).value(), 0.001);
    }

    @Test
    void unknownNodeHasNoSeries() {
        assertTrue(new MetricsHistory().series("ghost", Metric.CPU).isEmpty());
    }

    // ── Gaps ─────────────────────────────────────────────────────────────────

    @Test
    void anOutageSplitsTheSeriesIntoSeparateRuns() {
        MetricsHistory history = new MetricsHistory();
        history.record("node1", Metric.CPU, 1_000L, 10.0, true);
        history.record("node1", Metric.CPU, 2_000L, 20.0, true);
        // The node goes away.
        history.recordGap("node1", 3_000L);
        history.recordGap("node1", 4_000L);
        // And comes back.
        history.record("node1", Metric.CPU, 5_000L, 30.0, true);

        List<List<Sample>> runs = history.series("node1", Metric.CPU);

        // Two runs means the chart draws two separate lines with a visible break, rather than one
        // line sloping straight from 20% to 30% across the downtime.
        assertEquals(2, runs.size(), "the outage must split the series");
        assertEquals(2, runs.get(0).size());
        assertEquals(1, runs.get(1).size());
        assertEquals(30.0, runs.get(1).get(0).value(), 0.001);
    }

    @Test
    void anUnavailableReadingIsAGapNotAZero() {
        MetricsHistory history = new MetricsHistory();
        history.record("node1", Metric.CPU, 1_000L, 55.0, true);
        history.record("node1", Metric.CPU, 2_000L, 0.0, false);

        List<List<Sample>> runs = history.series("node1", Metric.CPU);

        assertEquals(1, runs.size());
        assertEquals(1, runs.get(0).size(), "the unavailable reading must not become a plotted point");
        assertEquals(55.0, runs.get(0).get(0).value(), 0.001);
    }

    @Test
    void leadingAndTrailingGapsProduceNoEmptyRuns() {
        MetricsHistory history = new MetricsHistory();
        history.recordGap("node1", 1_000L);
        history.record("node1", Metric.CPU, 2_000L, 10.0, true);
        history.recordGap("node1", 3_000L);

        List<List<Sample>> runs = history.series("node1", Metric.CPU);

        assertEquals(1, runs.size());
        assertFalse(runs.get(0).isEmpty());
    }

    @Test
    void aNodeThatOnlyEverGappedHasNoRuns() {
        MetricsHistory history = new MetricsHistory();
        history.recordGap("node1", 1_000L);
        history.recordGap("node1", 2_000L);

        assertTrue(history.series("node1", Metric.CPU).isEmpty(),
                "no data at all must render as 'no data', not as a flat line at zero");
    }

    @Test
    void currentlyUnavailableReflectsTheMostRecentSample() {
        MetricsHistory history = new MetricsHistory();
        history.record("node1", Metric.CPU, 1_000L, 10.0, true);
        assertFalse(history.isCurrentlyUnavailable("node1", Metric.CPU));

        history.recordGap("node1", 2_000L);
        assertTrue(history.isCurrentlyUnavailable("node1", Metric.CPU));
    }

    @Test
    void unknownNodeCountsAsUnavailable() {
        assertTrue(new MetricsHistory().isCurrentlyUnavailable("ghost", Metric.CPU));
    }

    @Test
    void latestValueReturnsTheLastRealReadingNotTheLastSample() {
        MetricsHistory history = new MetricsHistory();
        history.record("node1", Metric.CPU, 1_000L, 77.0, true);
        history.recordGap("node1", 2_000L);

        // The chart legend shows this as the last known value, explicitly flagged "no signal" — the
        // number is history, and the label says so.
        assertEquals(77.0, history.latestValue("node1", Metric.CPU), 0.001);
    }

    @Test
    void latestValueIsNullWhenNothingWasEverMeasured() {
        MetricsHistory history = new MetricsHistory();
        history.recordGap("node1", 1_000L);

        assertNull(history.latestValue("node1", Metric.CPU));
        assertNull(history.latestValue("ghost", Metric.CPU));
    }

    // ── Colour identity ──────────────────────────────────────────────────────

    @Test
    void eachNodeKeepsItsColourSlotForLife() {
        MetricsHistory history = new MetricsHistory();
        history.record("alpha", Metric.CPU, 1_000L, 1, true);
        history.record("beta", Metric.CPU, 1_000L, 1, true);
        history.record("gamma", Metric.CPU, 1_000L, 1, true);

        int betaSlot = history.slotOf("beta");

        // Removing another node must not repaint the survivors.
        history.forget("alpha");
        history.record("delta", Metric.CPU, 2_000L, 1, true);

        assertEquals(betaSlot, history.slotOf("beta"), "a node's colour must not shift");
        assertNotEquals(betaSlot, history.slotOf("delta"));
    }

    @Test
    void slotsAreAssignedInFirstSeenOrder() {
        MetricsHistory history = new MetricsHistory();
        history.record("first", Metric.CPU, 1_000L, 1, true);
        history.record("second", Metric.CPU, 1_000L, 1, true);

        assertEquals(0, history.slotOf("first"));
        assertEquals(1, history.slotOf("second"));
        assertEquals(-1, history.slotOf("never-seen"));
    }

    // ── Retention ────────────────────────────────────────────────────────────

    @Test
    void seriesIsCappedAtCapacity() {
        MetricsHistory history = new MetricsHistory(10);
        for (int i = 0; i < 50; i++) {
            history.record("node1", Metric.CPU, i * 1_000L, i, true);
        }

        int total = history.series("node1", Metric.CPU).stream().mapToInt(List::size).sum();

        assertEquals(10, total);
    }

    @Test
    void throughputSeriesIsCappedToo() {
        MetricsHistory history = new MetricsHistory(5);
        for (int i = 0; i < 20; i++) {
            history.recordTaskCount(i * 1_000L, i);
        }

        assertEquals(5, history.throughputSeries().size());
    }

    @Test
    void cpuAndMemoryAreTrackedIndependently() {
        MetricsHistory history = new MetricsHistory();
        history.record("node1", Metric.CPU, 1_000L, 10.0, true);
        history.record("node1", Metric.MEMORY, 1_000L, 0.0, false);

        assertFalse(history.isCurrentlyUnavailable("node1", Metric.CPU));
        assertTrue(history.isCurrentlyUnavailable("node1", Metric.MEMORY),
                "one metric being unreadable must not invalidate the other");
    }

    @Test
    void forgettingANodeDropsItsHistory() {
        MetricsHistory history = new MetricsHistory();
        history.record("node1", Metric.CPU, 1_000L, 10.0, true);

        history.forget("node1");

        assertTrue(history.series("node1", Metric.CPU).isEmpty());
        assertFalse(history.nodeIds().contains("node1"));
    }
}
