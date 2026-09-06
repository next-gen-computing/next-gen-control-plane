package com.nextgen.controlplane.capacity;

import com.nextgen.controlplane.NodeRecord;

/**
 * A relative capacity weight for splitting a job's work across nodes — the seam a future learned
 * throughput model fills later with zero caller changes, exactly like {@code RiskScorer} does for risk
 * assessment. {@link HeuristicNodeCapacityScorer} is the only implementation today: no
 * "sub-task took X seconds on a node with Y properties" outcome data exists yet to train a real one
 * (see {@code com.nextgen.controlplane.training.JobOutcomeLogger}, which starts collecting exactly
 * that dataset).
 */
public interface NodeCapacityScorer {

    /**
     * Strictly positive; higher means this node should receive a proportionally larger share of a
     * split job. Callers combine scores from multiple nodes as relative weights, never as an absolute
     * quantity, so the scale only needs to be consistent across nodes at one point in time.
     */
    double scoreCapacity(NodeRecord node);
}
