package com.nextgen.security;

/**
 * Stage II: a hook {@link EnrollmentTokenStore} calls immediately after a real mint/consume decision
 * has been made locally, letting a caller replicate that decision elsewhere — kept dependency-free of
 * anything Raft-specific here so this security package never needs to know Raft exists (the real
 * implementation, {@code com.nextgen.controlplane.raft.RaftEnrollmentTokenReplicator}, lives with the
 * rest of the Raft machinery instead).
 *
 * <p>Only the hash ever crosses this boundary, never the plaintext token — the same "the plaintext is
 * never stored" property {@link EnrollmentTokenStore}'s own class Javadoc already documents extends to
 * replication too.
 */
public interface TokenReplicationSink {

    /** Called synchronously from {@link EnrollmentTokenStore#mint} right after the token is minted and
     * stored locally, before the plaintext is returned to the caller. A blocking implementation (e.g.
     * one that waits for a Raft commit) is intentional and expected here: minting should not report
     * success to an operator until a future leader is guaranteed to recognize the same token. */
    void onMinted(String nodeId, String tokenHash, long expiresAtEpochMillis);

    /** Called after a token has already been authoritatively consumed (accepted) locally — this is
     * notification of a decision already made, not a request to make one, so an implementation is free
     * to replicate it asynchronously/best-effort without blocking the enrollment response that already
     * succeeded. */
    void onConsumed(String tokenHash);
}
