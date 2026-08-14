package com.nextgen.controlplane.raft;

import java.util.function.LongSupplier;

/**
 * The clock every registry ({@code NodeRegistry}/{@code TaskRegistry}/{@code JobRegistry}) is given
 * when Raft is enabled, so timestamps stamped during {@code apply()} come from the leader's proposal
 * time — carried in every {@code StateCommand}'s {@code proposed_at_epoch_millis} — rather than each
 * replica's own wall clock.
 *
 * <p>Without this, every replica would independently stamp {@code System.currentTimeMillis()} while
 * applying the very same command, and the "identical state machines" property Raft is supposed to
 * guarantee would be false from the first committed entry: two replicas applying the same
 * {@code RegisterNodeCommand} a few milliseconds apart would produce {@code NodeRecord}s that differ in
 * every timestamp field, even though nothing about the actual decision differed.
 *
 * <p>Real wall-clock time is still used everywhere else (heartbeat sweeps, risk scoring) — those are
 * deliberately leader-local, non-replicated concerns (see the plan's Stage J notes on what crosses the
 * Raft line), so {@link #enter}/{@link #exit} are only ever called by {@code RaftStateMachine} around
 * a single {@code apply()} call, on the one dedicated apply thread.
 */
public final class ApplyClock implements LongSupplier {
    private final ThreadLocal<Long> applying = new ThreadLocal<>();

    /** Called by {@code RaftStateMachine} immediately before invoking a registry mutator during apply. */
    public void enter(long proposedAtEpochMillis) {
        applying.set(proposedAtEpochMillis);
    }

    /** Called immediately after that mutator returns — never left set across unrelated calls on this thread. */
    public void exit() {
        applying.remove();
    }

    @Override
    public long getAsLong() {
        Long proposedAt = applying.get();
        return proposedAt != null ? proposedAt : System.currentTimeMillis();
    }
}
