package com.nextgen.controlplane.risk;

import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.proto.ControlPlaneProto.PredictionRequest;
import com.nextgen.proto.ControlPlaneProto.PredictionResponse;
import com.nextgen.proto.PredictorServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real in-process gRPC tests for {@link MLRiskScorer} — a fake {@code PredictorServiceImplBase}, not a
 * Mockito stub, matching this project's established convention for gRPC-facing tests.
 */
class MLRiskScorerTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private PredictorServiceGrpc.PredictorServiceBlockingStub startFakePredictor(
            PredictorServiceGrpc.PredictorServiceImplBase impl) throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return PredictorServiceGrpc.newBlockingStub(channel);
    }

    private static NodeRecord node(String nodeId) {
        return NodeRecord.fresh(nodeId, "10.0.0.1", 50051, nodeId, null, "1.0.0", 0L);
    }

    private static NodeHistory.Sample sample(long atMillis, double rtt) {
        return new NodeHistory.Sample(atMillis, 10f, true, 20f, true, 80f, true,
                true, true, false, true, rtt, true);
    }

    /** A distinguishable, obviously-not-a-real-computation result — proves real delegation occurred. */
    private static final class SentinelFallbackScorer implements RiskScorer {
        @Override
        public RiskAssessment score(NodeRecord node, List<NodeHistory.Sample> recentHistory) {
            return new RiskAssessment(0.42, false, List.of("SENTINEL_FALLBACK"));
        }
    }

    @Test
    void aTrainedModelWithLowFailureProbabilityIsNotAtRisk() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.1f)
                                .setTrainingExampleCount(500).setRecommendation("HEALTHY").build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertFalse(assessment.atRisk());
        assertEquals(0.1, assessment.riskScore(), 1e-6);
    }

    @Test
    void aTrainedModelWithHighFailureProbabilityIsAtRisk() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.9f)
                                .setTrainingExampleCount(500).setRecommendation("AT_RISK").build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertTrue(assessment.atRisk());
        assertEquals(0.9, assessment.riskScore(), 1e-6);
    }

    @Test
    void aTrainedModelsReasonIncludesTheAlgorithmThatProducedIt() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.9f)
                                .setTrainingExampleCount(500).setRecommendation("AT_RISK")
                                .setModelType("xgboost").build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(1, assessment.reasons().size());
        assertTrue(assessment.reasons().get(0).contains("xgboost"),
                "the composed reason should surface which algorithm actually produced the score: "
                        + assessment.reasons().get(0));
        assertTrue(assessment.reasons().get(0).contains("500"));
    }

    @Test
    void anEmptyModelTypeFallsBackToAnHonestUnknownLabelRatherThanBlank() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.9f)
                                .setTrainingExampleCount(500).setRecommendation("AT_RISK").build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertTrue(assessment.reasons().get(0).contains("unknown"));
    }

    @Test
    void aLoadForecastCrossingTheMemoryCeilingAddsABoundedReasonAndNudgesTheScore() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.3f)
                                .setTrainingExampleCount(500).setRecommendation("HEALTHY")
                                .setModelType("xgboost")
                                .setLoadModelTrained(true).setPredictedMemoryPercent(95.0f)
                                .setHorizonSeconds(300f).build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(0.45, assessment.riskScore(), 1e-6, "0.3 base + 0.15 bounded bump");
        assertEquals(2, assessment.reasons().size());
        assertTrue(assessment.reasons().get(1).contains("95"));
        assertTrue(assessment.reasons().get(1).contains("300"));
    }

    @Test
    void theLoadForecastBumpNeverPushesTheScoreAboveOne() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.95f)
                                .setTrainingExampleCount(500).setRecommendation("AT_RISK")
                                .setLoadModelTrained(true).setPredictedMemoryPercent(99.0f)
                                .setHorizonSeconds(300f).build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(1.0, assessment.riskScore(), 1e-6);
    }

    @Test
    void aLoadForecastBelowTheMemoryCeilingDoesNotAddAReasonOrChangeTheScore() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.2f)
                                .setTrainingExampleCount(500).setRecommendation("HEALTHY")
                                .setLoadModelTrained(true).setPredictedMemoryPercent(40.0f)
                                .setHorizonSeconds(300f).build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(0.2, assessment.riskScore(), 1e-6);
        assertEquals(1, assessment.reasons().size());
    }

    @Test
    void anUntrainedLoadForecastIsIgnoredEvenIfPredictedMemoryPercentLooksHigh() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder()
                                .setModelTrained(true).setFailureProbability(0.2f)
                                .setTrainingExampleCount(500).setRecommendation("HEALTHY")
                                .setLoadModelTrained(false).setPredictedMemoryPercent(0.0f)
                                .build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(0.2, assessment.riskScore(), 1e-6);
        assertEquals(1, assessment.reasons().size());
    }

    @Test
    void anUntrainedModelDelegatesExactlyToTheFallback() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        obs.onNext(PredictionResponse.newBuilder().setModelTrained(false).build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(0.42, assessment.riskScore(), "model_trained=false must delegate, never fabricate a score");
        assertEquals(List.of("SENTINEL_FALLBACK"), assessment.reasons());
    }

    @Test
    void anUnreachablePredictorDelegatesToTheFallback() {
        // No server registered under this in-process name at all.
        channel = InProcessChannelBuilder.forName("nothing-listening-here").directExecutor().build();
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(),
                PredictorServiceGrpc.newBlockingStub(channel), 500, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(0.42, assessment.riskScore(), "an unreachable predictor must delegate, not throw");
    }

    @Test
    void aPredictorThatExceedsTheDeadlineDelegatesToTheFallback() throws Exception {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        obs.onNext(PredictionResponse.newBuilder().setModelTrained(true)
                                .setFailureProbability(0.9f).build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 50, 0.5);

        RiskScorer.RiskAssessment assessment = scorer.score(node("n1"), List.of());

        assertEquals(0.42, assessment.riskScore(), "a deadline-exceeded call must fall back, not hang or throw");
    }

    @Test
    void recentHistoryTranslatesToTrendSamplesInOrder() throws Exception {
        AtomicReference<PredictionRequest> captured = new AtomicReference<>();
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = startFakePredictor(
                new PredictorServiceGrpc.PredictorServiceImplBase() {
                    @Override
                    public void getPrediction(PredictionRequest request, StreamObserver<PredictionResponse> obs) {
                        captured.set(request);
                        obs.onNext(PredictionResponse.newBuilder().setModelTrained(false).build());
                        obs.onCompleted();
                    }
                });
        MLRiskScorer scorer = new MLRiskScorer(new SentinelFallbackScorer(), stub, 500, 0.5);

        scorer.score(node("n1"), List.of(sample(100L, 0.01), sample(200L, 0.02), sample(300L, 0.03)));

        PredictionRequest request = captured.get();
        assertEquals("n1", request.getNodeId());
        assertEquals(3, request.getRecentSamplesCount());
        assertEquals(100L, request.getRecentSamples(0).getRecordedAtEpochMillis());
        assertEquals(200L, request.getRecentSamples(1).getRecordedAtEpochMillis());
        assertEquals(300L, request.getRecentSamples(2).getRecordedAtEpochMillis());
        assertEquals(0.03, request.getRecentSamples(2).getPreviousRttSeconds(), 1e-9);
    }
}
