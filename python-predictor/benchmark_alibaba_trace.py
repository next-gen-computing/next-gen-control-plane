#!/usr/bin/env python3
"""Reproducible, machine-held-out risk-classification benchmark for Alibaba PAI.

The risk label is the trace importer’s workload outcome (Failed/Interrupted versus
Terminated), not a control-plane node failure. Memory in this enriched trace is an
explicit estimate from concurrent workers' lifetime-average memory divided by the
machine capacity, not an instantaneous sensor reading. Keep those limitations with
every reported result.

Machines, rather than individual examples, are held out so samples from a machine
cannot leak between training and validation. This script intentionally does not
benchmark the LSTM: the trace's input intervals are highly irregular (534-second
median among eligible windows), while the current model has no time-delta feature.
Use regularly sampled live risk snapshots for LSTM training and evaluation.

Example:
    python benchmark_alibaba_trace.py \
        --input alibaba_risk_examples_large_enriched.jsonl \
        --output benchmark-results/alibaba-pai-risk.json
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import platform
import sys
from pathlib import Path

import numpy as np
import xgboost as xgb

from features import extract_features
from train_risk_model import train_xgboost

LOG = logging.getLogger("benchmark_alibaba_trace")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

DEFAULT_INPUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "alibaba_risk_examples_large_enriched.jsonl")


def load_trace(path: str) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Load risk features, labels, and machine groups from the JSONL trace."""
    feature_rows: list[np.ndarray] = []
    labels: list[int] = []
    node_ids: list[str] = []

    with open(path, "r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON on line {line_number}: {error}") from error

            label = row.get("label")
            node_id = row.get("nodeId")
            history = row.get("recentHistory")
            if label not in (0, 1) or not isinstance(node_id, str) or not isinstance(history, list):
                raise ValueError(f"line {line_number} lacks a nodeId, binary label, or recentHistory")

            feature_rows.append(extract_features(history))
            labels.append(int(label))
            node_ids.append(node_id)

    if not feature_rows:
        raise ValueError("input contained no usable examples")
    return (np.asarray(feature_rows, dtype=np.float64), np.asarray(labels, dtype=np.int32),
            np.asarray(node_ids, dtype=object))


def split_nodes(node_ids: np.ndarray, labels: np.ndarray, test_fraction: float,
                seed: int) -> tuple[set[str], set[str]]:
    """Make a deterministic group split with both labels represented on each side."""
    unique_nodes = np.asarray(sorted(set(node_ids.tolist())), dtype=object)
    if len(unique_nodes) < 2:
        raise ValueError("at least two distinct machines are required for a group holdout")
    test_count = max(1, min(len(unique_nodes) - 1, round(len(unique_nodes) * test_fraction)))

    for attempt in range(100):
        shuffled = np.random.default_rng(seed + attempt).permutation(unique_nodes)
        test_nodes = set(shuffled[:test_count].tolist())
        train_nodes = set(unique_nodes.tolist()) - test_nodes
        train_labels = set(labels[np.isin(node_ids, list(train_nodes))].tolist())
        test_labels = set(labels[np.isin(node_ids, list(test_nodes))].tolist())
        if train_labels == {0, 1} and test_labels == {0, 1}:
            return train_nodes, test_nodes
    raise ValueError("could not create a machine-held-out split containing both labels")


def roc_auc(labels: np.ndarray, scores: np.ndarray) -> float:
    positives = int(labels.sum())
    negatives = len(labels) - positives
    if positives == 0 or negatives == 0:
        raise ValueError("ROC-AUC requires both labels in the validation set")

    order = np.argsort(scores, kind="mergesort")
    sorted_scores = scores[order]
    sorted_labels = labels[order]
    positive_rank_sum = 0.0
    start = 0
    while start < len(order):
        end = start + 1
        while end < len(order) and sorted_scores[end] == sorted_scores[start]:
            end += 1
        average_rank = ((start + 1) + end) / 2.0
        positive_rank_sum += average_rank * int(sorted_labels[start:end].sum())
        start = end
    return (positive_rank_sum - positives * (positives + 1) / 2) / (positives * negatives)


def average_precision(labels: np.ndarray, scores: np.ndarray) -> float:
    positives = int(labels.sum())
    if positives == 0:
        raise ValueError("average precision requires positive examples")

    order = np.argsort(-scores, kind="mergesort")
    sorted_scores = scores[order]
    sorted_labels = labels[order]
    true_positives = 0
    examples_seen = 0
    result = 0.0
    start = 0
    while start < len(order):
        end = start + 1
        while end < len(order) and sorted_scores[end] == sorted_scores[start]:
            end += 1
        group_positives = int(sorted_labels[start:end].sum())
        true_positives += group_positives
        examples_seen += end - start
        result += (group_positives / positives) * (true_positives / examples_seen)
        start = end
    return result


def classification_metrics(labels: np.ndarray, scores: np.ndarray, bins: int = 10) -> dict:
    predictions = scores >= 0.5
    positives = labels == 1
    true_positive = int(np.count_nonzero(predictions & positives))
    false_positive = int(np.count_nonzero(predictions & ~positives))
    false_negative = int(np.count_nonzero(~predictions & positives))
    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)

    bin_ids = np.minimum((scores * bins).astype(int), bins - 1)
    calibration_error = 0.0
    for bin_id in range(bins):
        mask = bin_ids == bin_id
        if np.any(mask):
            calibration_error += float(np.mean(mask)) * abs(float(np.mean(scores[mask]))
                                                         - float(np.mean(labels[mask])))

    return {
        "roc_auc": roc_auc(labels, scores),
        "average_precision": average_precision(labels, scores),
        "accuracy_at_0_5": float(np.mean(predictions == positives)),
        "precision_at_0_5": precision,
        "recall_at_0_5": recall,
        "f1_at_0_5": 2 * precision * recall / max(1e-12, precision + recall),
        "brier_score": float(np.mean((scores - labels) ** 2)),
        "expected_calibration_error_10_bins": calibration_error,
        "confusion_at_0_5": {"true_positive": true_positive, "false_positive": false_positive,
                             "false_negative": false_negative,
                             "true_negative": int(np.count_nonzero(~predictions & ~positives))},
    }


def group_bootstrap_intervals(labels: np.ndarray, scores: np.ndarray, node_ids: np.ndarray,
                             resamples: int, seed: int) -> dict:
    """Estimate 95% confidence intervals by resampling machines, not correlated rows."""
    unique_nodes = np.asarray(sorted(set(node_ids.tolist())), dtype=object)
    indexes_by_node = {node: np.flatnonzero(node_ids == node) for node in unique_nodes}
    rng = np.random.default_rng(seed)
    auc_samples = []
    ap_samples = []

    for _ in range(resamples):
        selected_nodes = rng.choice(unique_nodes, size=len(unique_nodes), replace=True)
        indexes = np.concatenate([indexes_by_node[node] for node in selected_nodes])
        sample_labels = labels[indexes]
        if set(sample_labels.tolist()) != {0, 1}:
            continue
        sample_scores = scores[indexes]
        auc_samples.append(roc_auc(sample_labels, sample_scores))
        ap_samples.append(average_precision(sample_labels, sample_scores))

    if len(auc_samples) < max(10, resamples // 2):
        raise ValueError("too many bootstrap samples contained only one class")

    def interval(values: list[float]) -> dict:
        low, high = np.percentile(values, [2.5, 97.5])
        return {"lower_95": float(low), "upper_95": float(high)}

    return {"resamples_requested": resamples, "resamples_used": len(auc_samples),
            "roc_auc": interval(auc_samples), "average_precision": interval(ap_samples)}


def class_baselines(labels: np.ndarray) -> dict:
    positive_rate = float(np.mean(labels))
    return {
        "positive_prevalence": positive_rate,
        "majority_class_accuracy": max(positive_rate, 1.0 - positive_rate),
        "chance_roc_auc": 0.5,
        "random_ranking_average_precision": positive_rate,
        "constant_prevalence_brier_score": positive_rate * (1.0 - positive_rate),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", default=DEFAULT_INPUT)
    parser.add_argument("--output", default="benchmark-results/alibaba-pai.json")
    parser.add_argument("--test-fraction", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--xgb-n-estimators", type=int, default=100)
    parser.add_argument("--xgb-max-depth", type=int, default=4)
    parser.add_argument("--xgb-learning-rate", type=float, default=0.1)
    parser.add_argument("--bootstrap-resamples", type=int, default=200)
    args = parser.parse_args(argv)

    if not 0.0 < args.test_fraction < 1.0:
        parser.error("--test-fraction must be between 0 and 1")
    if args.bootstrap_resamples < 10:
        parser.error("--bootstrap-resamples must be at least 10")
    LOG.info("Loading trace examples from %s", args.input)
    X, labels, node_ids = load_trace(args.input)
    train_nodes, test_nodes = split_nodes(node_ids, labels, args.test_fraction, args.seed)
    train_mask = np.isin(node_ids, list(train_nodes))
    test_mask = ~train_mask
    if set(labels[train_mask].tolist()) != {0, 1} or set(labels[test_mask].tolist()) != {0, 1}:
        raise ValueError("machine split must contain both labels in training and validation")

    mean = X[train_mask].mean(axis=0)
    std = X[train_mask].std(axis=0)
    std_safe = np.where(std == 0, 1.0, std)
    X_normalized = (X - mean) / std_safe
    booster = train_xgboost(X_normalized[train_mask], labels[train_mask],
                            n_estimators=args.xgb_n_estimators, max_depth=args.xgb_max_depth,
                            learning_rate=args.xgb_learning_rate, seed=args.seed)
    risk_scores = booster.predict(xgb.DMatrix(X_normalized[test_mask]))
    validation_labels = labels[test_mask]
    validation_nodes = node_ids[test_mask]
    risk_report = {
        "train_examples": int(np.count_nonzero(train_mask)),
        "validation_examples": int(np.count_nonzero(test_mask)),
        "train_machines": len(train_nodes),
        "validation_machines": len(test_nodes),
        "majority_and_chance_baselines": class_baselines(validation_labels),
        "validation_metrics": classification_metrics(validation_labels, risk_scores),
        "machine_bootstrap_95_percent_intervals": group_bootstrap_intervals(
            validation_labels, risk_scores, validation_nodes, args.bootstrap_resamples, args.seed + 1),
    }

    report = {
        "source": {"path": os.path.basename(args.input), "rows": len(labels),
                   "distinct_machines": len(set(node_ids.tolist())),
                   "label_semantics": "Alibaba PAI workload status: Failed/Interrupted=1, Terminated=0",
                   "memory_semantics": "estimated concurrent worker average / machine capacity; not instantaneous telemetry",
                   "forecast_note": "LSTM metrics omitted: irregular trace intervals are not represented in the current model input"},
        "protocol": {"machine_holdout_fraction": args.test_fraction, "seed": args.seed,
                     "risk_xgboost": {"estimators": args.xgb_n_estimators,
                                      "max_depth": args.xgb_max_depth,
                                      "learning_rate": args.xgb_learning_rate,
                                      "seed": args.seed},
                     "machine_bootstrap_resamples": args.bootstrap_resamples},
        "environment": {"python": platform.python_version(), "numpy": np.__version__,
                        "xgboost": xgb.__version__,
                        "platform": platform.platform()},
        "risk_classifier": risk_report,
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(json.dumps(report, indent=2))
    LOG.info("Wrote benchmark report to %s", output_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())