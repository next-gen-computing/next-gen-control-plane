package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct, deterministic tests of {@link RaftNode#handleRequestVote}/{@link RaftNode#handleAppendEntries}
 * — driven by calling the handlers directly rather than through real threads/timers, so these are fast
 * and immune to timing flakiness. {@code start()} is deliberately never called on these nodes: the
 * handlers are pure functions of (current state, request) plus a durable log write, and testing them
 * this way isolates the election-safety and log-matching rules from the threading/timer machinery
 * (covered separately by {@code RaftElectionTest}/{@code RaftLogReplicationTest}).
 */
class RaftHandlerTest {

    private static final RaftApplier NOOP_APPLIER = (index, command) -> { };
    private static final RaftTransport NEVER_CALLED = new RaftTransport() {
        @Override
        public RequestVoteResult requestVote(RaftPeer peer, RequestVoteArgs args) {
            throw new UnsupportedOperationException("not expected to be called in these tests");
        }

        @Override
        public AppendEntriesResult appendEntries(RaftPeer peer, AppendEntriesArgs args) {
            throw new UnsupportedOperationException("not expected to be called in these tests");
        }
    };

    private static RaftNode bareNode(Path dir, String id) {
        List<RaftPeer> members = List.of(new RaftPeer(id, "localhost", 0), new RaftPeer("other", "localhost", 0));
        RaftLog log = new RaftLog(dir);
        return new RaftNode(id, "localhost:0", members, log, NOOP_APPLIER, NEVER_CALLED, RaftTestCluster.FAST_TIMINGS);
    }

    private static ByteString cmd(String s) {
        return ByteString.copyFromUtf8(s);
    }

    // ── RequestVote: election safety ────────────────────────────────────

    @Test
    void grantsAVoteToACandidateWithAnUpToDateLog(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        var reply = node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "candidate", 0, 0));
        assertTrue(reply.voteGranted());
        assertEquals(1, reply.term());
    }

    @Test
    void rejectsAVoteRequestWithALowerTermWithoutChangingState(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleRequestVote(new RaftTransport.RequestVoteArgs(5, "first", 0, 0)); // term now 5, voted for "first"

        var reply = node.handleRequestVote(new RaftTransport.RequestVoteArgs(3, "second", 0, 0));

        assertFalse(reply.voteGranted());
        assertEquals(5, reply.term());
    }

    @Test
    void neverGrantsASecondVoteInTheSameTerm(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        var first = node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "candidate-a", 0, 0));
        var second = node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "candidate-b", 0, 0));

        assertTrue(first.voteGranted());
        assertFalse(second.voteGranted(), "a second, different candidate must never win a vote in the same term");
    }

    @Test
    void reGrantsTheSameVoteToARetriedRequestFromTheSameCandidate(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "candidate-a", 0, 0));
        var retry = node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "candidate-a", 0, 0));

        assertTrue(retry.voteGranted(), "a retried RequestVote from the SAME already-voted-for candidate must still succeed");
    }

    @Test
    void aCandidateWithAStaleLastLogTermNeverWinsAVote(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(5, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 5, cmd("a"))), 0));

        var reply = node.handleRequestVote(new RaftTransport.RequestVoteArgs(6, "candidate", 1, 3));

        assertFalse(reply.voteGranted(), "a candidate whose last log term (3) trails ours (5) must not win our vote");
    }

    @Test
    void aCandidateWithAShorterLogAtTheSameLastTermNeverWinsAVote(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 1, cmd("a")), new LogEntry(0, 1, cmd("b"))), 0));

        var reply = node.handleRequestVote(new RaftTransport.RequestVoteArgs(2, "candidate", 1, 1));

        assertFalse(reply.voteGranted(), "same last term but fewer entries (1 < 2) must not win our vote");
    }

    @Test
    void aRejectedVoteRequestNeverResetsTheElectionDeadline(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "first", 0, 0)); // grants, resets deadline
        LeadershipStatus before = node.leadership();

        // A lower-term request is rejected outright and must not touch state at all.
        node.handleRequestVote(new RaftTransport.RequestVoteArgs(0, "second", 0, 0));

        assertEquals(before, node.leadership(), "a rejected vote request must leave leadership state untouched");
    }

    @Test
    void aHigherTermInAVoteRequestStepsDownAndAdoptsTheNewTerm(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleRequestVote(new RaftTransport.RequestVoteArgs(1, "first", 0, 0));

        var reply = node.handleRequestVote(new RaftTransport.RequestVoteArgs(7, "second", 0, 0));

        assertTrue(reply.voteGranted());
        assertEquals(7, reply.term());
        assertEquals(RaftRole.FOLLOWER, node.leadership().role());
    }

    // ── AppendEntries: log matching, truncation, commit ─────────────────

    @Test
    void rejectsAppendEntriesWithALowerTerm(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleRequestVote(new RaftTransport.RequestVoteArgs(5, "candidate", 0, 0)); // bumps term to 5

        var reply = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(3, "stale-leader", "addr", 0, 0, List.of(), 0));

        assertFalse(reply.success());
        assertEquals(5, reply.term());
    }

    @Test
    void appendsEntriesInOrderAndReportsTheCorrectMatchIndex(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");

        var reply = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 1, cmd("a")), new LogEntry(0, 1, cmd("b"))), 0));

        assertTrue(reply.success());
        assertEquals(2, reply.matchIndex());
    }

    @Test
    void rejectsWhenPrevLogIndexIsBeyondOurLogAndReportsWhereToRetry(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");

        var reply = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 5, 1, List.of(), 0));

        assertFalse(reply.success());
        assertEquals(0, reply.conflictTerm(), "a too-short log reports conflictTerm=0 (retry directly at conflictIndex)");
        assertEquals(1, reply.conflictIndex(), "our log is empty, so the leader should retry from index 1");
    }

    @Test
    void aTermConflictAtPrevLogIndexIsReportedWithTheConflictingTermAndItsFirstIndex(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        // Term 3 spans indices 2-3, so the fast-backup optimization has something real to skip.
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 2, cmd("x")), new LogEntry(0, 3, cmd("y")), new LogEntry(0, 3, cmd("z"))), 0));

        // A new leader believes index 3 was term 4, but we actually have term 3 there.
        var reply = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(5, "leader2", "addr2", 3, 4, List.of(), 0));

        assertFalse(reply.success());
        assertEquals(3, reply.conflictTerm());
        assertEquals(2, reply.conflictIndex(), "term 3 first appears at index 2 — the leader should skip the whole term in one round trip");
    }

    @Test
    void aRealTermConflictTruncatesOnlyFromTheConflictPointOnward(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 1, cmd("a")), new LogEntry(0, 1, cmd("stale-b"))), 0));

        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(2, "leader2", "addr2", 1, 1,
                List.of(new LogEntry(0, 2, cmd("real-b")), new LogEntry(0, 2, cmd("real-c"))), 0));

        assertEquals(2, node.leadership().term()); // sanity: adopted the second leader's term
        var status = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(2, "leader2", "addr2", 3, 2, List.of(), 0));
        assertTrue(status.success());
        assertEquals(3, status.matchIndex());
    }

    @Test
    void aDelayedDuplicateAppendEntriesDoesNotDeleteEntriesWeLegitimatelyHoldBeyondIt(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        // Leader sent [a, b, c] and we applied all three.
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 1, cmd("a")), new LogEntry(0, 1, cmd("b")), new LogEntry(0, 1, cmd("c"))), 0));

        // A delayed, duplicate copy of the FIRST AppendEntries (just [a]) arrives late.
        var reply = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 1, cmd("a"))), 0));

        assertTrue(reply.success());
        // We must still hold b and c — a same-term match at the overlapping entry must never truncate.
        var status = node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 3, 1, List.of(), 0));
        assertTrue(status.success(), "entries b and c beyond the duplicate's range must still be present");
        assertEquals(3, status.matchIndex());
    }

    @Test
    void leaderCommitAdvancesOurCommitIndexButNeverPastOurOwnLastIndex(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader", "addr", 0, 0,
                List.of(new LogEntry(0, 1, cmd("a")), new LogEntry(0, 1, cmd("b"))), 5)); // leaderCommit=5, we only have 2

        assertEquals(2, node.commitIndex(), "commitIndex must never exceed what we've actually replicated");
    }

    @Test
    void aFollowerAdoptsTheLeaderIdAndAddressFromAppendEntries(@TempDir Path dir) {
        RaftNode node = bareNode(dir, "n1");
        node.handleAppendEntries(new RaftTransport.AppendEntriesArgs(1, "leader-7", "10.0.0.7:50052", 0, 0, List.of(), 0));

        LeadershipStatus status = node.leadership();
        assertEquals(RaftRole.FOLLOWER, status.role());
        assertEquals("leader-7", status.leaderId());
        assertEquals("10.0.0.7:50052", status.leaderAddress());
    }
}
