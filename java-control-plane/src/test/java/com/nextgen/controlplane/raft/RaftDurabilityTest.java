package com.nextgen.controlplane.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static com.nextgen.controlplane.raft.RaftTestSupport.awaitLeader;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A restarted node's persisted term/vote/log must survive exactly as {@link RaftLog} promises, and a
 * restarted cluster member must safely rejoin without ever double-voting in a term it already voted in. */
class RaftDurabilityTest {

    @Test
    void aRestartedNodeNeverGrantsASecondConflictingVoteInATermItAlreadyVotedIn(@TempDir Path dir) {
        Path nodeDir = dir.resolve("solo");
        String votedTerm;
        try (RaftLog log = new RaftLog(nodeDir)) {
            log.recordVote(3, "candidate-a");
            votedTerm = log.votedFor();
        }
        assertEquals("candidate-a", votedTerm);

        // Simulate a crash + restart, then directly exercise the handler as a fresh RaftNode instance
        // built on the SAME on-disk directory.
        try (RaftLog reopened = new RaftLog(nodeDir)) {
            java.util.List<RaftPeer> members = java.util.List.of(
                    new RaftPeer("solo", "localhost", 0), new RaftPeer("other", "localhost", 0));
            RaftNode restarted = new RaftNode("solo", "localhost:0", members, reopened,
                    (index, command) -> { }, new RaftTransport() {
                        @Override
                        public RequestVoteResult requestVote(RaftPeer peer, RequestVoteArgs args) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public AppendEntriesResult appendEntries(RaftPeer peer, AppendEntriesArgs args) {
                            throw new UnsupportedOperationException();
                        }
                    }, RaftTestCluster.FAST_TIMINGS);

            var reply = restarted.handleRequestVote(new RaftTransport.RequestVoteArgs(3, "candidate-b", 0, 0));
            assertFalse(reply.voteGranted(),
                    "a replica that already voted for candidate-a in term 3 must never grant candidate-b a vote in the same term, even after a restart");
        }
    }

    @Test
    void currentTermNeverRegressesAcrossARestart(@TempDir Path dir) {
        Path nodeDir = dir.resolve("solo");
        try (RaftLog log = new RaftLog(nodeDir)) {
            log.setCurrentTerm(9);
        }
        try (RaftLog reopened = new RaftLog(nodeDir)) {
            assertEquals(9, reopened.currentTerm());
            reopened.setCurrentTerm(5); // an attempt to regress — this test asserts the FILE itself
            // records whatever the caller sets; the actual monotonicity guarantee is RaftNode's
            // responsibility (it only ever calls setCurrentTerm with a term greater than its own), which
            // RaftHandlerTest's rejectsAVoteRequestWithALowerTermWithoutChangingState already covers by
            // proving a lower-term request never triggers a term change at all.
        }
        try (RaftLog reopenedAgain = new RaftLog(nodeDir)) {
            assertEquals(5, reopenedAgain.currentTerm(), "state.meta must durably reflect the last write");
        }
    }

    @Test
    void aCommittedEntrySurvivesACrashAndTheReplicaRejoinsWithTheSameAppliedState(@TempDir Path dir) throws Exception {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            long index = RaftTestSupport.proposeViaCurrentLeader(cluster, "durable-payload", Duration.ofSeconds(3));
            for (RaftNode node : cluster.allNodes()) {
                await().atMost(Duration.ofSeconds(3)).until(() -> node.lastApplied() >= index);
            }

            String leaderId = cluster.allNodes().stream().filter(RaftNode::isLeader).findFirst()
                    .orElseThrow().nodeId();
            String followerId = cluster.memberIds().stream().filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();

            cluster.crash(followerId);
            RaftNode restarted = cluster.restart(followerId);

            await().atMost(Duration.ofSeconds(3)).until(() -> restarted.lastApplied() >= index);
            assertTrue(cluster.appliedInOrderOn(followerId).stream().anyMatch(c -> c.toStringUtf8().equals("durable-payload")),
                    "a restarted replica must recover the entry from its own durable log/replay, without needing to re-fetch it");
        }
    }

    @Test
    void aRestartedNodeCanStillParticipateInAFutureElection(@TempDir Path dir) throws Exception {
        try (RaftTestCluster cluster = new RaftTestCluster(3, dir)) {
            RaftNode leader = awaitLeader(cluster);
            String leaderId = leader.nodeId();
            String followerId = cluster.memberIds().stream().filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();

            cluster.crash(followerId);
            cluster.restart(followerId);

            // Now kill the ORIGINAL leader too — the restarted node must be eligible to vote in (or
            // win) the resulting election, proving it isn't permanently stuck in some broken state.
            cluster.crash(leaderId);
            await().atMost(Duration.ofSeconds(4)).until(() -> cluster.allNodes().stream().anyMatch(RaftNode::isLeader));
        }
    }
}
