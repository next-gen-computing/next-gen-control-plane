package com.nextgen.controlplane.raft;

import com.nextgen.proto.ControlPlaneProto.ConsumeEnrollmentTokenCommand;
import com.nextgen.proto.ControlPlaneProto.MintEnrollmentTokenCommand;
import com.nextgen.proto.ControlPlaneProto.StateCommand;
import com.nextgen.security.TokenReplicationSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stage II: the real {@link TokenReplicationSink} — proposes {@code MintEnrollmentTokenCommand}/
 * {@code ConsumeEnrollmentTokenCommand} through {@link RaftNode}, exactly the same
 * build-a-{@code StateCommand}-and-propose shape {@link RaftControlPlaneWriter} already uses for
 * node/task/job mutations, just for the two enrollment-token commands that proto already reserved
 * (see {@code control_plane.proto}'s own comment: "wired in Stage J's PKI work, not this proto's first
 * users" — this class is that wiring).
 *
 * <p>{@link #onMinted} blocks until committed and applied (same semantics as {@code
 * RaftControlPlaneWriter.propose}) — see {@code TokenReplicationSink#onMinted}'s Javadoc for why that's
 * the correct, intentional behavior here, not an oversight. {@link #onConsumed} is fire-and-forget: it
 * proposes and returns immediately, logging (never throwing) on failure, since by the time it's called
 * the real accept/invalid/expired decision has already been made and reported to the caller.
 */
public final class RaftEnrollmentTokenReplicator implements TokenReplicationSink {
    private static final Logger LOG = LoggerFactory.getLogger(RaftEnrollmentTokenReplicator.class);

    private final RaftNode raftNode;
    private final long proposeTimeoutMs;

    public RaftEnrollmentTokenReplicator(RaftNode raftNode, long proposeTimeoutMs) {
        this.raftNode = raftNode;
        this.proposeTimeoutMs = proposeTimeoutMs;
    }

    @Override
    public void onMinted(String nodeId, String tokenHash, long expiresAtEpochMillis) {
        StateCommand command = newCommand()
                .setMintEnrollmentToken(MintEnrollmentTokenCommand.newBuilder()
                        .setNodeId(nodeId).setTokenHash(tokenHash).setExpiresAtEpochMillis(expiresAtEpochMillis))
                .build();
        try {
            raftNode.propose(command.toByteString()).get(proposeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re; // e.g. NotLeaderException — surface it exactly, matching RaftControlPlaneWriter
            }
            throw new IllegalStateException("Raft propose failed while replicating a minted enrollment token",
                    e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "Raft propose did not commit within " + proposeTimeoutMs + "ms while minting an enrollment token");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replicating a minted enrollment token", e);
        }
    }

    @Override
    public void onConsumed(String tokenHash) {
        StateCommand command = newCommand()
                .setConsumeEnrollmentToken(ConsumeEnrollmentTokenCommand.newBuilder().setTokenHash(tokenHash))
                .build();
        raftNode.propose(command.toByteString())
                .exceptionally(e -> {
                    LOG.warn("Could not replicate enrollment-token consumption (a since-superseded token "
                            + "hash could remain valid on a newly-elected leader until it naturally expires): {}",
                            e.getMessage());
                    return null;
                });
    }

    private static StateCommand.Builder newCommand() {
        return StateCommand.newBuilder().setProposedAtEpochMillis(System.currentTimeMillis());
    }
}
