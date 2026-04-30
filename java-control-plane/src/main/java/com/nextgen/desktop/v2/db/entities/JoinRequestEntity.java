package com.nextgen.desktop.v2.db.entities;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * JPA Entity representing a node's request to join a server.
 * Tracks approval workflow and communication.
 */
@Entity
@Table(name = "join_requests")
public class JoinRequestEntity {
    
    @Id
    @Column(length = 36)
    private String id; // UUID
    
    @Column(name = "node_id", nullable = false, length = 36)
    private String nodeId;
    
    @Column(name = "server_id", nullable = false, length = 36)
    private String serverId;
    
    @Column(length = 500)
    private String message; // Optional message from node
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;
    
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;
    
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;
    
    @Column(name = "response_message", length = 500)
    private String responseMessage; // Message from server
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    public JoinRequestEntity() {}
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    
    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    
    public LocalDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(LocalDateTime respondedAt) { this.respondedAt = respondedAt; }
    
    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public enum RequestStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
