package com.nextgen.controlplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RoundRobinScheduler.
 *
 * <p>Several of these fail against the previous index-modulo-size implementation; they are the
 * regression tests for the defects that motivated rotating over node identity instead.
 */
class RoundRobinSchedulerTest {

    private static List<NodeRecord> nodes(String... ids) {
        List<NodeRecord> records = new ArrayList<>();
        for (String id : ids) {
            records.add(new NodeRecord(id, "10.0.0.1", 50051, id));
        }
        records.sort(Comparator.comparing(NodeRecord::getNodeId));
        return records;
    }

    @Test
    void emptyCandidateListSelectsNothing() {
        assertTrue(new RoundRobinScheduler().select(List.of()).isEmpty());
    }

    @Test
    void nullCandidateListSelectsNothing() {
        assertTrue(new RoundRobinScheduler().select(null).isEmpty());
    }

    @Test
    void singleNodeAlwaysSelected() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        List<NodeRecord> candidates = nodes("only");

        for (int i = 0; i < 5; i++) {
            assertEquals("only", scheduler.select(candidates).orElseThrow().getNodeId());
        }
    }

    @Test
    void rotatesThroughEveryNodeInOrder() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        List<NodeRecord> candidates = nodes("a", "b", "c");

        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            picks.add(scheduler.select(candidates).orElseThrow().getNodeId());
        }

        assertEquals(List.of("a", "b", "c", "a", "b", "c"), picks);
    }

    @Test
    void distributionIsEvenOverManySelections() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        List<NodeRecord> candidates = nodes("a", "b", "c", "d");

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            counts.merge(scheduler.select(candidates).orElseThrow().getNodeId(), 1, Integer::sum);
        }

        counts.values().forEach(count -> assertEquals(100, count));
    }

    @Test
    void rotationSurvivesMembershipChurnWithoutStarvingANode() {
        // The old index-modulo-size scheme starved nodes when the candidate size changed between
        // calls: counter 0,1,2,3,4 against sizes 3,3,2,2,3 gives 0,1,0,1,2.
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        List<NodeRecord> three = nodes("a", "b", "c");
        List<NodeRecord> two = nodes("a", "b");

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            List<NodeRecord> candidates = (i % 5 < 3) ? three : two;
            counts.merge(scheduler.select(candidates).orElseThrow().getNodeId(), 1, Integer::sum);
        }

        // Every node must receive work; "c" is only present some of the time but must not be starved
        // whenever it IS present.
        assertTrue(counts.getOrDefault("c", 0) > 0, "node c was starved entirely: " + counts);
        assertTrue(counts.get("a") > 0 && counts.get("b") > 0);
    }

    @Test
    void removingTheCurrentNodeContinuesFromTheNextOneRatherThanRestarting() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        assertEquals("a", scheduler.select(nodes("a", "b", "c")).orElseThrow().getNodeId());
        assertEquals("b", scheduler.select(nodes("a", "b", "c")).orElseThrow().getNodeId());

        // "b" (the node just selected) disappears. The cycle should continue at "c", not reset to "a".
        assertEquals("c", scheduler.select(nodes("a", "c")).orElseThrow().getNodeId());
    }

    @Test
    void addingANodeDoesNotRestartTheCycle() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        scheduler.select(nodes("a", "c"));                     // -> a
        NodeRecord next = scheduler.select(nodes("a", "b", "c")).orElseThrow();

        // "b" sorts between the cursor ("a") and "c", so it is the correct next pick.
        assertEquals("b", next.getNodeId());
    }

    @Test
    void schedulerSurvivesIntegerOverflow() {
        // The previous implementation used Math.abs(counter.getAndIncrement()) % size. After 2^31
        // submissions the counter reaches Integer.MIN_VALUE, Math.abs of which is still negative,
        // producing a negative index and an IndexOutOfBoundsException on List.get.
        // Rotating over identity has no counter to overflow; this asserts that property holds
        // across an arbitrarily long run.
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        List<NodeRecord> candidates = nodes("a", "b", "c");

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100_000; i++) {
                assertTrue(scheduler.select(candidates).isPresent());
            }
        });
    }

    @Test
    void cursorPointingAtARemovedNodeStillSelectsSomething() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        scheduler.seedCursor("zzz-node-that-no-longer-exists");

        Optional<NodeRecord> pick = scheduler.select(nodes("a", "b"));

        // The cursor sorts past the end of the list, so selection must wrap rather than fail.
        assertTrue(pick.isPresent());
        assertEquals("a", pick.get().getNodeId());
    }

    @Test
    void deadNodesAreNotSelectedWhenTheRegistryFiltersThem() {
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        NodeRegistry registry = new NodeRegistry(map, System::currentTimeMillis);
        registry.register("alive", "10.0.0.1", 1, "h", null, "v");
        registry.register("dead", "10.0.0.2", 1, "h", null, "v");
        map.computeIfPresent("dead", (k, v) -> v.withStatus(NodeStatus.SUSPECTED_DEAD));

        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        for (int i = 0; i < 10; i++) {
            assertEquals("alive", scheduler.select(registry.aliveSnapshot()).orElseThrow().getNodeId());
        }
    }

    @Test
    @Timeout(30)
    void concurrentSelectionsDistributeWorkWithoutDoubleAssigningTheSameCursor() throws Exception {
        RoundRobinScheduler scheduler = new RoundRobinScheduler();
        List<NodeRecord> candidates = nodes("a", "b", "c", "d");

        int threads = 8;
        int perThread = 500;
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    counts.merge(scheduler.select(candidates).orElseThrow().getNodeId(), 1, Integer::sum);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));

        int total = threads * perThread;
        assertEquals(total, counts.values().stream().mapToInt(Integer::intValue).sum());
        // The compare-and-set makes each advance exclusive, so the spread stays near-uniform even
        // under contention.
        counts.values().forEach(count ->
                assertEquals(total / 4.0, count, total * 0.05,
                        "distribution skewed under concurrency: " + counts));
    }
}
