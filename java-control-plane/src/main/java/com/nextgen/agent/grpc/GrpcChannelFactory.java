package com.nextgen.agent.grpc;

import com.nextgen.agent.config.AgentConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

public class GrpcChannelFactory {
    public static ManagedChannel buildChannel(AgentConfig config) {
        return ManagedChannelBuilder.forAddress(config.getControlPlaneHost(), config.getControlPlanePort())
                .usePlaintext() // No TLS for Phase 1 by default unless configured
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .enableRetry()
                .maxRetryAttempts(5)
                .build();
    }
}
