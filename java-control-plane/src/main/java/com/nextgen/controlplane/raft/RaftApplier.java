package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;

/**
 * Applies one committed Raft log entry to real state. Invoked only from {@link RaftNode}'s single
 * internal apply thread, strictly in log order — implementations (see {@code RaftStateMachine}) do not
 * need their own synchronization for this reason, but must never call back into the owning
 * {@link RaftNode} (e.g. {@code propose}) synchronously from within {@link #apply}, since that would
 * reenter the apply thread and could deadlock against the very commit it's waiting on.
 */
public interface RaftApplier {

    /**
     * @param index   the committed log index this command was applied at (1-based, monotonically
     *                increasing across calls)
     * @param command the raw command payload (a serialized {@code StateCommand} in production; an
     *                empty {@link ByteString} for a no-op entry, which implementations should ignore)
     */
    void apply(long index, ByteString command);
}
