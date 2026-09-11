package com.nextgen.controlplane.raft;

import com.nextgen.proto.ControlPlaneProto.RaftAppendEntriesRequest;
import com.nextgen.proto.ControlPlaneProto.RaftAppendEntriesResponse;
import com.nextgen.proto.ControlPlaneProto.RaftLogEntryProto;
import com.nextgen.proto.ControlPlaneProto.RaftRequestVoteRequest;
import com.nextgen.proto.ControlPlaneProto.RaftRequestVoteResponse;
import com.nextgen.proto.RaftConsensusGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link RaftTransport}: dials each peer's {@code RaftConsensus} service (a separate port
 * from the node-facing {@code ControlPlaneService}, see {@code RAFT_PORT}) and converts between
 * {@link RaftTransport}'s plain-Java request/reply records and the wire proto messages — the only class
 * in this codebase that does that conversion, so {@link RaftNode} itself never depends on protobuf/gRPC
 * types for its own algorithm.
 *
 * <p>Channels are built lazily, one per peer, and reused — peer addresses are static for the lifetime
 * of a process under the current fixed-membership design (see the plan's Stage J scope cuts on dynamic
 * membership). Every call carries a bounded deadline so an unreachable peer fails fast rather than
 * hanging a replicator thread forever, exactly as {@link RaftTransport}'s contract requires.
 */
public final class GrpcRaftTransport implements RaftTransport {

    private static final long CALL_DEADLINE_MS = 1_000;

    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    @Override
    public RequestVoteResult requestVote(RaftPeer peer, RequestVoteArgs args) {
        RaftRequestVoteRequest request = RaftRequestVoteRequest.newBuilder()
                .setTerm(args.term())
                .setCandidateId(args.candidateId())
                .setLastLogIndex(args.lastLogIndex())
                .setLastLogTerm(args.lastLogTerm())
                .build();
        RaftRequestVoteResponse response = stubFor(peer)
                .withDeadlineAfter(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .requestVote(request);
        return new RequestVoteResult(response.getTerm(), response.getVoteGranted());
    }

    @Override
    public AppendEntriesResult appendEntries(RaftPeer peer, AppendEntriesArgs args) {
        RaftAppendEntriesRequest.Builder builder = RaftAppendEntriesRequest.newBuilder()
                .setTerm(args.term())
                .setLeaderId(args.leaderId())
                .setLeaderAddress(args.leaderAddress())
                .setPrevLogIndex(args.prevLogIndex())
                .setPrevLogTerm(args.prevLogTerm())
                .setLeaderCommit(args.leaderCommit());
        for (LogEntry entry : args.entries()) {
            builder.addEntries(toProto(entry));
        }
        RaftAppendEntriesResponse response = stubFor(peer)
                .withDeadlineAfter(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .appendEntries(builder.build());
        return new AppendEntriesResult(response.getTerm(), response.getSuccess(), response.getMatchIndex(),
                response.getConflictIndex(), response.getConflictTerm());
    }

    private RaftConsensusGrpc.RaftConsensusBlockingStub stubFor(RaftPeer peer) {
        ManagedChannel channel = channels.computeIfAbsent(peer.id(), id ->
                ManagedChannelBuilder.forAddress(peer.host(), peer.port()).usePlaintext().build());
        return RaftConsensusGrpc.newBlockingStub(channel);
    }

    static RaftLogEntryProto toProto(LogEntry entry) {
        return RaftLogEntryProto.newBuilder()
                .setIndex(entry.index())
                .setTerm(entry.term())
                .setCommand(entry.command())
                .build();
    }

    static LogEntry fromProto(RaftLogEntryProto proto) {
        return new LogEntry(proto.getIndex(), proto.getTerm(), proto.getCommand());
    }

    static List<LogEntry> fromProtoList(List<RaftLogEntryProto> protos) {
        return protos.stream().map(GrpcRaftTransport::fromProto).toList();
    }

    /** Releases every peer channel this transport opened. Best-effort: a slow-to-close channel does
     * not block process shutdown indefinitely. */
    public void shutdown() {
        for (ManagedChannel channel : channels.values()) {
            channel.shutdownNow();
        }
    }
}
