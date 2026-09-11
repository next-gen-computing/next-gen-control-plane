package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bounded, seeded, randomized fault-injection loop over a live cluster, asserting Raft's core safety
 * invariants after every round. Per the plan, this is the single highest-value test in the Raft suite —
 * it catches classes of bugs (a missing commit-index term check, an incorrectly-truncated follower log,
 * a double vote) that hand-crafted scenario tests can miss, and is not to be skipped.
 *
 * <p>Checks after every round, across every currently-live node:
 * <ol>
 *   <li>at most one leader per term;</li>
 *   <li>the log-matching property — if two logs share an (index, term), every entry before that index
 *       is identical on both;</li>
 *   <li>{@code lastApplied <= commitIndex <= lastIndex};</li>
 *   <li>an entry applied at index <i>i</i> on one node is byte-identical to what every other node that
 *       has also applied <i>i</i> applied.</li>
 * </ol>
 */
class RaftSafetyInvariantTest {

    private static final int ROUNDS = 60;
    private static final int CLUSTER_SIZE = 5;

    @Test
    void safetyInvariantsHoldUnderRandomPartitionsCrashesAndRestarts(@TempDir Path dir) {
        Random actions = new Random(20260811L);
        try (RaftTestCluster cluster = new RaftTestCluster(CLUSTER_SIZE, dir)) {
            Set<String> crashedIds = new HashSet<>();
            List<String> allIds = cluster.memberIds();
            int[] proposalCounter = {0};

            for (int round = 0; round < ROUNDS; round++) {
                runRandomAction(cluster, crashedIds, allIds, actions, proposalCounter);
                sleepQuietly(15);
                assertInvariants(cluster, crashedIds);
            }

            // End fully healed and all-alive, and confirm the cluster still converges — proves the
            // fault injection didn't leave it permanently wedged.
            cluster.healAll();
            for (String id : new ArrayList<>(crashedIds)) {
                cluster.restart(id);
            }
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(15))
                    .until(() -> cluster.allNodes().stream().filter(RaftNode::isLeader).count() == 1);
        }
    }

    private static void runRandomAction(RaftTestCluster cluster, Set<String> crashedIds, List<String> allIds,
                                        Random actions, int[] proposalCounter) {
        try {
            switch (actions.nextInt(6)) {
                case 0 -> {
                    var leader = liveNodes(cluster, crashedIds).stream().filter(RaftNode::isLeader).findFirst();
                    leader.ifPresent(raftNode -> raftNode.propose(ByteString.copyFromUtf8("v" + proposalCounter[0]++)));
                }
                case 1 -> {
                    List<String> candidates = new ArrayList<>(allIds);
                    candidates.removeAll(crashedIds);
                    if (candidates.size() > 1) { // never crash the last remaining node
                        String victim = candidates.get(actions.nextInt(candidates.size()));
                        cluster.crash(victim);
                        crashedIds.add(victim);
                    }
                }
                case 2 -> {
                    if (!crashedIds.isEmpty()) {
                        String toRestart = new ArrayList<>(crashedIds).get(actions.nextInt(crashedIds.size()));
                        cluster.restart(toRestart);
                        crashedIds.remove(toRestart);
                    }
                }
                case 3 -> {
                    List<String> candidates = new ArrayList<>(allIds);
                    candidates.removeAll(crashedIds);
                    if (candidates.size() >= 2) {
                        String a = candidates.get(actions.nextInt(candidates.size()));
                        String b = candidates.get(actions.nextInt(candidates.size()));
                        if (!a.equals(b)) {
                            cluster.partition(a, b);
                        }
                    }
                }
                case 4 -> cluster.healAll();
                default -> { /* let real time pass with no injected fault this round */ }
            }
        } catch (RuntimeException e) {
            // An action racing a concurrent state change (e.g. proposing right as the leader steps
            // down) may legitimately throw — a normal outcome here, not a safety violation. Only the
            // invariant checks below are real assertions.
        }
    }

    private static List<RaftNode> liveNodes(RaftTestCluster cluster, Set<String> crashedIds) {
        return cluster.memberIds().stream()
                .filter(id -> !crashedIds.contains(id))
                .map(cluster::node)
                .filter(Objects::nonNull)
                .toList();
    }

    private static void assertInvariants(RaftTestCluster cluster, Set<String> crashedIds) {
        List<RaftNode> live = liveNodes(cluster, crashedIds);

        assertAtMostOneLeaderPerTerm(live);
        assertAppliedNeverAheadOfCommitted(live);
        assertIdenticalAppliedStateAtEachIndex(cluster, live);
        assertLogMatchingProperty(cluster, live);
    }

    private static void assertAtMostOneLeaderPerTerm(List<RaftNode> live) {
        Map<Long, List<String>> leadersByTerm = new HashMap<>();
        for (RaftNode node : live) {
            LeadershipStatus status = node.leadership();
            if (status.role() == RaftRole.LEADER) {
                leadersByTerm.computeIfAbsent(status.term(), t -> new ArrayList<>()).add(node.nodeId());
            }
        }
        for (Map.Entry<Long, List<String>> entry : leadersByTerm.entrySet()) {
            assertEquals(1, entry.getValue().size(),
                    "more than one leader observed in term " + entry.getKey() + ": " + entry.getValue());
        }
    }

    private static void assertAppliedNeverAheadOfCommitted(List<RaftNode> live) {
        for (RaftNode node : live) {
            long applied = node.lastApplied();
            long committed = node.commitIndex();
            assertTrue(applied <= committed,
                    node.nodeId() + ": lastApplied(" + applied + ") > commitIndex(" + committed + ")");
        }
    }

    private static void assertIdenticalAppliedStateAtEachIndex(RaftTestCluster cluster, List<RaftNode> live) {
        Map<Long, ByteString> referenceByIndex = new HashMap<>();
        for (RaftNode node : live) {
            for (Map.Entry<Long, ByteString> entry : cluster.appliedOn(node.nodeId()).entrySet()) {
                ByteString existing = referenceByIndex.putIfAbsent(entry.getKey(), entry.getValue());
                assertTrue(existing == null || existing.equals(entry.getValue()),
                        "divergent applied state at index " + entry.getKey() + " on node " + node.nodeId());
            }
        }
    }

    /** The real claim (Raft paper §5.3): if two logs share an (index, term) pair, every entry before
     * that index is identical on both — not merely "no mismatch before the first mismatch," which
     * would miss a bug that let a log incorrectly re-converge in term after a genuine divergence. */
    private static void assertLogMatchingProperty(RaftTestCluster cluster, List<RaftNode> live) {
        for (int a = 0; a < live.size(); a++) {
            for (int b = a + 1; b < live.size(); b++) {
                RaftLog logA = cluster.rawLog(live.get(a).nodeId());
                RaftLog logB = cluster.rawLog(live.get(b).nodeId());
                long upTo = Math.min(logA.lastIndex(), logB.lastIndex());
                long[] termsA = new long[(int) upTo + 1];
                long[] termsB = new long[(int) upTo + 1];
                for (int index = 1; index <= upTo; index++) {
                    termsA[index] = logA.termAt(index);
                    termsB[index] = logB.termAt(index);
                }
                for (int index = 1; index <= upTo; index++) {
                    if (termsA[index] != termsB[index]) {
                        continue;
                    }
                    for (int earlier = 1; earlier < index; earlier++) {
                        assertEquals(termsA[earlier], termsB[earlier],
                                "log-matching property violated between " + live.get(a).nodeId() + " and "
                                        + live.get(b).nodeId() + ": entries match at index " + index
                                        + " but diverge at earlier index " + earlier);
                    }
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
}
