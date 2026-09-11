package com.nextgen.desktop.ui.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rolling time series of real readings, per node, for the charts.
 *
 * <h2>Gaps are data</h2>
 *
 * When a node stops reporting — because it died, or because its OS reading was unavailable — this
 * class records a <b>gap</b>, not a value. {@link #series(String, Metric)} returns the samples split
 * into contiguous runs so the chart can draw a break rather than a straight line bridging the
 * outage.
 *
 * <p>That distinction is the whole point. A line drawn straight across a node's downtime says "this
 * node was fine the whole time", which is exactly the opposite of what happened.
 *
 * <h2>Colour identity</h2>
 *
 * Each node is assigned a categorical slot on first sight and keeps it. Filtering or removing other
 * nodes never repaints the survivors.
 */
public class MetricsHistory {

    /** Which metric a series carries. */
    public enum Metric { CPU, MEMORY }

    /** How many samples to retain per node per metric. At a 2s poll this is ~10 minutes. */
    private final int capacity;

    private final Map<String, NodeSeries> byNode = new ConcurrentHashMap<>();
    private final Map<String, Integer> slotByNode = new ConcurrentHashMap<>();
    private final AtomicInteger nextSlot = new AtomicInteger(0);

    /** Cluster-wide counts, for the throughput chart. */
    private final List<Sample> taskThroughput = Collections.synchronizedList(new ArrayList<>());

    public MetricsHistory() {
        this(300);
    }

    public MetricsHistory(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Records one reading.
     *
     * @param available false when the node could not measure the metric. A gap is stored, never a
     *                  substituted zero.
     */
    public void record(String nodeId, Metric metric, long epochMillis, double value, boolean available) {
        byNode.computeIfAbsent(nodeId, id -> {
            slotByNode.computeIfAbsent(id, key -> nextSlot.getAndIncrement());
            return new NodeSeries(capacity);
        }).add(metric, new Sample(epochMillis, value, available));
    }

    /** Records that a node produced no reading at all for this tick (offline). */
    public void recordGap(String nodeId, long epochMillis) {
        record(nodeId, Metric.CPU, epochMillis, 0, false);
        record(nodeId, Metric.MEMORY, epochMillis, 0, false);
    }

    /** Records the number of tasks observed in flight, for the cluster throughput chart. */
    public void recordTaskCount(long epochMillis, int count) {
        synchronized (taskThroughput) {
            taskThroughput.add(new Sample(epochMillis, count, true));
            while (taskThroughput.size() > capacity) {
                taskThroughput.remove(0);
            }
        }
    }

    /** Drops a node's history entirely. Its colour slot is NOT reused, so identity stays stable. */
    public void forget(String nodeId) {
        byNode.remove(nodeId);
    }

    public Set<String> nodeIds() {
        return Set.copyOf(byNode.keySet());
    }

    /** The stable categorical slot for a node, or -1 if it has never been seen. */
    public int slotOf(String nodeId) {
        return slotByNode.getOrDefault(nodeId, -1);
    }

    /**
     * A node's samples for one metric, split into contiguous runs of available readings.
     *
     * @return one list per unbroken run; an empty outer list means "no data at all"
     */
    public List<List<Sample>> series(String nodeId, Metric metric) {
        NodeSeries node = byNode.get(nodeId);
        if (node == null) {
            return List.of();
        }
        return splitIntoRuns(node.snapshot(metric));
    }

    /**
     * A node's raw samples for one metric, in order, including gaps ({@code available() == false}) —
     * unlike {@link #series}, which splits into contiguous available-only runs for JavaFX's per-run
     * chart series. Consumers that render a {@code null} value as a native line break (the local HTTP
     * API's JSON contract, consumed by Plotly) want the gap markers themselves, not runs with the gaps
     * removed.
     */
    public List<Sample> rawSamples(String nodeId, Metric metric) {
        NodeSeries node = byNode.get(nodeId);
        return node == null ? List.of() : node.snapshot(metric);
    }

    public List<Sample> throughputSeries() {
        synchronized (taskThroughput) {
            return List.copyOf(taskThroughput);
        }
    }

    /** True when the node's most recent sample for this metric was a gap. */
    public boolean isCurrentlyUnavailable(String nodeId, Metric metric) {
        NodeSeries node = byNode.get(nodeId);
        if (node == null) {
            return true;
        }
        List<Sample> samples = node.snapshot(metric);
        return samples.isEmpty() || !samples.get(samples.size() - 1).available();
    }

    /** The most recent real value, or null when the node has never produced one. */
    public Double latestValue(String nodeId, Metric metric) {
        NodeSeries node = byNode.get(nodeId);
        if (node == null) {
            return null;
        }
        List<Sample> samples = node.snapshot(metric);
        for (int i = samples.size() - 1; i >= 0; i--) {
            if (samples.get(i).available()) {
                return samples.get(i).value();
            }
        }
        return null;
    }

    static List<List<Sample>> splitIntoRuns(List<Sample> samples) {
        List<List<Sample>> runs = new ArrayList<>();
        List<Sample> current = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.available()) {
                current.add(sample);
            } else if (!current.isEmpty()) {
                runs.add(List.copyOf(current));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            runs.add(List.copyOf(current));
        }
        return List.copyOf(runs);
    }

    /** One reading. {@code available == false} means no measurement existed at this instant. */
    public record Sample(long epochMillis, double value, boolean available) {
    }

    /** Fixed-capacity ring of samples for one node. */
    private static final class NodeSeries {
        private final Map<Metric, List<Sample>> samples = new LinkedHashMap<>();
        private final int capacity;

        NodeSeries(int capacity) {
            this.capacity = capacity;
            for (Metric metric : Metric.values()) {
                samples.put(metric, Collections.synchronizedList(new ArrayList<>()));
            }
        }

        void add(Metric metric, Sample sample) {
            List<Sample> list = samples.get(metric);
            synchronized (list) {
                list.add(sample);
                while (list.size() > capacity) {
                    list.remove(0);
                }
            }
        }

        List<Sample> snapshot(Metric metric) {
            List<Sample> list = samples.get(metric);
            synchronized (list) {
                return List.copyOf(list);
            }
        }
    }
}
