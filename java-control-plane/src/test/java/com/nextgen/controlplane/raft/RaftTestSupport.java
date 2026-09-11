package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/** Shared helpers for this package's thread-driven cluster tests. */
final class RaftTestSupport {
    private RaftTestSupport() {
    }

    /**
     * Waits for some node to become leader and returns THAT node — captured atomically inside the
     * await condition, not re-queried afterward. Re-querying "which node is leader" as a second,
     * separate step after the await condition passes is a real race: under contested elections the
     * leader that satisfied the condition can already have stepped down by the time a second query
     * runs, throwing {@code NoSuchElementException} instead of returning a stale-but-valid reference.
     */
    static RaftNode awaitLeader(RaftTestCluster cluster) {
        return awaitLeader(cluster, Duration.ofSeconds(3));
    }

    static RaftNode awaitLeader(RaftTestCluster cluster, Duration timeout) {
        AtomicReference<RaftNode> leaderRef = new AtomicReference<>();
        await().atMost(timeout).pollInterval(Duration.ofMillis(10)).until(() -> {
            var leader = cluster.allNodes().stream().filter(RaftNode::isLeader).findFirst();
            leader.ifPresent(leaderRef::set);
            return leader.isPresent();
        });
        return leaderRef.get();
    }

    /**
     * Proposes {@code payload} via whichever node is currently leader, retrying against a freshly
     * re-observed leader on {@link NotLeaderException} — a node captured by {@link #awaitLeader} a
     * moment earlier can genuinely have stepped down by the time {@code propose} actually runs, under
     * the short/contested election timings these tests deliberately use; a real client would retry
     * exactly the same way (see the plan's Stage J client-redirect design), so this is representative
     * behavior, not a test-only workaround.
     */
    static long proposeViaCurrentLeader(RaftTestCluster cluster, String payload, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Exception lastError = null;
        while (System.currentTimeMillis() < deadline) {
            long remaining = Math.max(100, deadline - System.currentTimeMillis());
            RaftNode leader = awaitLeader(cluster, Duration.ofMillis(remaining));
            try {
                return leader.propose(ByteString.copyFromUtf8(payload)).get(1, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof NotLeaderException) {
                    lastError = e;
                    continue;
                }
                throw e;
            } catch (TimeoutException e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("propose did not succeed via any leader within " + timeout, lastError);
    }
}
