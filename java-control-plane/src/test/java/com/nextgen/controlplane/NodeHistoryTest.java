package com.nextgen.controlplane;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeHistory — the server-owned trend store the Stage D risk scorer will read.
 */
class NodeHistoryTest {

    private static NodeHistory.Sample sample(long atMillis, float cpu) {
        return new NodeHistory.Sample(atMillis,
                cpu, true,
                50.0f, true,
                80.0f, true,
                false, true,
                true, true,
                0.05, true);
    }

    @Test
    void aNodeWithNoSamplesHasEmptyHistory() {
        NodeHistory history = new NodeHistory();

        assertTrue(history.recent("ghost").isEmpty());
        assertTrue(history.latest("ghost").isEmpty());
    }

    @Test
    void recordsSamplesInOrder() {
        NodeHistory history = new NodeHistory();

        history.record("node1", sample(1000, 10f));
        history.record("node1", sample(2000, 20f));
        history.record("node1", sample(3000, 30f));

        List<NodeHistory.Sample> recent = history.recent("node1");
        assertEquals(3, recent.size());
        assertEquals(10f, recent.get(0).cpuPercent());
        assertEquals(20f, recent.get(1).cpuPercent());
        assertEquals(30f, recent.get(2).cpuPercent());
    }

    @Test
    void latestReturnsTheMostRecentlyRecordedSample() {
        NodeHistory history = new NodeHistory();
        history.record("node1", sample(1000, 10f));
        history.record("node1", sample(2000, 20f));

        assertEquals(20f, history.latest("node1").orElseThrow().cpuPercent());
    }

    @Test
    void distinctNodesHaveIndependentHistories() {
        NodeHistory history = new NodeHistory();
        history.record("node1", sample(1000, 10f));
        history.record("node2", sample(1000, 99f));

        assertEquals(1, history.recent("node1").size());
        assertEquals(1, history.recent("node2").size());
        assertEquals(10f, history.recent("node1").get(0).cpuPercent());
        assertEquals(99f, history.recent("node2").get(0).cpuPercent());
    }

    @Test
    void ringBufferDropsTheOldestSampleOnceTheCapIsExceeded() {
        NodeHistory history = new NodeHistory();

        int total = NodeHistory.MAX_SAMPLES_PER_NODE + 5;
        for (int i = 0; i < total; i++) {
            history.record("node1", sample(i, (float) i));
        }

        List<NodeHistory.Sample> recent = history.recent("node1");
        assertEquals(NodeHistory.MAX_SAMPLES_PER_NODE, recent.size(),
                "the buffer must never grow past its cap");
        // The oldest 5 samples (cpu values 0..4) must have been dropped; the buffer keeps the newest.
        assertEquals(5f, recent.get(0).cpuPercent(), "the oldest surviving sample must be sample #5");
        assertEquals((float) (total - 1), recent.get(recent.size() - 1).cpuPercent());
    }

    @Test
    void forgetRemovesTheNodesHistoryEntirely() {
        NodeHistory history = new NodeHistory();
        history.record("node1", sample(1000, 10f));

        history.forget("node1");

        assertTrue(history.recent("node1").isEmpty());
        assertTrue(history.latest("node1").isEmpty());
    }

    @Test
    void forgetOnAnUnknownNodeIsANoOp() {
        NodeHistory history = new NodeHistory();
        assertDoesNotThrow(() -> history.forget("ghost"));
    }

    @Test
    void sampleCarriesEveryAvailabilityFlagIndependently() {
        NodeHistory history = new NodeHistory();
        // A desktop machine: no battery, cpu/memory readable.
        NodeHistory.Sample noBattery = new NodeHistory.Sample(1000,
                42f, true, 60f, true,
                0f, false, false, false, false, false,
                0.02, true);
        history.record("desktop1", noBattery);

        NodeHistory.Sample recorded = history.latest("desktop1").orElseThrow();
        assertTrue(recorded.cpuAvailable());
        assertFalse(recorded.batteryAvailable());
        assertFalse(recorded.chargingKnown());
        assertFalse(recorded.onAcPowerKnown());
    }
}
