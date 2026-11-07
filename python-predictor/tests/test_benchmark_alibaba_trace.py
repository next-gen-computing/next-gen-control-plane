import os
import sys

import numpy as np
import xgboost as xgb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from benchmark_alibaba_trace import (  # noqa: E402
    class_baselines,
    classification_metrics,
    group_bootstrap_intervals,
    roc_auc,
    split_nodes,
)
from train_risk_model import train_xgboost  # noqa: E402


def test_auc_and_average_precision_are_perfect_for_separated_scores():
    labels = np.array([0, 1, 0, 1], dtype=np.int32)
    scores = np.array([0.1, 0.9, 0.2, 0.8], dtype=np.float64)

    metrics = classification_metrics(labels, scores)

    assert metrics["roc_auc"] == 1.0
    assert metrics["average_precision"] == 1.0
    assert metrics["accuracy_at_0_5"] == 1.0
    assert metrics["brier_score"] == np.mean((scores - labels) ** 2)


def test_roc_auc_treats_tied_scores_as_half_credit():
    assert roc_auc(np.array([0, 1]), np.array([0.5, 0.5])) == 0.5


def test_class_baselines_report_prevalence_and_no_skill_values():
    baseline = class_baselines(np.array([0, 0, 1, 1], dtype=np.int32))

    assert baseline["positive_prevalence"] == 0.5
    assert baseline["majority_class_accuracy"] == 0.5
    assert baseline["chance_roc_auc"] == 0.5
    assert baseline["random_ranking_average_precision"] == 0.5
    assert baseline["constant_prevalence_brier_score"] == 0.25


def test_group_bootstrap_intervals_are_repeatable_and_machine_clustered():
    node_ids = np.repeat(np.array([f"machine-{i}" for i in range(20)], dtype=object), 2)
    labels = np.tile(np.array([0, 1], dtype=np.int32), 20)
    scores = np.where(labels == 1, 0.9, 0.1)

    first = group_bootstrap_intervals(labels, scores, node_ids, resamples=30, seed=17)
    second = group_bootstrap_intervals(labels, scores, node_ids, resamples=30, seed=17)

    assert first == second
    assert first["resamples_used"] == 30
    assert first["roc_auc"] == {"lower_95": 1.0, "upper_95": 1.0}


def test_node_split_is_deterministic_disjoint_and_keeps_both_labels():
    node_ids = np.repeat(np.array([f"machine-{i}" for i in range(20)], dtype=object), 2)
    labels = np.tile(np.array([0, 1], dtype=np.int32), 20)

    train_nodes, test_nodes = split_nodes(node_ids, labels, test_fraction=0.2, seed=42)
    repeated_train, repeated_test = split_nodes(node_ids, labels, test_fraction=0.2, seed=42)

    assert train_nodes.isdisjoint(test_nodes)
    assert train_nodes | test_nodes == set(node_ids)
    assert train_nodes == repeated_train
    assert test_nodes == repeated_test
    assert set(labels[np.isin(node_ids, list(train_nodes))]) == {0, 1}
    assert set(labels[np.isin(node_ids, list(test_nodes))]) == {0, 1}


def test_xgboost_training_is_reproducible_for_a_fixed_seed():
    rng = np.random.default_rng(7)
    features = rng.normal(size=(80, 4))
    labels = (features[:, 0] + features[:, 1] > 0).astype(np.float64)

    first = train_xgboost(features, labels, n_estimators=12, max_depth=2, seed=23)
    second = train_xgboost(features, labels, n_estimators=12, max_depth=2, seed=23)

    matrix = xgb.DMatrix(features)
    assert np.array_equal(first.predict(matrix), second.predict(matrix))