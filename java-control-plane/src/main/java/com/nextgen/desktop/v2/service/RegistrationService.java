package com.nextgen.desktop.v2.service;

import com.nextgen.desktop.v2.db.DatabaseManager;
import com.nextgen.desktop.v2.db.entities.NodeEntity;
import com.nextgen.desktop.v2.db.entities.ServerEntity;
import com.nextgen.desktop.v2.db.repositories.NodeRepository;
import com.nextgen.desktop.v2.db.repositories.ServerRepository;
import com.nextgen.desktop.v2.util.SystemSpecDetector;
import com.nextgen.desktop.v2.util.TlsCertificateGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling server and node registration.
 */
public class RegistrationService {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationService.class);
    
    private final DatabaseManager dbManager;
    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    
    public RegistrationService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        var em = dbManager.createEntityManager();
        this.serverRepository = new ServerRepository(em);
        this.nodeRepository = new NodeRepository(em);
    }
    
    /**
     * Register a new server.
     * 
     * @param name Server name
     * @param grpcPort gRPC port
     * @return The registered server entity
     */
    public ServerEntity registerServer(String name, Integer grpcPort) {
        // Detect system specs
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        // Generate TLS certificate
        String cert = TlsCertificateGenerator.generateCertificate(name, "NextGenControlPlane");
        
        // Generate connection token
        String token = TlsCertificateGenerator.generateConnectionToken();
        
        // Create server entity
        ServerEntity server = new ServerEntity();
        server.setId(UUID.randomUUID().toString());
        server.setName(name);
        server.setGrpcPort(grpcPort);
        server.setCpuCores((Integer) specs.get("cpuCores"));
        server.setMemoryGb((Double) specs.get("memoryGb"));
        server.setOsInfo((String) specs.get("osInfo"));
        server.setTlsCertificate(cert);
        server.setConnectionToken(token);
        server.setStatus(ServerEntity.ServerStatus.INACTIVE);
        
        return serverRepository.save(server);
    }
    
    /**
     * Register a new node.
     * 
     * @param name Node name
     * @return The registered node entity
     */
    public NodeEntity registerNode(String name) {
        // Detect system specs
        Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
        
        // Generate TLS certificate
        String cert = TlsCertificateGenerator.generateCertificate(name, "NextGenControlPlane");
        
        // Create node entity
        NodeEntity node = new NodeEntity();
        node.setId(UUID.randomUUID().toString());
        node.setName(name);
        node.setHostname((String) specs.get("hostname"));
        node.setCpuCores((Integer) specs.get("cpuCores"));
        node.setMemoryGb((Double) specs.get("memoryGb"));
        node.setDiskGb((Double) specs.get("diskGb"));
        node.setOsInfo((String) specs.get("osInfo"));
        node.setTlsCertificate(cert);
        node.setStatus(NodeEntity.NodeStatus.OFFLINE);
        
        return nodeRepository.save(node);
    }
    
    /**
     * Get a server by ID.
     */
    public Optional<ServerEntity> getServer(String id) {
        return serverRepository.findById(id);
    }
    
    /**
     * Get a node by ID.
     */
    public Optional<NodeEntity> getNode(String id) {
        return nodeRepository.findById(id);
    }
    
    /**
     * Get all servers.
     */
    public java.util.List<ServerEntity> getAllServers() {
        return serverRepository.findAll();
    }
    
    /**
     * Get all nodes.
     */
    public java.util.List<NodeEntity> getAllNodes() {
        return nodeRepository.findAll();
    }
    
    /**
     * Update server status.
     */
    public void updateServerStatus(String serverId, ServerEntity.ServerStatus status) {
        serverRepository.findById(serverId).ifPresent(server -> {
            server.setStatus(status);
            serverRepository.save(server);
        });
    }
    
    /**
     * Update node status.
     */
    public void updateNodeStatus(String nodeId, NodeEntity.NodeStatus status) {
        nodeRepository.findById(nodeId).ifPresent(node -> {
            node.setStatus(status);
            nodeRepository.save(node);
        });
    }
}
