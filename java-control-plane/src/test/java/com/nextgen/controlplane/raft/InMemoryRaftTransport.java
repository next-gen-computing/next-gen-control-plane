package com.nextgen.controlplane.raft;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes Raft RPCs directly to peers' {@code handleRequestVote}/{@code handleAppendEntries} — no gRPC,
 * no real network. Supports injecting a one-way-blind partition between a pair of nodes and simulating
 * an unreachable/crashed node, for the fault-injection tests in this package.
 */
final class InMemoryRaftTransport implements RaftTransport {
    private final Map<String, RaftNode> nodesById = new ConcurrentHashMap<>();
    private final Set<String> crashed = ConcurrentHashMap.newKeySet();
    private final Set<UnorderedPair> partitioned = ConcurrentHashMap.newKeySet();

    void register(RaftNode node) {
        nodesById.put(node.nodeId(), node);
    }

    void unregister(String nodeId) {
        nodesById.remove(nodeId);
    }

    void crash(String nodeId) {
        crashed.add(nodeId);
    }

    void restore(String nodeId) {
        crashed.remove(nodeId);
    }

    void partition(String a, String b) {
        partitioned.add(new UnorderedPair(a, b));
    }

    void healAll() {
        partitioned.clear();
    }

    private boolean reachable(String fromId, String toId) {
        return !crashed.contains(fromId) && !crashed.contains(toId)
                && !partitioned.contains(new UnorderedPair(fromId, toId));
    }

    @Override
    public RequestVoteResult requestVote(RaftPeer peer, RequestVoteArgs args) {
        RaftNode target = nodesById.get(peer.id());
        if (target == null || !reachable(args.candidateId(), peer.id())) {
            throw new IllegalStateException("unreachable in this test topology: " + peer.id());
        }
        return target.handleRequestVote(args);
    }

    @Override
    public AppendEntriesResult appendEntries(RaftPeer peer, AppendEntriesArgs args) {
        RaftNode target = nodesById.get(peer.id());
        if (target == null || !reachable(args.leaderId(), peer.id())) {
            throw new IllegalStateException("unreachable in this test topology: " + peer.id());
        }
        return target.handleAppendEntries(args);
    }

    private record UnorderedPair(String a, String b) {
        UnorderedPair {
            if (a.compareTo(b) > 0) {
                String tmp = a;
                a = b;
                b = tmp;
            }
        }
    }
}
