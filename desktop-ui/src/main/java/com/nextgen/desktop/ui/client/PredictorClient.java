package com.nextgen.desktop.ui.client;

import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.PredictorServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC client stub for PredictorService.
 * Returns N/A values when the Predictor service is not available.
 */
public class PredictorClient {
    private static final Logger LOG = LoggerFactory.getLogger(PredictorClient.class);

    private final PredictorServiceGrpc.PredictorServiceBlockingStub blockingStub;

    public PredictorClient(ManagedChannel channel) {
        this.blockingStub = PredictorServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Get prediction for a node. Returns N/A if Predictor service is unavailable.
     */
    public PredictionResult getPrediction(String nodeId, float cpu, float memory) {
        try {
            ControlPlaneProto.PredictionRequest request = ControlPlaneProto.PredictionRequest.newBuilder()
                    .setNodeId(nodeId)
                    .setCpu(cpu)
                    .setMemory(memory)
                    .build();
            ControlPlaneProto.PredictionResponse response = blockingStub.getPrediction(request);
            return new PredictionResult(
                    response.getPredictedLoad(),
                    response.getFailureProbability(),
                    response.getRecommendation()
            );
        } catch (StatusRuntimeException e) {
            LOG.debug("Predictor service unavailable for node {}: {}", nodeId, e.getStatus().getDescription());
            return PredictionResult.unavailable();
        }
    }

    /**
     * Immutable prediction result.
     */
    public static class PredictionResult {
        private final float predictedLoad;
        private final float failureProbability;
        private final String recommendation;
        private final boolean available;

        private PredictionResult(float predictedLoad, float failureProbability, String recommendation) {
            this.predictedLoad = predictedLoad;
            this.failureProbability = failureProbability;
            this.recommendation = recommendation;
            this.available = true;
        }

        private PredictionResult() {
            this.predictedLoad = -1f;
            this.failureProbability = -1f;
            this.recommendation = "N/A";
            this.available = false;
        }

        public static PredictionResult unavailable() {
            return new PredictionResult();
        }

        public float getPredictedLoad() {
            return predictedLoad;
        }

        public float getFailureProbability() {
            return failureProbability;
        }

        public String getRecommendation() {
            return recommendation;
        }

        public boolean isAvailable() {
            return available;
        }

        public String getFailureProbabilityText() {
            return available ? String.format("%.1f%%", failureProbability * 100) : "N/A";
        }

        public String getPredictedLoadText() {
            return available ? String.format("%.1f%%", predictedLoad * 100) : "N/A";
        }
    }
}
