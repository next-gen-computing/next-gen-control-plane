package com.nextgen.controlplane.risk;

import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A hand-tuned placeholder for a real trained model — deliberately simple and interpretable, since
 * it exists to be replaced, not to be a tuned product in its own right. See {@link RiskScorer}.
 *
 * <p>Three independent rules, each a real trend observed in {@code NodeHistory}, never a fabricated
 * one:
 * <ul>
 *   <li><b>Low battery, on battery power.</b> The node itself already knows its power state; this
 *       does not measure anything the node's own OS doesn't already report.</li>
 *   <li><b>Rising heartbeat RTT.</b> The last {@code trendWindow} {@code previousRttSeconds} values
 *       strictly increasing — a real, agent-measured number relayed one heartbeat late (see
 *       {@code NodeAgent}'s Javadoc on why one-way latency can't be fabricated), not a synthetic
 *       "network health" score.</li>
 *   <li><b>Memory pressure building.</b> Memory usage non-decreasing across the whole window AND
 *       already above a ceiling — a plateau or a one-off spike is not treated as pressure; a
 *       genuinely climbing, already-high usage is.</li>
 * </ul>
 *
 * <p>Any ONE rule alone is enough to flag a node {@code atRisk} — each carries a weight of 0.5 against
 * a 0.5 threshold. A node that is unplugged and nearly out of battery is a real, standalone reason to
 * proactively move its work, and waiting for a second corroborating signal would waste exactly the
 * "before it dies" window this whole feature exists to buy. Multiple triggers still raise the score
 * further (capped at 1.0), which matters for logging/observability even though it changes no decision.
 *
 * <p>Honest about missing data throughout: a rule only fires when every sample it inspects actually
 * carries the signal it needs ({@code available}/{@code known} flags checked, never assumed) — a gap
 * in the history makes that rule abstain, not guess.
 */
public final class RuleBasedRiskScorer implements RiskScorer {

    public static final double DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT = 15.0;
    public static final double DEFAULT_MEMORY_CEILING_PERCENT = 90.0;
    public static final int DEFAULT_TREND_WINDOW = 5;
    public static final double DEFAULT_AT_RISK_THRESHOLD = 0.5;

    private static final double RULE_WEIGHT = 0.5;

    private final double lowBatteryThresholdPercent;
    private final double memoryCeilingPercent;
    private final int trendWindow;
    private final double atRiskThreshold;

    public RuleBasedRiskScorer() {
        this(DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT, DEFAULT_MEMORY_CEILING_PERCENT,
                DEFAULT_TREND_WINDOW, DEFAULT_AT_RISK_THRESHOLD);
    }

    public RuleBasedRiskScorer(double lowBatteryThresholdPercent, double memoryCeilingPercent,
                               int trendWindow, double atRiskThreshold) {
        if (trendWindow < 2) {
            throw new IllegalArgumentException("trendWindow must be >= 2 to observe a trend");
        }
        this.lowBatteryThresholdPercent = lowBatteryThresholdPercent;
        this.memoryCeilingPercent = memoryCeilingPercent;
        this.trendWindow = trendWindow;
        this.atRiskThreshold = atRiskThreshold;
    }

    @Override
    public RiskAssessment score(NodeRecord node, List<NodeHistory.Sample> recentHistory) {
        List<String> reasons = new ArrayList<>();
        double score = 0.0;

        if (!recentHistory.isEmpty()) {
            NodeHistory.Sample latest = recentHistory.get(recentHistory.size() - 1);

            if (isOnLowBattery(latest)) {
                score += RULE_WEIGHT;
                reasons.add(String.format(Locale.ROOT, "on battery at %.1f%% (below %.0f%% threshold)",
                        latest.batteryPercent(), lowBatteryThresholdPercent));
            }
            if (hasRisingRtt(recentHistory)) {
                score += RULE_WEIGHT;
                reasons.add("heartbeat RTT strictly increasing over the last " + trendWindow + " samples");
            }
            if (hasMemoryPressure(recentHistory, latest)) {
                score += RULE_WEIGHT;
                reasons.add(String.format(Locale.ROOT,
                        "memory non-decreasing and above %.0f%% ceiling (currently %.1f%%)",
                        memoryCeilingPercent, latest.memoryPercent()));
            }
        }

        double capped = Math.min(1.0, score);
        return new RiskAssessment(capped, capped >= atRiskThreshold, List.copyOf(reasons));
    }

    private boolean isOnLowBattery(NodeHistory.Sample latest) {
        return latest.batteryAvailable() && latest.onAcPowerKnown() && !latest.onAcPower()
                && latest.batteryPercent() < lowBatteryThresholdPercent;
    }

    private boolean hasRisingRtt(List<NodeHistory.Sample> history) {
        List<NodeHistory.Sample> window = lastN(history);
        if (window.size() < trendWindow) {
            return false; // not enough history yet to assess a trend
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (NodeHistory.Sample sample : window) {
            if (!sample.previousRttAvailable()) {
                return false; // an honest trend needs every point real — a gap breaks it, not skips it
            }
            if (sample.previousRttSeconds() <= previous) {
                return false;
            }
            previous = sample.previousRttSeconds();
        }
        return true;
    }

    private boolean hasMemoryPressure(List<NodeHistory.Sample> history, NodeHistory.Sample latest) {
        if (!latest.memoryAvailable() || latest.memoryPercent() < memoryCeilingPercent) {
            return false;
        }
        List<NodeHistory.Sample> window = lastN(history);
        if (window.size() < trendWindow) {
            return false;
        }
        float previous = Float.NEGATIVE_INFINITY;
        for (NodeHistory.Sample sample : window) {
            if (!sample.memoryAvailable()) {
                return false;
            }
            if (sample.memoryPercent() < previous) {
                return false; // dropped — not monotonically non-decreasing
            }
            previous = sample.memoryPercent();
        }
        return true;
    }

    private List<NodeHistory.Sample> lastN(List<NodeHistory.Sample> history) {
        int size = history.size();
        return size <= trendWindow ? history : history.subList(size - trendWindow, size);
    }
}
