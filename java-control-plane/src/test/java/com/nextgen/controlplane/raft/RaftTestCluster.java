package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * A small in-process cluster of {@link RaftNode}s wired to a shared {@link InMemoryRaftTransport}, for
 * this package's Phase-A tests (no gRPC, no real network). Uses real (deliberately short) timings and
 * real threads — tests drive assertions with Awaitility rather than a simulated clock, matching this
 * codebase's established {@code HeartbeatMonitorTest}/{@code RiskMonitorTest} convention of real
 * thread-driven polling loops observed from outside.
 */
final class RaftTestCluster implements Closeable {

    /** Short enough for tests to run in well under a second; wide enough to avoid pure timing flakes. */
    static final RaftTimings FAST_TIMINGS = new RaftTimings(20, 100, 200, 1000);

    private final InMemoryRaftTransport transport = new InMemoryRaftTransport();
    private final Map<String, RaftNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, RaftLog> logs = new ConcurrentHashMap<>();
    // Keyed by log index, not append order: a restarted node's RaftNode correctly RE-applies from
    // index 1 (lastApplied is deliberately not persisted — see RaftLog's class doc), so an append-only
    // list would double-count entries after any crash/restart. A map keyed by index instead models the
    // real, idempotent "apply(index, command) may be called more than once for the same index" contract.
    private final Map<String, ConcurrentSkipListMap<Long, ByteString>> applied = new ConcurrentHashMap<>();
    private final Path rootDir;
    private final List<RaftPeer> members;
    private final RaftTimings timings;

    RaftTestCluster(int size, Path tempDir) {
        this(size, tempDir, FAST_TIMINGS);
    }

    RaftTestCluster(int size, Path tempDir, RaftTimings timings) {
        this.rootDir = tempDir;
        this.timings = timings;
        List<RaftPeer> memberList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            memberList.add(new RaftPeer("n" + i, "localhost", 0));
        }
        this.members = List.copyOf(memberList);
        for (RaftPeer peer : members) {
            startNode(peer.id());
        }
    }

    private void startNode(String id) {
        RaftLog log = new RaftLog(rootDir.resolve(id));
        logs.put(id, log);
        applied.putIfAbsent(id, new ConcurrentSkipListMap<>());
        RaftApplier applier = (index, command) -> applied.get(id).put(index, command);
        RaftNode node = new RaftNode(id, "localhost:0", members, log, applier, transport, timings);
        nodes.put(id, node);
        transport.register(node);
        node.start();
    }

    RaftNode node(String id) {
        return nodes.get(id);
    }

    /** Direct access to a node's on-disk log, for tests that verify the log-matching property across
     * replicas rather than only their applied-command lists. */
    RaftLog rawLog(String id) {
        return logs.get(id);
    }

    List<RaftNode> allNodes() {
        return List.copyOf(nodes.values());
    }

    List<String> memberIds() {
        return members.stream().map(RaftPeer::id).toList();
    }

    /** Fully stops a node's threads and marks it unreachable — a real crash, not just a network split. */
    void crash(String id) {
        transport.crash(id);
        RaftNode node = nodes.remove(id);
        if (node != null) {
            node.close();
        }
    }

    /** Restarts a crashed node from its own on-disk log directory — the durability-across-restart proof. */
    RaftNode restart(String id) {
        transport.restore(id);
        RaftLog reopenedLog = new RaftLog(rootDir.resolve(id));
        logs.put(id, reopenedLog);
        applied.putIfAbsent(id, new ConcurrentSkipListMap<>());
        RaftApplier applier = (index, command) -> applied.get(id).put(index, command);
        RaftNode node = new RaftNode(id, "localhost:0", members, reopenedLog, applier, transport, timings);
        nodes.put(id, node);
        transport.register(node);
        node.start();
        return node;
    }

    void partition(String a, String b) {
        transport.partition(a, b);
    }

    void healAll() {
        transport.healAll();
    }

    /** Applied commands for {@code id}, keyed by log index (1-based) so callers can compare across
     * nodes and across a crash/restart without an append-order artifact. */
    Map<Long, ByteString> appliedOn(String id) {
        return applied.getOrDefault(id, new ConcurrentSkipListMap<>());
    }

    /** Convenience view for tests that just want the applied commands in index order. */
    List<ByteString> appliedInOrderOn(String id) {
        return List.copyOf(appliedOn(id).values());
    }

    @Override
    public void close() {
        for (RaftNode node : nodes.values()) {
            node.close();
        }
        for (RaftLog log : logs.values()) {
            log.close();
        }
    }
}
