package com.nextgen.controlplane.raft;

import java.util.List;

/**
 * Outbound Raft RPCs to a peer — the seam that lets {@link RaftNode} be tested without any gRPC
 * transport (an in-memory implementation routes straight to peers' {@code handleRequestVote}/
 * {@code handleAppendEntries}) and that a gRPC-backed implementation implements for production,
 * translating these plain records to/from the {@code RaftConsensus} proto service.
 *
 * <p>Implementations must not block indefinitely: a genuinely unreachable peer must fail fast or time
 * out on its own rather than hang the calling replicator thread forever.
 */
public interface RaftTransport {

    RequestVoteResult requestVote(RaftPeer peer, RequestVoteArgs args);

    AppendEntriesResult appendEntries(RaftPeer peer, AppendEntriesArgs args);

    /** Raft's RequestVote RPC arguments (candidate -> peer). */
    record RequestVoteArgs(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
    }

    /** Raft's RequestVote RPC reply (peer -> candidate). */
    record RequestVoteResult(long term, boolean voteGranted) {
    }

    /** Raft's AppendEntries RPC arguments (leader -> follower); also used as the leader heartbeat when
     * {@code entries} is empty. {@code leaderAddress} is the leader's client-facing advertised address
     * (distinct from its Raft peer address), carried so any replica can hand out a usable redirect hint
     * regardless of who is currently leading. */
    record AppendEntriesArgs(long term, String leaderId, String leaderAddress, long prevLogIndex,
                             long prevLogTerm, List<LogEntry> entries, long leaderCommit) {
    }

    /** Raft's AppendEntries RPC reply (follower -> leader). On failure, {@code conflictIndex}/
     * {@code conflictTerm} let the leader fast-backup {@code nextIndex} in one round trip instead of
     * decrementing by one each time: {@code conflictTerm == 0} means "follower's log was too short,
     * retry at conflictIndex directly"; otherwise it names the conflicting term and its first index. */
    record AppendEntriesResult(long term, boolean success, long matchIndex, long conflictIndex, long conflictTerm) {
    }
}
