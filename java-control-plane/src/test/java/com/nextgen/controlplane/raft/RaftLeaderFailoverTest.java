package com.nextgen.controlplane.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static com.nextgen.controlplane.raft.RaftTestSupport.awaitLeader;
import static com.nextgen.controlplane.raft.RaftTestSupport.proposeViaCurrentLeader;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kill the leader; a new one must emerge with a strictly greater term, and entries committed under
 * the old leader must survive on the new one. */
class RaftLeaderFailoverTest {

    @Test
    void aNewLeaderEmergesWithAHigherTermAfterTheOldLeaderIsKilled(@TempDir Path dir) {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            RaftNode firstLeader = awaitLeader(cluster);
            String firstLeaderId = firstLeader.nodeId();
            long firstTerm = firstLeader.leadership().term();

            cluster.crash(firstLeaderId);

            RaftNode newLeader = awaitLeader(cluster);
            long newTerm = newLeader.leadership().term();

            assertTrue(newTerm > firstTerm, "the new leader's term must be strictly greater than the old one's");
            assertTrue(!newLeader.nodeId().equals(firstLeaderId));
        }
    }

    @Test
    void entriesCommittedUnderTheOldLeaderSurviveOnTheNewOne(@TempDir Path dir) throws Exception {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            RaftNode firstLeader = awaitLeader(cluster);
            String firstLeaderId = firstLeader.nodeId();

            long committedIndex = proposeAndAwaitApplied(cluster, "hello-before-failover");

            cluster.crash(firstLeaderId);
            RaftNode newLeader = awaitLeader(cluster);

            await().atMost(Duration.ofSeconds(2)).until(() -> newLeader.lastApplied() >= committedIndex);
            assertTrue(cluster.appliedInOrderOn(newLeader.nodeId()).stream()
                    .anyMatch(c -> c.toStringUtf8().equals("hello-before-failover")));
        }
    }

    @Test
    void aNewLeaderAppendsANoOpEntryInItsOwnTermOnElection(@TempDir Path dir) {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            RaftNode leader = awaitLeader(cluster);
            // The no-op is entry index 1 in a fresh cluster (nothing else has been proposed yet).
            await().atMost(Duration.ofSeconds(1)).until(() -> leader.commitIndex() >= 1);
        }
    }

    private static long proposeAndAwaitApplied(RaftTestCluster cluster, String payload) throws Exception {
        long index = proposeViaCurrentLeader(cluster, payload, Duration.ofSeconds(3));
        for (RaftNode node : cluster.allNodes()) {
            await().atMost(Duration.ofSeconds(3)).until(() -> node.lastApplied() >= index);
        }
        return index;
    }
}
