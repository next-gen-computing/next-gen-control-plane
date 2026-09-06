package com.nextgen.controlplane;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exports node counts by reading the registry at scrape time.
 *
 * <p>Replaces the previous imperative {@code ACTIVE_NODES.set(registry.size())} call, which had two
 * bugs at once: it was only ever invoked from {@code registerNode}, so the gauge never decreased when
 * a node died; and it published the <i>total</i> registry size under a name that claims to count
 * active nodes.
 *
 * <p>A pull-time collector removes the whole "forgot to decrement" class of bug — there is no counter
 * left to forget to update, because the number is derived from the registry every time it is read.
 */
public final class NodeRegistryCollector extends Collector {

    private final NodeRegistry registry;

    public NodeRegistryCollector(NodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        Map<NodeStatus, Integer> counts = registry.statusCounts();

        GaugeMetricFamily byStatus = new GaugeMetricFamily(
                "controlplane_nodes",
                "Registered nodes by liveness status",
                List.of("status"));
        for (Map.Entry<NodeStatus, Integer> entry : counts.entrySet()) {
            byStatus.addMetric(List.of(entry.getKey().wireName()), entry.getValue());
        }

        // Retained under its original name so existing dashboards and the integration test keep
        // working; it is now an alias for controlplane_nodes{status="ALIVE"} and, unlike before,
        // actually falls when a node dies.
        GaugeMetricFamily active = new GaugeMetricFamily(
                "controlplane_active_nodes",
                "Number of currently alive nodes (alias for controlplane_nodes{status=\"ALIVE\"})",
                counts.getOrDefault(NodeStatus.ALIVE, 0));

        GaugeMetricFamily registered = new GaugeMetricFamily(
                "controlplane_registered_nodes",
                "Total nodes in the registry regardless of status",
                registry.size());

        List<MetricFamilySamples> samples = new ArrayList<>(3);
        samples.add(byStatus);
        samples.add(active);
        samples.add(registered);
        return samples;
    }
}
