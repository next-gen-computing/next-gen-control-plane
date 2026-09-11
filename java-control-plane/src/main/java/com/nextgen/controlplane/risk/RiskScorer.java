package com.nextgen.controlplane.risk;

import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;

import java.util.List;

/**
 * Predicts whether a node is likely to fail soon, from its recent trend history — the seam a future
 * trained model fills with zero caller changes.
 *
 * <p>{@link RuleBasedRiskScorer} implements this now, with hand-tuned thresholds, as an explicit
 * placeholder: no training data exists yet. Once real outcome data has been collected (see the
 * project plan's Stage E), an {@code MLRiskScorer implements RiskScorer} can be swapped in for
 * {@code RiskMonitor} to use instead — nothing else in the system needs to change.
 */
public interface RiskScorer {

    /**
     * @param node          the node's current registry record (status, identity — NOT history).
     * @param recentHistory this node's recent {@code NodeHistory} samples, oldest first. May be empty
     *                      for a node that has never heartbeated with the new signal fields, or has
     *                      no history yet.
     */
    RiskAssessment score(NodeRecord node, List<NodeHistory.Sample> recentHistory);

    /**
     * @param riskScore a value in {@code [0, 1]}; higher means more likely to fail soon.
     * @param atRisk    whether this assessment crosses the scorer's own threshold for action.
     * @param reasons   human-readable explanations, for logging and (Stage E) training-data capture —
     *                  never empty when {@code atRisk} is true.
     */
    record RiskAssessment(double riskScore, boolean atRisk, List<String> reasons) {
    }
}
