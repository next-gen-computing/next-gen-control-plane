package com.nextgen.desktop.ui.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Manages gRPC channels and connections to ControlPlane and Predictor services.
 */
public class GrpcConnectionManager {
    private static final Logger LOG = LoggerFactory.getLogger(GrpcConnectionManager.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_CONTROL_PLANE_PORT = 50051;
    private static final int DEFAULT_PREDICTOR_PORT = 50052;

    private ManagedChannel controlPlaneChannel;
    private ManagedChannel predictorChannel;

    private ControlPlaneClient controlPlaneClient;
    private PredictorClient predictorClient;

    private String currentHost = DEFAULT_HOST;
    private int currentControlPlanePort = DEFAULT_CONTROL_PLANE_PORT;
    private int currentPredictorPort = DEFAULT_PREDICTOR_PORT;

    public GrpcConnectionManager() {
        // Initialize with defaults; connections are established lazily
    }

    public synchronized ControlPlaneClient getControlPlaneClient() {
        if (controlPlaneClient == null) {
            connectControlPlane();
        }
        return controlPlaneClient;
    }

    public synchronized PredictorClient getPredictorClient() {
        if (predictorClient == null) {
            connectPredictor();
        }
        return predictorClient;
    }

    public void connectTo(String host, int controlPlanePort, int predictorPort) {
        shutdown();
        this.currentHost = host;
        this.currentControlPlanePort = controlPlanePort;
        this.currentPredictorPort = predictorPort;
        LOG.info("Configured connection to {}:{}/{}:", host, controlPlanePort, predictorPort);
    }

    private void connectControlPlane() {
        try {
            controlPlaneChannel = ManagedChannelBuilder
                    .forAddress(currentHost, currentControlPlanePort)
                    .usePlaintext()
                    .maxRetryAttempts(3)
                    .build();
            controlPlaneClient = new ControlPlaneClient(controlPlaneChannel);
            LOG.info("Connected to ControlPlane at {}:{}", currentHost, currentControlPlanePort);
        } catch (Exception e) {
            LOG.error("Failed to connect to ControlPlane at {}:{}", currentHost, currentControlPlanePort, e);
        }
    }

    private void connectPredictor() {
        try {
            predictorChannel = ManagedChannelBuilder
                    .forAddress(currentHost, currentPredictorPort)
                    .usePlaintext()
                    .maxRetryAttempts(3)
                    .build();
            predictorClient = new PredictorClient(predictorChannel);
            LOG.info("Connected to Predictor at {}:{}", currentHost, currentPredictorPort);
        } catch (Exception e) {
            LOG.error("Failed to connect to Predictor at {}:{}", currentHost, currentPredictorPort, e);
        }
    }

    public boolean isControlPlaneConnected() {
        return controlPlaneChannel != null && !controlPlaneChannel.isShutdown();
    }

    public boolean isPredictorConnected() {
        return predictorChannel != null && !predictorChannel.isShutdown();
    }

    public void shutdown() {
        if (controlPlaneChannel != null) {
            try {
                controlPlaneChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            controlPlaneChannel = null;
            controlPlaneClient = null;
        }
        if (predictorChannel != null) {
            try {
                predictorChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            predictorChannel = null;
            predictorClient = null;
        }
    }

    public String getCurrentHost() {
        return currentHost;
    }

    public int getCurrentControlPlanePort() {
        return currentControlPlanePort;
    }

    public int getCurrentPredictorPort() {
        return currentPredictorPort;
    }
}
