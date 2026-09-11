package com.nextgen.controlplane.capacity;

import com.nextgen.controlplane.NodeRecord;

/**
 * Hand-tuned capacity score, combining declared hardware (CPU cores, memory), live headroom (how much
 * of that hardware is currently free), and predictive risk (a node already flagged as likely to fail
 * soon should receive proportionally less new work) into one positive weight.
 *
 * <p>Explicit placeholder, not a tuned product — same rationale as {@code RuleBasedRiskScorer}: simple
 * and interpretable now, replaceable later once real outcome data exists to train a learned version
 * against.
 *
 * <p>Reads <i>live</i> headroom, which gives capability-aware splitting a useful emergent property
 * with no extra fairness logic: a node loaded up by one job's share scores lower on the next job
 * submitted shortly after (its next heartbeat reflects the reduced headroom), so back-to-back
 * submissions naturally spread out rather than piling onto whichever node looked strongest once.
 */
public final class HeuristicNodeCapacityScorer implements NodeCapacityScorer {

    public static final double DEFAULT_CPU_CORE_WEIGHT = 1.0;
    public static final double DEFAULT_MEMORY_GB_WEIGHT = 0.25;
    public static final double DEFAULT_HEADROOM_FLOOR = 0.1;
    public static final double DEFAULT_STALE_HEADROOM_FACTOR = 0.5;
    public static final double DEFAULT_RISK_PENALTY_WEIGHT = 0.6;
    public static final double DEFAULT_MIN_RISK_FACTOR = 0.2;
    public static final double DEFAULT_MIN_WEIGHT_FLOOR = 0.01;

    private final double cpuCoreWeight;
    private final double memoryGbWeight;
    private final double headroomFloor;
    private final double staleHeadroomFactor;
    private final double riskPenaltyWeight;
    private final double minRiskFactor;
    private final double minWeightFloor;

    public HeuristicNodeCapacityScorer() {
        this(DEFAULT_CPU_CORE_WEIGHT, DEFAULT_MEMORY_GB_WEIGHT, DEFAULT_HEADROOM_FLOOR,
                DEFAULT_STALE_HEADROOM_FACTOR, DEFAULT_RISK_PENALTY_WEIGHT, DEFAULT_MIN_RISK_FACTOR,
                DEFAULT_MIN_WEIGHT_FLOOR);
    }

    public HeuristicNodeCapacityScorer(double cpuCoreWeight, double memoryGbWeight, double headroomFloor,
                                       double staleHeadroomFactor, double riskPenaltyWeight,
                                       double minRiskFactor, double minWeightFloor) {
        this.cpuCoreWeight = cpuCoreWeight;
        this.memoryGbWeight = memoryGbWeight;
        this.headroomFloor = headroomFloor;
        this.staleHeadroomFactor = staleHeadroomFactor;
        this.riskPenaltyWeight = riskPenaltyWeight;
        this.minRiskFactor = minRiskFactor;
        this.minWeightFloor = minWeightFloor;
    }

    @Override
    public double scoreCapacity(NodeRecord node) {
        double declaredCapacity = cpuCores(node) * cpuCoreWeight + memoryGb(node) * memoryGbWeight;
        double score = declaredCapacity
                * headroom(node.isCpuStale(), node.getCpuUsage())
                * headroom(node.isMemoryStale(), node.getMemoryUsage())
                * riskFactor(node.getRiskScore());
        return Math.max(minWeightFloor, score);
    }

    /** Never {@code 0} — an unreported core count must not starve a node's share entirely. */
    private static int cpuCores(NodeRecord node) {
        int cores = node.getCapabilities().getCpuCores();
        return cores > 0 ? cores : 1;
    }

    /** Never {@code 0} — same reasoning as {@link #cpuCores}. */
    private static double memoryGb(NodeRecord node) {
        long bytes = node.getCapabilities().getTotalMemoryBytes();
        return bytes > 0 ? bytes / 1_000_000_000.0 : 1.0;
    }

    /** A stale reading is neither "idle" nor "fully loaded" — a neutral factor, not a guess. */
    private double headroom(boolean stale, float usagePercent) {
        if (stale) {
            return staleHeadroomFactor;
        }
        double headroom = 1.0 - (usagePercent / 100.0);
        return Math.max(headroomFloor, Math.min(1.0, headroom));
    }

    /** An at-risk node still receives some work — just proportionally less, never zero. */
    private double riskFactor(double riskScore) {
        double factor = 1.0 - riskPenaltyWeight * riskScore;
        return Math.max(minRiskFactor, Math.min(1.0, factor));
    }
}
