package com.nextgen.controlplane.raft;

/**
 * A point-in-time snapshot of a {@link RaftNode}'s role/term/leader knowledge, read outside the node's
 * internal lock (see {@code RaftNode.leadership()}).
 */
public record LeadershipStatus(RaftRole role, long term, String leaderId, String leaderAddress) {

    public boolean isLeader() {
        return role == RaftRole.LEADER;
    }
}
