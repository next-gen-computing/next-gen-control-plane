package com.nextgen.controlplane.risk;

import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.proto.ControlPlaneProto.PredictionRequest;
import com.nextgen.proto.ControlPlaneProto.PredictionResponse;
import com.nextgen.proto.ControlPlaneProto.TrendSample;
import com.nextgen.proto.PredictorServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Real, trained risk scoring — a thin gRPC client to the {@code PredictorService} (Python,
 * {@code python-predictor/}), the same service {@code ControlPlaneServiceImpl.requestPrediction}
 * already calls advisory-only from {@code submitTask}. Composes a fallback {@link RiskScorer} (in
 * practice {@link RuleBasedRiskScorer}) rather than extending it: on any reason to distrust the
 * predictor's answer — unreachable, timed out, or honestly reporting {@code model_trained=false} — this
 * always literally delegates to {@code fallback.score(...)}, never fabricates or substitutes a number.
 * Same discipline {@code requestPrediction} already established for its own advisory string.
 *
 * <p>Wired in behind {@code ML_RISK_SCORER_ENABLED} (default {@code false}) — see
 * {@code ControlPlaneServer.start()}. The model starts genuinely untrained, so enabling this before
 * real data has been collected and {@code train_risk_model.py} has been run at least once simply means
 * every call falls back to the rule-based scorer anyway; the flag exists so an operator can turn ML
 * scoring on once it is actually worth trusting, without a code change.
 *
 * <p>Stage I: also folds in the independent LSTM load-forecast model, when it exists and is trained.
 * The failure classifier (above) and the forecaster train and become "ready" on separate schedules —
 * one being trained while the other isn't is normal, not an error. The forecast never overrides the
 * classifier's own score; it only adds one bounded, capped nudge on top when the forecast crosses the
 * same memory ceiling {@link RuleBasedRiskScorer} already uses, composing with rather than dominating
 * the primary score. Deliberately scoped narrow — it is not wired into capability-aware job splitting
 * in this stage (see the plan's Stage I notes).
 */
public final class MLRiskScorer implements RiskScorer {
    private static final Logger LOG = LoggerFactory.getLogger(MLRiskScorer.class);

    public static final long DEFAULT_PREDICTOR_DEADLINE_MS = 500;
    public static final double DEFAULT_AT_RISK_THRESHOLD = 0.5;

    /** Same ceiling {@link RuleBasedRiskScorer} already uses for its own memory-pressure rule. */
    private static final double LOAD_FORECAST_MEMORY_THRESHOLD_PERCENT =
            RuleBasedRiskScorer.DEFAULT_MEMORY_CEILING_PERCENT;
    /** Bounded, capped contribution — the forecast composes with the classifier's score, never replaces it. */
    private static final double LOAD_FORECAST_RISK_BUMP = 0.15;

    private final RiskScorer fallback;
    private final PredictorServiceGrpc.PredictorServiceBlockingStub stub;
    private final long deadlineMs;
    private final double atRiskThreshold;

    public MLRiskScorer(RiskScorer fallback, String predictorHost, int predictorPort) {
        this(fallback, predictorHost, predictorPort, DEFAULT_PREDICTOR_DEADLINE_MS, DEFAULT_AT_RISK_THRESHOLD);
    }

    public MLRiskScorer(RiskScorer fallback, String predictorHost, int predictorPort,
                        long deadlineMs, double atRiskThreshold) {
        this(fallback, buildStub(predictorHost, predictorPort), deadlineMs, atRiskThreshold);
    }

    /** Test seam: inject an already-built stub, e.g. one pointed at an in-process fake predictor. */
    MLRiskScorer(RiskScorer fallback, PredictorServiceGrpc.PredictorServiceBlockingStub stub,
                long deadlineMs, double atRiskThreshold) {
        this.fallback = fallback;
        this.stub = stub;
        this.deadlineMs = deadlineMs;
        this.atRiskThreshold = atRiskThreshold;
    }

    private static PredictorServiceGrpc.PredictorServiceBlockingStub buildStub(String host, int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        return PredictorServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public RiskAssessment score(NodeRecord node, List<NodeHistory.Sample> recentHistory) {
        try {
            PredictionRequest request = PredictionRequest.newBuilder()
                    .setNodeId(node.getNodeId())
                    .setCpu(node.getCpuUsage())
                    .setMemory(node.getMemoryUsage())
                    .addAllRecentSamples(recentHistory.stream().map(MLRiskScorer::toTrendSample).toList())
                    .build();
            PredictionResponse response = stub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .getPrediction(request);

            if (!response.getModelTrained()) {
                // Honest, not a failure: the predictor is reachable but has no real model to serve yet.
                return fallback.score(node, recentHistory);
            }

            double riskScore = response.getFailureProbability();
            String modelType = response.getModelType().isEmpty() ? "unknown" : response.getModelType();
            List<String> reasons = new ArrayList<>();
            reasons.add(String.format(Locale.ROOT, "ML model (%s, n=%d): %s",
                    modelType, response.getTrainingExampleCount(), response.getRecommendation()));

            if (response.getLoadModelTrained()
                    && response.getPredictedMemoryPercent() >= LOAD_FORECAST_MEMORY_THRESHOLD_PERCENT) {
                riskScore = Math.min(1.0, riskScore + LOAD_FORECAST_RISK_BUMP);
                reasons.add(String.format(Locale.ROOT,
                        "LSTM forecast: memory predicted to reach %.0f%% within %.0fs",
                        response.getPredictedMemoryPercent(), response.getHorizonSeconds()));
            }

            boolean atRisk = riskScore >= atRiskThreshold;
            return new RiskAssessment(riskScore, atRisk, List.copyOf(reasons));
        } catch (Exception e) {
            LOG.warn("⚠ ML predictor unavailable for node '{}', falling back to rule-based scoring: {}",
                    node.getNodeId(), e.getMessage());
            return fallback.score(node, recentHistory);
        }
    }

    private static TrendSample toTrendSample(NodeHistory.Sample sample) {
        return TrendSample.newBuilder()
                .setRecordedAtEpochMillis(sample.recordedAtMillis())
                .setCpuPercent(sample.cpuPercent())
                .setCpuAvailable(sample.cpuAvailable())
                .setMemoryPercent(sample.memoryPercent())
                .setMemoryAvailable(sample.memoryAvailable())
                .setBatteryPercent(sample.batteryPercent())
                .setBatteryAvailable(sample.batteryAvailable())
                .setCharging(sample.charging())
                .setChargingKnown(sample.chargingKnown())
                .setOnAcPower(sample.onAcPower())
                .setOnAcPowerKnown(sample.onAcPowerKnown())
                .setPreviousRttSeconds(sample.previousRttSeconds())
                .setPreviousRttAvailable(sample.previousRttAvailable())
                .build();
    }
}
