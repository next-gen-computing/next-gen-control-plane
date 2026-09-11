package com.nextgen.controlplane.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forces two candidates to contend for the same term (via {@link RaftNode#forceStartElection()}, a
 * test-only seam that bypasses the real randomized timer) in a 4-node cluster, where an even 2-2 vote
 * split is possible — and confirms the cluster still recovers: at no point do two nodes ever both claim
 * LEADER for the same term, and a single leader eventually emerges once contention resolves.
 */
class RaftSplitVoteTest {

    @Test
    void contendingCandidatesNeverProduceTwoLeadersInTheSameTermAndOneEventuallyWins(@TempDir Path dir) {
        try (RaftTestCluster cluster = new RaftTestCluster(4, dir)) {
            // Force the first two nodes to contend before the cluster's own timers would naturally fire.
            cluster.node("n0").forceStartElection();
            cluster.node("n1").forceStartElection();

            // Poll for a while, asserting the safety property throughout rather than only at the end —
            // this is what would catch two simultaneous leaders in the same term, not just "eventually
            // exactly one remains."
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                var leaderTerms = cluster.allNodes().stream()
                        .filter(RaftNode::isLeader)
                        .map(n -> n.leadership().term())
                        .collect(Collectors.toSet());
                long leaderCount = cluster.allNodes().stream().filter(RaftNode::isLeader).count();
                assertTrue(leaderCount <= 1 || leaderTerms.size() == leaderCount,
                        "two nodes must never both claim LEADER for the same term, even under contention");
                if (leaderCount == 1) {
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
                    .until(() -> cluster.allNodes().stream().filter(RaftNode::isLeader).count() == 1);
        }
    }
}
