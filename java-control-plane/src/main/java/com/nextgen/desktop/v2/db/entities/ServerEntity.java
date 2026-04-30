package com.nextgen.desktop.v2.db.entities;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * JPA Entity representing a ControlPlane Server.
 * Stores server configuration, specifications, and TLS certificates.
 */
@Entity
@Table(name = "servers")
public class ServerEntity {
    
    @Id
    @Column(length = 36)
    private String id; // UUID
    
    @Column(nullable = false, length = 100)
    private String name;
    
    @Column(nullable = false)
    private Integer grpcPort;
    
    @Column(name = "cpu_cores")
    private Integer cpuCores;
    
    @Column(name = "memory_gb")
    private Double memoryGb;
    
    @Column(name = "os_info", length = 200)
    private String osInfo;
    
    @Lob
    @Column(name = "tls_cert")
    private String tlsCertificate; // PEM format as string
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServerStatus status;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "connection_token", length = 64, unique = true)
    private String connectionToken; // Token for nodes to join
    
    public ServerEntity() {}
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public Integer getGrpcPort() { return grpcPort; }
    public void setGrpcPort(Integer grpcPort) { this.grpcPort = grpcPort; }
    
    public Integer getCpuCores() { return cpuCores; }
    public void setCpuCores(Integer cpuCores) { this.cpuCores = cpuCores; }
    
    public Double getMemoryGb() { return memoryGb; }
    public void setMemoryGb(Double memoryGb) { this.memoryGb = memoryGb; }
    
    public String getOsInfo() { return osInfo; }
    public void setOsInfo(String osInfo) { this.osInfo = osInfo; }
    
    public String getTlsCertificate() { return tlsCertificate; }
    public void setTlsCertificate(String tlsCertificate) { this.tlsCertificate = tlsCertificate; }
    
    public ServerStatus getStatus() { return status; }
    public void setStatus(ServerStatus status) { this.status = status; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public String getConnectionToken() { return connectionToken; }
    public void setConnectionToken(String connectionToken) { this.connectionToken = connectionToken; }
    
    public enum ServerStatus {
        ACTIVE,
        INACTIVE,
        STARTING,
        STOPPING
    }
}
