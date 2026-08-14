package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A hand-rolled core Raft implementation: leader election + log replication over a fixed, statically
 * configured membership (no dynamic add/remove-peer; see the plan's Stage J scope cuts). Built directly
 * on {@link RaftLog}/{@link RaftTransport}/{@link RaftApplier} so it can be tested with an in-memory
 * transport and no gRPC (see the {@code raft} test package's Phase A harness) before ever touching a
 * real network.
 *
 * <p><b>The one rule that makes this safe</b>: a Raft command (see {@link #propose}) records a decision
 * that has already been made — it never records the inputs to a decision. Everything time/state-
 * dependent (heartbeat freshness, node-registry reads, scheduling cursors) must be resolved by the
 * caller <i>before</i> proposing; {@code apply()} on the far side is then a pure, deterministic replay.
 *
 * <p><b>Threading model</b>: one single-threaded scheduled "tick" executor decides <i>when</i> to act
 * (election timeout, heartbeat cadence, quorum-loss step-down); one single-threaded executor per peer
 * performs that peer's actual RPC + reply processing, strictly in submission order — this structurally
 * prevents the classic bug where two in-flight {@code AppendEntries} to the same peer complete out of
 * order and drive {@code nextIndex} backwards; one single-threaded "apply" executor drains
 * committed-but-unapplied entries into the injected {@link RaftApplier}, in strict log order. All
 * mutable Raft state (role, term-adjacent bookkeeping, indices) is guarded by this object's own
 * intrinsic lock — Raft's invariants are cross-field, so a single lock is the only honest choice; it is
 * held across {@link RaftLog} calls (fast, and includes the mandatory pre-reply fsync) but never across
 * an outbound RPC.
 *
 * <p>Several correctness rules here are the ones most often gotten wrong in hand-rolled Raft
 * implementations, and are called out at their point of use rather than only in this class comment:
 * the mandatory {@code log.termAt(N) == currentTerm} check before advancing {@code commitIndex}
 * (Figure 8 in the Raft paper — omitting it causes silent data loss), the no-op entry appended
 * immediately on becoming leader (Raft §5.4.2), never resetting the election deadline on a
 * <i>rejected</i> vote, and stepping down on lost quorum contact so a partitioned leader doesn't serve
 * stale reads forever.
 */
public final class RaftNode implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(RaftNode.class);

    private static final int MAX_ENTRIES_PER_APPEND = 256;
    private static final long APPLY_POLL_INTERVAL_MS = 10;

    private final String nodeId;
    private final String advertisedAddress;
    private final List<RaftPeer> members;
    private final List<RaftPeer> peers; // members, minus self
    private final int majority;
    private final RaftLog log;
    private final RaftApplier applier;
    private final RaftTransport transport;
    private final RaftTimings timings;
    private final LongSupplier clock;
    private final Random random;

    private final List<Consumer<LeadershipStatus>> leadershipListeners = new CopyOnWriteArrayList<>();
    private volatile LeadershipStatus lastFiredStatus;

    // ── guarded by `this` ──────────────────────────────────────────────
    private RaftRole role = RaftRole.FOLLOWER;
    private String leaderId = "";
    private String leaderAddress = "";
    private long commitIndex = 0;
    private long lastApplied = 0;
    private long electionDeadlineMillis;
    private long votesGranted;
    private long electionInProgressTerm = -1;
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();
    private final Map<String, Long> lastSuccessfulContactMillis = new HashMap<>();
    private final Map<Long, CompletableFuture<Long>> pendingProposals = new HashMap<>();
    // ────────────────────────────────────────────────────────────────────

    private final Map<String, AtomicBoolean> dispatchInFlight = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> peerExecutors = new ConcurrentHashMap<>();
    private ScheduledExecutorService tickExecutor;
    private ExecutorService applyExecutor;
    private volatile boolean started;
    private volatile boolean closed;

    public RaftNode(String nodeId, String advertisedAddress, List<RaftPeer> members, RaftLog log,
                    RaftApplier applier, RaftTransport transport, RaftTimings timings) {
        this(nodeId, advertisedAddress, members, log, applier, transport, timings,
                System::currentTimeMillis, new Random());
    }

    public RaftNode(String nodeId, String advertisedAddress, List<RaftPeer> members, RaftLog log,
                    RaftApplier applier, RaftTransport transport, RaftTimings timings,
                    LongSupplier clock, Random random) {
        this.nodeId = nodeId;
        this.advertisedAddress = advertisedAddress;
        this.members = List.copyOf(members);
        this.peers = members.stream().filter(p -> !p.id().equals(nodeId)).toList();
        this.majority = members.size() / 2 + 1;
        this.log = log;
        this.applier = applier;
        this.transport = transport;
        this.timings = timings;
        this.clock = clock;
        this.random = random;
        for (RaftPeer peer : peers) {
            dispatchInFlight.put(peer.id(), new AtomicBoolean(false));
        }
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        resetElectionDeadline();
        for (RaftPeer peer : peers) {
            peerExecutors.put(peer.id(), Executors.newSingleThreadExecutor(
                    r -> daemonThread(r, "raft-replicator-" + nodeId + "-" + peer.id())));
        }
        tickExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> daemonThread(r, "raft-tick-" + nodeId));
        long tickIntervalMs = Math.max(10, timings.heartbeatIntervalMs() / 3);
        tickExecutor.scheduleAtFixedRate(this::safeTick, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
        applyExecutor = Executors.newSingleThreadExecutor(r -> daemonThread(r, "raft-apply-" + nodeId));
        applyExecutor.submit(this::applyLoop);
    }

    private static Thread daemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void close() {
        closed = true;
        if (tickExecutor != null) {
            tickExecutor.shutdownNow();
        }
        if (applyExecutor != null) {
            applyExecutor.shutdownNow();
        }
        for (ExecutorService es : peerExecutors.values()) {
            es.shutdownNow();
        }
        // Actually wait for the threads to stop, not just signal them — otherwise a straggling
        // raft-tick/raft-replicator thread can keep running briefly after close() returns, which is
        // surprising for callers (e.g. tests that immediately reopen the same RaftLog directory) and
        // was a real source of test flakiness during development.
        awaitTermination(tickExecutor);
        awaitTermination(applyExecutor);
        for (ExecutorService es : peerExecutors.values()) {
            awaitTermination(es);
        }
        synchronized (this) {
            failPendingProposals(new IllegalStateException("RaftNode " + nodeId + " closed"));
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        try {
            executor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── public read API ───────────────────────────────────────────────

    public synchronized boolean isLeader() {
        return role == RaftRole.LEADER;
    }

    public synchronized LeadershipStatus leadership() {
        return new LeadershipStatus(role, log.currentTerm(), leaderId, leaderAddress);
    }

    public String nodeId() {
        return nodeId;
    }

    public List<RaftPeer> members() {
        return members;
    }

    public synchronized long commitIndex() {
        return commitIndex;
    }

    public synchronized long lastApplied() {
        return lastApplied;
    }

    public void onLeadershipChange(Consumer<LeadershipStatus> listener) {
        leadershipListeners.add(listener);
    }

    // ── client-facing: propose a command ──────────────────────────────

    /**
     * Proposes a command for replication. Completes with the applied index once a majority has
     * durably replicated it AND this replica has applied it (so the caller can safely re-read the
     * result of its own write). Fails immediately with {@link NotLeaderException} if this replica is
     * not currently leading.
     */
    public CompletableFuture<Long> propose(ByteString command) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("RaftNode " + nodeId + " is closed"));
            return future;
        }
        synchronized (this) {
            if (role != RaftRole.LEADER) {
                future.completeExceptionally(new NotLeaderException(leaderId, leaderAddress));
                return future;
            }
            LogEntry entry = log.append(log.currentTerm(), command);
            pendingProposals.put(entry.index(), future);
            if (peers.isEmpty()) {
                maybeAdvanceCommitIndex();
            }
        }
        for (RaftPeer peer : peers) {
            triggerReplication(peer);
        }
        return future;
    }

    // ── inbound RPC handlers ──────────────────────────────────────────

    public RaftTransport.RequestVoteResult handleRequestVote(RaftTransport.RequestVoteArgs request) {
        RaftTransport.RequestVoteResult result;
        synchronized (this) {
            if (request.term() < log.currentTerm()) {
                return new RaftTransport.RequestVoteResult(log.currentTerm(), false);
            }
            if (request.term() > log.currentTerm()) {
                stepDown(request.term());
            }

            boolean logOk = request.lastLogTerm() > log.lastTerm()
                    || (request.lastLogTerm() == log.lastTerm() && request.lastLogIndex() >= log.lastIndex());
            String voted = log.votedFor();
            boolean canVote = voted.isEmpty() || voted.equals(request.candidateId());

            boolean granted = false;
            if (logOk && canVote) {
                log.recordVote(request.term(), request.candidateId()); // fsync BEFORE replying
                resetElectionDeadline(); // only reset on an actual grant — never on a rejection
                granted = true;
            }
            result = new RaftTransport.RequestVoteResult(log.currentTerm(), granted);
        }
        maybeFireLeadershipChange();
        return result;
    }

    public RaftTransport.AppendEntriesResult handleAppendEntries(RaftTransport.AppendEntriesArgs request) {
        RaftTransport.AppendEntriesResult result;
        synchronized (this) {
            if (request.term() < log.currentTerm()) {
                return new RaftTransport.AppendEntriesResult(log.currentTerm(), false, 0, 0, 0);
            }
            if (request.term() > log.currentTerm()) {
                stepDown(request.term());
            }
            role = RaftRole.FOLLOWER;
            leaderId = request.leaderId();
            leaderAddress = request.leaderAddress();
            resetElectionDeadline();

            if (request.prevLogIndex() > log.lastIndex()) {
                result = new RaftTransport.AppendEntriesResult(log.currentTerm(), false, 0, log.lastIndex() + 1, 0);
            } else if (request.prevLogIndex() > 0 && log.termAt(request.prevLogIndex()) != request.prevLogTerm()) {
                long conflictTerm = log.termAt(request.prevLogIndex());
                long conflictIndex = log.firstIndexOfTerm(conflictTerm);
                result = new RaftTransport.AppendEntriesResult(log.currentTerm(), false, 0, conflictIndex, conflictTerm);
            } else {
                long index = request.prevLogIndex();
                List<LogEntry> toAppend = new ArrayList<>();
                for (LogEntry entry : request.entries()) {
                    index++;
                    if (index <= log.lastIndex()) {
                        if (log.termAt(index) != entry.term()) {
                            // A real term conflict — truncate. Never truncate merely because this batch
                            // is shorter than what we hold; a delayed/duplicate AppendEntries must not
                            // delete entries we legitimately hold beyond this request.
                            log.truncateFrom(index);
                            toAppend.add(entry);
                        }
                        // else: we already have this exact (index, term) — matches the log-matching
                        // property, nothing to do.
                    } else {
                        toAppend.add(entry);
                    }
                }
                if (!toAppend.isEmpty()) {
                    log.appendAll(toAppend);
                }

                if (request.leaderCommit() > commitIndex) {
                    commitIndex = Math.min(request.leaderCommit(), log.lastIndex());
                }

                long matchIndexResult = request.prevLogIndex() + request.entries().size();
                result = new RaftTransport.AppendEntriesResult(log.currentTerm(), true, matchIndexResult, 0, 0);
            }
        }
        maybeFireLeadershipChange();
        return result;
    }

    // ── the tick: election timeout, heartbeat cadence, quorum-loss step-down ──

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.error("Unhandled exception in Raft tick for '{}'", nodeId, e);
        }
    }

    private void tick() {
        boolean shouldStartElection = false;
        List<RaftPeer> toReplicate = List.of();
        synchronized (this) {
            long now = clock.getAsLong();
            if (role != RaftRole.LEADER) {
                if (now >= electionDeadlineMillis) {
                    shouldStartElection = true;
                }
            } else {
                long contacted = 1; // self
                for (RaftPeer peer : peers) {
                    Long last = lastSuccessfulContactMillis.get(peer.id());
                    if (last != null && now - last <= timings.electionTimeoutMaxMs()) {
                        contacted++;
                    }
                }
                if (contacted < majority) {
                    // A leader that can no longer reach a majority must not keep serving reads as if
                    // it were current — step down rather than serve arbitrarily stale data forever.
                    stepDown(log.currentTerm());
                } else {
                    toReplicate = peers;
                }
            }
        }
        if (shouldStartElection) {
            startElection();
        } else {
            for (RaftPeer peer : toReplicate) {
                triggerReplication(peer);
            }
        }
        maybeFireLeadershipChange();
    }

    private void triggerReplication(RaftPeer peer) {
        AtomicBoolean inFlight = dispatchInFlight.get(peer.id());
        if (inFlight == null || !inFlight.compareAndSet(false, true)) {
            return; // a round is already in flight/queued for this peer; it will see the latest state
        }
        ExecutorService executor = peerExecutors.get(peer.id());
        if (executor == null) {
            inFlight.set(false);
            return; // not started yet (e.g. a direct propose() call before start())
        }
        executor.submit(() -> replicateToPeer(peer, inFlight));
    }

    private void replicateToPeer(RaftPeer peer, AtomicBoolean inFlight) {
        try {
            long term;
            String selfId;
            long prevLogIndex;
            long prevLogTerm;
            List<LogEntry> entriesToSend;
            long leaderCommitSnapshot;
            String advertised;
            synchronized (this) {
                if (role != RaftRole.LEADER) {
                    return;
                }
                term = log.currentTerm();
                selfId = nodeId;
                long ni = nextIndex.getOrDefault(peer.id(), log.lastIndex() + 1);
                prevLogIndex = ni - 1;
                prevLogTerm = log.termAt(prevLogIndex);
                entriesToSend = log.entriesFrom(ni, MAX_ENTRIES_PER_APPEND);
                leaderCommitSnapshot = commitIndex;
                advertised = advertisedAddress;
            }

            RaftTransport.AppendEntriesArgs args = new RaftTransport.AppendEntriesArgs(
                    term, selfId, advertised, prevLogIndex, prevLogTerm, entriesToSend, leaderCommitSnapshot);
            RaftTransport.AppendEntriesResult reply;
            try {
                reply = transport.appendEntries(peer, args);
            } catch (RuntimeException e) {
                return; // unreachable this round; the next tick will retry
            }

            synchronized (this) {
                if (reply.term() > log.currentTerm()) {
                    stepDown(reply.term());
                    return;
                }
                if (role != RaftRole.LEADER || log.currentTerm() != term) {
                    return; // stale reply from a round we're no longer leading / no longer in this term
                }
                lastSuccessfulContactMillis.put(peer.id(), clock.getAsLong());
                if (reply.success()) {
                    long newMatch = prevLogIndex + entriesToSend.size();
                    matchIndex.put(peer.id(), newMatch);
                    nextIndex.put(peer.id(), newMatch + 1);
                    maybeAdvanceCommitIndex();
                } else {
                    long newNextIndex;
                    if (reply.conflictTerm() == 0) {
                        newNextIndex = Math.max(1, reply.conflictIndex());
                    } else {
                        long ourFirst = log.firstIndexOfTerm(reply.conflictTerm());
                        newNextIndex = ourFirst != 0 ? ourFirst : Math.max(1, reply.conflictIndex());
                    }
                    nextIndex.put(peer.id(), newNextIndex);
                }
            }
        } finally {
            inFlight.set(false);
        }
        maybeFireLeadershipChange();
    }

    /** Must be called while holding the lock. The single most-often-omitted rule in hand-rolled Raft:
     * an entry is only committed by counting a majority AND requiring it belong to the current term
     * (Raft paper Figure 8) — counting a majority on an older-term entry alone can later be silently
     * overwritten, which is the worst possible failure mode for a control plane. */
    private void maybeAdvanceCommitIndex() {
        for (long n = log.lastIndex(); n > commitIndex; n--) {
            if (log.termAt(n) != log.currentTerm()) {
                continue;
            }
            long replicated = 1; // self
            for (RaftPeer peer : peers) {
                if (matchIndex.getOrDefault(peer.id(), 0L) >= n) {
                    replicated++;
                }
            }
            if (replicated >= majority) {
                commitIndex = n;
                return;
            }
        }
    }

    // ── elections ──────────────────────────────────────────────────────

    /** Test seam: forces this node to start a candidacy immediately, bypassing the real election
     * timer — used to deterministically engineer a split vote in {@code RaftSplitVoteTest} rather than
     * relying on randomized-timer luck. Package-private, never called in production. */
    void forceStartElection() {
        startElection();
    }

    private void startElection() {
        long term;
        long lastLogIndex;
        long lastLogTerm;
        synchronized (this) {
            if (role == RaftRole.LEADER) {
                return; // stray timeout signal, ignore
            }
            term = log.currentTerm() + 1;
            log.recordVote(term, nodeId); // fsync BEFORE any RPC leaves this process
            role = RaftRole.CANDIDATE;
            leaderId = "";
            leaderAddress = "";
            votesGranted = 1; // self
            electionInProgressTerm = term;
            lastLogIndex = log.lastIndex();
            lastLogTerm = log.lastTerm();
            resetElectionDeadline();
        }
        LOG.info("Raft '{}' starting an election for term {}", nodeId, term);

        if (peers.isEmpty()) {
            synchronized (this) {
                if (role == RaftRole.CANDIDATE && electionInProgressTerm == term) {
                    becomeLeader(term);
                }
            }
            maybeFireLeadershipChange();
            return;
        }

        RaftTransport.RequestVoteArgs args = new RaftTransport.RequestVoteArgs(term, nodeId, lastLogIndex, lastLogTerm);
        for (RaftPeer peer : peers) {
            ExecutorService executor = peerExecutors.get(peer.id());
            if (executor != null) {
                executor.submit(() -> requestVoteFromPeer(peer, args, term));
            }
        }
    }

    private void requestVoteFromPeer(RaftPeer peer, RaftTransport.RequestVoteArgs args, long termAtDispatch) {
        RaftTransport.RequestVoteResult reply;
        try {
            reply = transport.requestVote(peer, args);
        } catch (RuntimeException e) {
            return;
        }
        synchronized (this) {
            if (reply.term() > log.currentTerm()) {
                stepDown(reply.term());
                return;
            }
            if (role != RaftRole.CANDIDATE || electionInProgressTerm != termAtDispatch
                    || log.currentTerm() != termAtDispatch) {
                return; // stale reply
            }
            if (reply.voteGranted()) {
                votesGranted++;
                if (votesGranted >= majority) {
                    becomeLeader(termAtDispatch);
                }
            }
        }
        maybeFireLeadershipChange();
    }

    /** Must be called while holding the lock. */
    private void becomeLeader(long term) {
        role = RaftRole.LEADER;
        leaderId = nodeId;
        leaderAddress = advertisedAddress;
        nextIndex.clear();
        matchIndex.clear();
        lastSuccessfulContactMillis.clear();
        long lastLogIdx = log.lastIndex();
        long now = clock.getAsLong();
        for (RaftPeer peer : peers) {
            nextIndex.put(peer.id(), lastLogIdx + 1);
            matchIndex.put(peer.id(), 0L);
            lastSuccessfulContactMillis.put(peer.id(), now); // grace period until proven otherwise
        }
        log.append(term, ByteString.EMPTY); // Raft §5.4.2 — lets commitIndex advance past a stale tail
        LOG.info("Raft '{}' became LEADER for term {}", nodeId, term);
    }

    /** Must be called while holding the lock. Never advances the term backwards; always drops to
     * FOLLOWER, clears leader knowledge, and fails any writes this replica had accepted as leader. */
    private void stepDown(long newTerm) {
        if (newTerm > log.currentTerm()) {
            log.setCurrentTerm(newTerm);
        }
        boolean wasLeader = role == RaftRole.LEADER;
        role = RaftRole.FOLLOWER;
        leaderId = "";
        leaderAddress = "";
        if (wasLeader) {
            LOG.info("Raft '{}' stepping down from LEADER (term {})", nodeId, log.currentTerm());
        }
        failPendingProposals(new NotLeaderException("", ""));
        resetElectionDeadline();
    }

    /** Must be called while holding the lock. */
    private void failPendingProposals(RuntimeException cause) {
        if (pendingProposals.isEmpty()) {
            return;
        }
        for (CompletableFuture<Long> future : pendingProposals.values()) {
            future.completeExceptionally(cause);
        }
        pendingProposals.clear();
    }

    /** Must be called while holding the lock. */
    private void resetElectionDeadline() {
        long span = timings.electionTimeoutMaxMs() - timings.electionTimeoutMinMs();
        long jitter = span <= 0 ? 0 : Math.floorMod(random.nextLong(), span + 1);
        electionDeadlineMillis = clock.getAsLong() + timings.electionTimeoutMinMs() + jitter;
    }

    // ── applying committed entries ─────────────────────────────────────

    private void applyLoop() {
        while (!Thread.currentThread().isInterrupted() && !closed) {
            long indexToApply = -1;
            ByteString command = null;
            synchronized (this) {
                if (lastApplied < commitIndex) {
                    indexToApply = lastApplied + 1;
                    command = log.entryAt(indexToApply).map(LogEntry::command).orElse(null);
                }
            }
            if (indexToApply < 0 || command == null) {
                sleepQuietly(APPLY_POLL_INTERVAL_MS);
                continue;
            }
            try {
                applier.apply(indexToApply, command);
            } catch (RuntimeException e) {
                // Every replica MUST apply the same commands to stay in sync — this is a serious bug if
                // it ever fires, not a routine, swallowable failure. Logged loudly; we still advance
                // rather than wedging this replica forever, matching this codebase's "never crash, log
                // loudly" idiom elsewhere (e.g. JobOutcomeLogger).
                LOG.error("RaftApplier threw while applying index {} on '{}' — this risks state "
                        + "divergence from other replicas and needs investigation.", indexToApply, nodeId, e);
            }
            synchronized (this) {
                lastApplied = indexToApply;
                CompletableFuture<Long> future = pendingProposals.remove(indexToApply);
                if (future != null) {
                    future.complete(indexToApply);
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── leadership-change notification, fired outside the lock ────────

    private void maybeFireLeadershipChange() {
        LeadershipStatus current = leadership();
        if (!current.equals(lastFiredStatus)) {
            lastFiredStatus = current;
            for (Consumer<LeadershipStatus> listener : leadershipListeners) {
                try {
                    listener.accept(current);
                } catch (RuntimeException e) {
                    LOG.warn("Leadership-change listener threw for '{}': {}", nodeId, e.toString());
                }
            }
        }
    }
}
