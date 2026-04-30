package com.nextgen.desktop.v2.grpc;

import com.nextgen.proto.ClusterManagerGrpc;
import com.nextgen.proto.ControlPlaneProto.*;
import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.ClusterMembershipEntity;
import com.nextgen.desktop.v2.db.entities.JoinRequestEntity;
import com.nextgen.desktop.v2.db.entities.ServerEntity;
import com.nextgen.desktop.v2.db.repositories.ClusterMembershipRepository;
import com.nextgen.desktop.v2.db.repositories.JoinRequestRepository;
import com.nextgen.desktop.v2.db.repositories.ServerRepository;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * gRPC service implementation for ClusterManager.
 * Handles node join requests, bidirectional streaming, and commands.
 */
public class ClusterManagerServiceImpl extends ClusterManagerGrpc.ClusterManagerImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterManagerServiceImpl.class);
    
    private final DatabaseManager dbManager;
    private final ServerRepository serverRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final ClusterMembershipRepository clusterMembershipRepository;
    
    public ClusterManagerServiceImpl(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        var em = dbManager.createEntityManager();
        this.serverRepository = new ServerRepository(em);
        this.joinRequestRepository = new JoinRequestRepository(em);
        this.clusterMembershipRepository = new ClusterMembershipRepository(em);
    }
    
    @Override
    public void requestJoin(JoinRequest request, StreamObserver<JoinResponse> responseObserver) {
        try {
            LOG.info("Received join request from node {} to server {}", request.getNodeId(), request.getServerId());
            
            // Verify server exists
            var serverOpt = serverRepository.findById(request.getServerId());
            if (serverOpt.isEmpty()) {
                responseObserver.onNext(JoinResponse.newBuilder()
                        .setApproved(false)
                        .setMessage("Server not found")
                        .build());
                responseObserver.onCompleted();
                return;
            }
            
            ServerEntity server = serverOpt.get();
            
            // Create join request entity
            JoinRequestEntity joinRequestEntity = new JoinRequestEntity();
            joinRequestEntity.setId(UUID.randomUUID().toString());
            joinRequestEntity.setNodeId(request.getNodeId());
            joinRequestEntity.setServerId(request.getServerId());
            joinRequestEntity.setMessage(request.getMessage());
            joinRequestEntity.setStatus(JoinRequestEntity.RequestStatus.PENDING);
            joinRequestEntity.setRequestedAt(LocalDateTime.now());
            
            joinRequestRepository.save(joinRequestEntity);
            
            // For now, auto-approve (later will require manual approval via UI)
            joinRequestEntity.setStatus(JoinRequestEntity.RequestStatus.APPROVED);
            joinRequestEntity.setRespondedAt(LocalDateTime.now());
            joinRequestEntity.setResponseMessage("Auto-approved");
            joinRequestRepository.save(joinRequestEntity);
            
            // Create cluster membership
            ClusterMembershipEntity membership = new ClusterMembershipEntity();
            membership.setId(UUID.randomUUID().toString());
            membership.setNodeId(request.getNodeId());
            membership.setServerId(request.getServerId());
            membership.setStatus(ClusterMembershipEntity.MembershipStatus.APPROVED);
            membership.setJoinedAt(LocalDateTime.now());
            clusterMembershipRepository.save(membership);
            
            // Send response with server certificate
            responseObserver.onNext(JoinResponse.newBuilder()
                    .setApproved(true)
                    .setMessage("Join request approved")
                    .setServerCertificate(com.google.protobuf.ByteString.copyFromUtf8(server.getTlsCertificate()))
                    .build());
            responseObserver.onCompleted();
            
            LOG.info("Join request from node {} approved", request.getNodeId());
            
        } catch (Exception e) {
            LOG.error("Error processing join request", e);
            responseObserver.onNext(JoinResponse.newBuilder()
                    .setApproved(false)
                    .setMessage("Internal error: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public StreamObserver<NodeMessage> establishStream(StreamObserver<ServerMessage> responseObserver) {
        LOG.info("New bidirectional stream established");
        
        return new StreamObserver<>() {
            @Override
            public void onNext(NodeMessage nodeMessage) {
                try {
                    if (nodeMessage.hasHeartbeat()) {
                        handleHeartbeat(nodeMessage.getHeartbeat());
                    } else if (nodeMessage.hasTaskResult()) {
                        handleTaskResult(nodeMessage.getTaskResult());
                    } else if (nodeMessage.hasStatus()) {
                        handleStatusUpdate(nodeMessage.getStatus());
                    }
                } catch (Exception e) {
                    LOG.error("Error processing node message", e);
                }
            }
            
            @Override
            public void onError(Throwable throwable) {
                LOG.error("Stream error", throwable);
            }
            
            @Override
            public void onCompleted() {
                LOG.info("Stream completed");
                responseObserver.onCompleted();
            }
        };
    }
    
    @Override
    public void sendCommand(CommandRequest request, StreamObserver<CommandResponse> responseObserver) {
        try {
            LOG.info("Sending command {} to node {}", request.getCommand(), request.getNodeId());
            
            // For now, just acknowledge
            responseObserver.onNext(CommandResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Command received")
                    .build());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.error("Error sending command", e);
            responseObserver.onNext(CommandResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }
    
    private void handleHeartbeat(Heartbeat heartbeat) {
        LOG.debug("Received heartbeat from node {}: CPU={}, Memory={}", 
                heartbeat.getNodeId(), heartbeat.getCpuUsage(), heartbeat.getMemoryUsage());
        
        // Update cluster membership with latest heartbeat
        var memberships = clusterMembershipRepository.findByNodeId(heartbeat.getNodeId());
        for (var membership : memberships) {
            membership.setLastHeartbeat(LocalDateTime.now());
            membership.setCpuUsagePercent((double) heartbeat.getCpuUsage());
            membership.setMemoryUsageMb((double) heartbeat.getMemoryUsage());
            clusterMembershipRepository.save(membership);
        }
    }
    
    private void handleTaskResult(TaskResult taskResult) {
        LOG.info("Received task result for task {}: success={}", taskResult.getTaskId(), taskResult.getSuccess());
        // TODO: Store task result in database
    }
    
    private void handleStatusUpdate(StatusUpdate status) {
        LOG.info("Received status update from node {}: status={}", status.getNodeId(), status.getStatus());
        // TODO: Update node status in database
    }
}
