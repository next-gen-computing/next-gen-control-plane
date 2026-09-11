package com.nextgen.controlplane.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.nextgen.controlplane.raft.RaftTestSupport.awaitLeader;

/**
 * Real, reproducible measurements of election and failover latency — not asserted against a threshold
 * (that's {@link RaftLeaderFailoverTest}/{@link RaftElectionTest}'s job), just measured and printed, so
 * a reader can generate their own real numbers rather than trust a claimed one (see this project's own
 * "Paper &harr; Implementation" discipline in README.md). Uses {@link RaftTimings#defaults()} —
 * production timings ({@code heartbeat=150ms, electionTimeout=750-1500ms}) — deliberately NOT the
 * {@code RaftTestCluster.FAST_TIMINGS} every correctness test in this package uses, since a timing
 * number measured under artificially-fast test timings would not represent real deployment behaviour.
 *
 * <p>Run directly: {@code mvn test -Dtest=RaftElectionTimingBenchmarkTest -pl java-control-plane}.
 * Results print to stdout as plain lines, one measurement per line, so they can be redirected/parsed.
 */
class RaftElectionTimingBenchmarkTest {

    private static final int TRIALS = 20;

    @Test
    void measureColdStartElectionAndLeaderFailoverLatency(@TempDir Path root) {
        List<Long> coldStartMs = new ArrayList<>();
        List<Long> failoverMs = new ArrayList<>();

        for (int i = 0; i < TRIALS; i++) {
            Path dir = root.resolve("trial-" + i);
            try (RaftTestCluster cluster = new RaftTestCluster(3, dir, RaftTimings.defaults())) {
                long start = System.nanoTime();
                RaftNode leader = awaitLeader(cluster, java.time.Duration.ofSeconds(10));
                coldStartMs.add((System.nanoTime() - start) / 1_000_000);

                long failoverStart = System.nanoTime();
                cluster.crash(leader.nodeId());
                awaitLeader(cluster, java.time.Duration.ofSeconds(10));
                failoverMs.add((System.nanoTime() - failoverStart) / 1_000_000);
            }
        }

        report("cold_start_election_ms", coldStartMs);
        report("leader_failover_ms", failoverMs);
    }

    private static void report(String label, List<Long> samplesMs) {
        List<Long> sorted = new ArrayList<>(samplesMs);
        sorted.sort(Long::compareTo);
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        long median = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(sorted.size() * 0.95) - 1));

        System.out.println("BENCHMARK " + label + " n=" + sorted.size()
                + " raw_ms=" + sorted);
        System.out.printf(Locale.ROOT,
                "BENCHMARK %s summary: mean=%.1fms median=%dms min=%dms max=%dms p95=%dms%n",
                label, mean, median, min, max, p95);
    }
}
