package com.nextgen.agent.service;

import com.nextgen.agent.state.NodeState;
import com.nextgen.controlplane.grpc.NodeAgentServiceGrpc;
import com.nextgen.controlplane.grpc.RegisterRequest;
import com.nextgen.controlplane.grpc.RegisterResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class RegistrationService {
    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final int MAX_RETRIES = 5;

    public RegisterResponse register(NodeAgentServiceGrpc.NodeAgentServiceBlockingStub stub, String nodeId) {
        RegisterRequest request = RegisterRequest.newBuilder()
                .setNodeId(nodeId)
                .setHostname(getHostname())
                .setIpAddress(getIpAddress())
                .build();

        int attempt = 0;
        long backoff = 1000;

        while (attempt < MAX_RETRIES) {
            try {
                log.info("Attempting registration (try {}/{})", attempt + 1, MAX_RETRIES);
                RegisterResponse response = stub.registerNode(request);
                log.info("Registration successful. Assigned cluster ID: {}", response.getClusterId());
                return response;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT ||
                    e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                    log.error("Hard rejection from server: {}", e.getStatus());
                    throw e; // Non-retriable
                }
                
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("Max registration retries exceeded.");
                    throw e;
                }

                try {
                    long jitter = (long) (Math.random() * backoff * 0.2) - (long) (backoff * 0.1);
                    Thread.sleep(backoff + jitter);
                    backoff *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during registration backoff", ie);
                }
            }
        }
        throw new IllegalStateException("Registration failed");
    }

    private String getHostname() {
        try { return InetAddress.getLocalHost().getHostName(); } 
        catch (UnknownHostException e) { return "unknown-host"; }
    }

    private String getIpAddress() {
        try { return InetAddress.getLocalHost().getHostAddress(); } 
        catch (UnknownHostException e) { return "127.0.0.1"; }
    }
}
