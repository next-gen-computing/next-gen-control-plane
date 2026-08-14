package com.nextgen.controlplane.raft;

/** Thrown by {@code RaftNode.propose(...)} when this replica is not (or is no longer) the leader. */
public final class NotLeaderException extends RuntimeException {

    private final String leaderId;
    private final String leaderAddress;

    public NotLeaderException(String leaderId, String leaderAddress) {
        super("Not the leader" + (leaderId == null || leaderId.isEmpty() ? "" : " (leader is " + leaderId + ")"));
        this.leaderId = leaderId == null ? "" : leaderId;
        this.leaderAddress = leaderAddress == null ? "" : leaderAddress;
    }

    public String leaderId() {
        return leaderId;
    }

    public String leaderAddress() {
        return leaderAddress;
    }
}
