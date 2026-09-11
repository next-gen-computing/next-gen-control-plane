package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.nextgen.controlplane.raft.RaftTestSupport.awaitLeader;
import static com.nextgen.controlplane.raft.RaftTestSupport.proposeViaCurrentLeader;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftLogReplicationTest {

    @Test
    void aProposedCommandAppliesOnEveryReplicaInTheSameOrder(@TempDir Path dir) throws Exception {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            awaitLeader(cluster);

            // Retries against whichever node is currently leader — under these tests' deliberately short,
            // contested election timings, leadership can genuinely change between one propose and the next.
            proposeViaCurrentLeader(cluster, "one", Duration.ofSeconds(3));
            proposeViaCurrentLeader(cluster, "two", Duration.ofSeconds(3));
            long lastIndex = proposeViaCurrentLeader(cluster, "three", Duration.ofSeconds(3));

            for (RaftNode node : cluster.allNodes()) {
                await().atMost(Duration.ofSeconds(3)).until(() -> node.lastApplied() >= lastIndex);
            }
            for (String id : cluster.memberIds()) {
                var commands = cluster.appliedInOrderOn(id).stream().map(ByteString::toStringUtf8)
                        .filter(s -> !s.isEmpty()).toList();
                assertEquals(java.util.List.of("one", "two", "three"), commands,
                        "every replica must apply the same commands in the same order (node " + id + ")");
            }
        }
    }

    @Test
    void aPartitionedFollowerFullyCatchesUpOnHeal(@TempDir Path dir) throws Exception {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            RaftNode leader = awaitLeader(cluster);
            String leaderId = leader.nodeId();
            String isolatedId = cluster.memberIds().stream().filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();

            cluster.partition(leaderId, isolatedId);
            String secondFollowerId = cluster.memberIds().stream()
                    .filter(id -> !id.equals(leaderId) && !id.equals(isolatedId)).findFirst().orElseThrow();
            cluster.partition(secondFollowerId, isolatedId); // fully isolate, not just from the leader

            long lastIndex = -1;
            for (int i = 0; i < 5; i++) {
                lastIndex = leader.propose(ByteString.copyFromUtf8("during-partition-" + i)).get(3, TimeUnit.SECONDS);
            }
            final long duringPartitionIndex = lastIndex;
            await().atMost(Duration.ofSeconds(3)).until(() -> cluster.node(leaderId).lastApplied() >= duringPartitionIndex);
            assertTrue(cluster.node(isolatedId).lastApplied() < duringPartitionIndex,
                    "the isolated node must not have applied entries it never received");

            cluster.healAll();

            await().atMost(Duration.ofSeconds(4)).until(() -> cluster.node(isolatedId).lastApplied() >= duringPartitionIndex);
            var isolatedCommands = cluster.appliedInOrderOn(isolatedId).stream().map(ByteString::toStringUtf8)
                    .filter(s -> !s.isEmpty()).toList();
            var leaderCommands = cluster.appliedInOrderOn(leaderId).stream().map(ByteString::toStringUtf8)
                    .filter(s -> !s.isEmpty()).toList();
            assertEquals(leaderCommands, isolatedCommands, "a healed follower must converge to the exact same applied sequence");
        }
    }

    @Test
    void anIsolatedFollowerThatBumpsItsOwnTermStillReconvergesCleanlyOnHeal(@TempDir Path dir) throws Exception {
        // In a 3-node cluster a fully-isolated single node can never WIN an election while isolated (it
        // needs a majority of 2 votes but can reach nobody) — so during isolation it can only bump its
        // own term via failed election attempts, never accept a client write. Once healed, it is
        // correct — not a bug — for that node to win a SUBSEQUENT election: its log is exactly as
        // up to date as everyone else's (it received "committed" before being isolated and nothing new
        // was proposed while it was cut off) and its term is now higher, since it kept incrementing
        // through repeated timeouts. This is the textbook "disruptive server" scenario vanilla Raft
        // accepts (the Pre-Vote extension exists specifically to avoid it, and is explicitly out of
        // scope for this stage — see the plan). So this test asserts the property that actually must
        // hold — the cluster reconverges on ONE agreed leader and term — not that the ORIGINAL leader
        // specifically survives. RaftHandlerTest's aRealTermConflictTruncatesOnlyFromTheConflictPointOnward
        // covers the log-truncation side of a genuine divergent tail directly at the handler level.
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            RaftNode leader = awaitLeader(cluster);
            String leaderId = leader.nodeId();
            String followerId = cluster.memberIds().stream().filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();

            long committed = leader.propose(ByteString.copyFromUtf8("committed")).get(3, TimeUnit.SECONDS);
            for (RaftNode node : cluster.allNodes()) {
                await().atMost(Duration.ofSeconds(3)).until(() -> node.lastApplied() >= committed);
            }

            for (String other : cluster.memberIds()) {
                if (!other.equals(followerId)) {
                    cluster.partition(followerId, other);
                }
            }
            long followerTermBeforeHeal = cluster.node(followerId).leadership().term();
            await().atMost(Duration.ofSeconds(2)).until(() -> cluster.node(followerId).leadership().term() > followerTermBeforeHeal);

            cluster.healAll();

            await().atMost(Duration.ofSeconds(4)).until(() -> {
                var terms = cluster.allNodes().stream().map(n -> n.leadership().term()).distinct().toList();
                var leaderIds = cluster.allNodes().stream().map(n -> n.leadership().leaderId())
                        .filter(id -> !id.isEmpty()).distinct().toList();
                return terms.size() == 1 && leaderIds.size() == 1
                        && cluster.allNodes().stream().filter(RaftNode::isLeader).count() == 1;
            });
        }
    }

    @Test
    void withAMinorityIsolatedNothingCommitsAndProposeEventuallyFails(@TempDir Path dir) throws Exception {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir, new RaftTimings(20, 100, 200, 300))) {
            RaftNode leader = awaitLeader(cluster);
            String leaderId = leader.nodeId();
            var followerIds = cluster.memberIds().stream().filter(id -> !id.equals(leaderId)).toList();

            // Isolate BOTH followers from the leader — the leader retains no majority.
            cluster.partition(leaderId, followerIds.get(0));
            cluster.partition(leaderId, followerIds.get(1));

            var future = leader.propose(ByteString.copyFromUtf8("never-commits"));
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof NotLeaderException,
                    "a leader that loses quorum must step down, failing the pending proposal honestly "
                            + "rather than hanging forever or committing without a majority");
        }
    }
}
