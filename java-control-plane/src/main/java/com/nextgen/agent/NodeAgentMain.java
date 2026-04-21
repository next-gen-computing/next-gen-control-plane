package com.nextgen.agent;

import com.nextgen.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

public class NodeAgentMain {
    private static final Logger log = LoggerFactory.getLogger(NodeAgentMain.class);
    
    public static void main(String[] args) {
        log.info("Starting NodeAgent Process...");
        AgentConfig config = AgentConfig.fromEnvironment();
        
        NodeAgentOrchestrator orchestrator = new NodeAgentOrchestrator(config);
        orchestrator.orchestrate();

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
