package com.nextgen.controlplane.raft;

import com.nextgen.proto.ControlPlaneProto.RaftAppendEntriesRequest;
import com.nextgen.proto.ControlPlaneProto.RaftAppendEntriesResponse;
import com.nextgen.proto.ControlPlaneProto.RaftRequestVoteRequest;
import com.nextgen.proto.ControlPlaneProto.RaftRequestVoteResponse;
import com.nextgen.proto.RaftConsensusGrpc;
import io.grpc.stub.StreamObserver;

/**
 * The inbound half of {@link GrpcRaftTransport}: unwraps a peer's {@code RequestVote}/
 * {@code AppendEntries} proto call into {@link RaftTransport}'s plain-Java records, hands it to
 * {@link RaftNode}'s own handler, and wraps the reply back into proto. Registered on the same gRPC
 * server as {@code ControlPlaneService} but bound to {@code RAFT_PORT}, not {@code GRPC_PORT}, and
 * deliberately outside {@code MtlsPolicyInterceptor} — a peer replica is not a node and presents no
 * node certificate.
 */
public final class RaftConsensusServiceImpl extends RaftConsensusGrpc.RaftConsensusImplBase {

    private final RaftNode raftNode;

    public RaftConsensusServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void requestVote(RaftRequestVoteRequest request, StreamObserver<RaftRequestVoteResponse> responseObserver) {
        RaftTransport.RequestVoteResult result = raftNode.handleRequestVote(new RaftTransport.RequestVoteArgs(
                request.getTerm(), request.getCandidateId(), request.getLastLogIndex(), request.getLastLogTerm()));
        responseObserver.onNext(RaftRequestVoteResponse.newBuilder()
                .setTerm(result.term())
                .setVoteGranted(result.voteGranted())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(RaftAppendEntriesRequest request, StreamObserver<RaftAppendEntriesResponse> responseObserver) {
        RaftTransport.AppendEntriesArgs args = new RaftTransport.AppendEntriesArgs(
                request.getTerm(), request.getLeaderId(), request.getLeaderAddress(),
                request.getPrevLogIndex(), request.getPrevLogTerm(),
                GrpcRaftTransport.fromProtoList(request.getEntriesList()), request.getLeaderCommit());
        RaftTransport.AppendEntriesResult result = raftNode.handleAppendEntries(args);
        responseObserver.onNext(RaftAppendEntriesResponse.newBuilder()
                .setTerm(result.term())
                .setSuccess(result.success())
                .setMatchIndex(result.matchIndex())
                .setConflictIndex(result.conflictIndex())
                .setConflictTerm(result.conflictTerm())
                .build());
        responseObserver.onCompleted();
    }
}
