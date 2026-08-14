package com.nextgen.controlplane.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-cluster, thread-driven election tests over {@link RaftTestCluster}'s
 * {@link InMemoryRaftTransport} — real timers, real threads, short real timings, observed with
 * Awaitility (matching this codebase's established {@code HeartbeatMonitorTest} convention) rather than
 * a simulated clock.
 */
class RaftElectionTest {

    @Test
    void aSingleLeaderEmergesInAQuiet3NodeCluster(@TempDir Path dir) {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
                    .until(() -> cluster.allNodes().stream().filter(RaftNode::isLeader).count() == 1);

            List<RaftNode> leaders = cluster.allNodes().stream().filter(RaftNode::isLeader).toList();
            assertEquals(1, leaders.size());

            long term = leaders.get(0).leadership().term();
            // Every node must agree on the same term and the same leader id.
            String leaderId = leaders.get(0).nodeId();
            for (RaftNode node : cluster.allNodes()) {
                await().atMost(Duration.ofSeconds(2)).until(() -> node.leadership().leaderId().equals(leaderId));
                assertEquals(term, node.leadership().term());
            }
        }
    }

    @Test
    void exactlyOneLeaderExistsPerTermEvenAcrossRepeatedObservation(@TempDir Path dir) {
        try (RaftTestCluster cluster = new RaftTestCluster(5, dir)) {
            await().atMost(Duration.ofSeconds(3)).until(
                    () -> cluster.allNodes().stream().anyMatch(RaftNode::isLeader));

            // Sample repeatedly over a short window — at no point should two nodes both claim LEADER
            // for the same term.
            for (int i = 0; i < 20; i++) {
                Set<Long> leaderTerms = cluster.allNodes().stream()
                        .filter(RaftNode::isLeader)
                        .map(n -> n.leadership().term())
                        .collect(Collectors.toSet());
                long leaderCount = cluster.allNodes().stream().filter(RaftNode::isLeader).count();
                assertTrue(leaderCount <= 1 || leaderTerms.size() == leaderCount,
                        "at most one leader per term must ever be observed");
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void aSingleNodeClusterImmediatelyBecomesItsOwnLeader(@TempDir Path dir) {
        try (RaftTestCluster cluster = new RaftTestCluster(1, dir)) {
            await().atMost(Duration.ofSeconds(2)).until(() -> cluster.node("n0").isLeader());
        }
    }
}
