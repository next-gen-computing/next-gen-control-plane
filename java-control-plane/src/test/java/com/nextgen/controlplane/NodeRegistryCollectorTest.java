package com.nextgen.controlplane;

import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeRegistryCollector.
 *
 * <p>Each test registers the collector on its own {@link CollectorRegistry}. The default registry
 * rejects duplicate registration, so sharing it across tests would fail on the second one.
 */
class NodeRegistryCollectorTest {

    private record Fixture(NodeRegistry registry, CollectorRegistry metrics, AtomicLong clock) {
    }

    private static Fixture fixture() {
        AtomicLong clock = new AtomicLong(1_000L);
        NodeRegistry registry = new NodeRegistry(new ConcurrentHashMap<>(), clock::get);
        CollectorRegistry metrics = new CollectorRegistry();
        new NodeRegistryCollector(registry).register(metrics);
        return new Fixture(registry, metrics, clock);
    }

    private static double sample(CollectorRegistry metrics, String name, String status) {
        Double value = status == null
                ? metrics.getSampleValue(name)
                : metrics.getSampleValue(name, new String[]{"status"}, new String[]{status});
        assertNotNull(value, "no sample found for " + name);
        return value;
    }

    @Test
    void emptyRegistryReportsZeroActiveNodes() {
        Fixture f = fixture();

        assertEquals(0.0, sample(f.metrics(), "controlplane_active_nodes", null));
        assertEquals(0.0, sample(f.metrics(), "controlplane_registered_nodes", null));
    }

    @Test
    void aliveNodesAreCounted() {
        Fixture f = fixture();
        f.registry().register("node1", "10.0.0.1", 1, "h", null, "v");
        f.registry().register("node2", "10.0.0.2", 1, "h", null, "v");

        assertEquals(2.0, sample(f.metrics(), "controlplane_active_nodes", null));
        assertEquals(2.0, sample(f.metrics(), "controlplane_nodes", "ALIVE"));
    }

    @Test
    void activeNodeCountFallsWhenANodeDies() {
        // The regression this collector exists for: the previous gauge was only ever set in
        // registerNode, so it counted total registry size and never decreased when a node died.
        Fixture f = fixture();
        f.registry().register("node1", "10.0.0.1", 1, "h", null, "v");
        f.registry().register("node2", "10.0.0.2", 1, "h", null, "v");
        assertEquals(2.0, sample(f.metrics(), "controlplane_active_nodes", null));

        f.clock().set(1_000_000L);
        f.registry().sweepExpired(6_000L);

        assertEquals(0.0, sample(f.metrics(), "controlplane_active_nodes", null),
                "active node count must fall when nodes stop heartbeating");
        assertEquals(2.0, sample(f.metrics(), "controlplane_nodes", "SUSPECTED_DEAD"));
        assertEquals(2.0, sample(f.metrics(), "controlplane_registered_nodes", null),
                "dead nodes are still registered, just not active");
    }

    @Test
    void countsAreReadAtScrapeTimeNotAtMutationTime() {
        Fixture f = fixture();
        assertEquals(0.0, sample(f.metrics(), "controlplane_active_nodes", null));

        f.registry().register("late", "10.0.0.1", 1, "h", null, "v");

        // No explicit metric update happened; the next scrape simply reads the registry.
        assertEquals(1.0, sample(f.metrics(), "controlplane_active_nodes", null));
    }

    @Test
    void everyStatusIsExportedIncludingZeroValuedOnes() {
        Fixture f = fixture();
        f.registry().register("node1", "10.0.0.1", 1, "h", null, "v");

        for (NodeStatus status : NodeStatus.values()) {
            assertNotNull(f.metrics().getSampleValue("controlplane_nodes",
                            new String[]{"status"}, new String[]{status.wireName()}),
                    "missing series for status " + status);
        }
    }

    @Test
    void deregisteredNodeDisappearsFromBothCounts() {
        Fixture f = fixture();
        f.registry().register("node1", "10.0.0.1", 1, "h", null, "v");
        f.registry().deregister("node1", false);

        assertEquals(0.0, sample(f.metrics(), "controlplane_active_nodes", null));
        assertEquals(0.0, sample(f.metrics(), "controlplane_registered_nodes", null));
    }
}
