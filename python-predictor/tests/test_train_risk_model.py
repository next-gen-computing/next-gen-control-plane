"""
Tests for train_risk_model.py — a real correctness check that the hand-rolled logistic-regression
gradient descent actually converges on a synthetic, trivially-separable dataset (not just "runs without
crashing"), plus the outcome/snapshot horizon-labeling join logic.
"""

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from features import FEATURE_NAMES  # noqa: E402
from train_risk_model import (  # noqa: E402
    accuracy,
    build_examples,
    main,
    read_jsonl,
    train_logistic_regression,
    train_xgboost,
    xgboost_accuracy,
)


def healthy_history():
    return [{
        "cpuPercent": 10.0, "cpuAvailable": True,
        "memoryPercent": 20.0, "memoryAvailable": True,
        "batteryPercent": 90.0, "batteryAvailable": True,
        "onAcPower": True, "onAcPowerKnown": True,
        "previousRttSeconds": 0.01, "previousRttAvailable": True,
    }]


def risky_history():
    return [{
        "cpuPercent": 90.0, "cpuAvailable": True,
        "memoryPercent": 97.0, "memoryAvailable": True,
        "batteryPercent": 3.0, "batteryAvailable": True,
        "onAcPower": False, "onAcPowerKnown": True,
        "previousRttSeconds": 2.0, "previousRttAvailable": True,
    }]


# ── Stage EE: read_jsonl must not crash the whole read on one bad byte ────

def test_read_jsonl_skips_a_line_with_invalid_utf8_bytes_but_keeps_the_rest(tmp_path):
    path = tmp_path / "outcomes.jsonl"
    good_line_1 = json.dumps({"label": 1}).encode("utf-8")
    bad_line = b"\xff\xfe not valid utf-8"
    good_line_2 = json.dumps({"label": 0}).encode("utf-8")
    path.write_bytes(good_line_1 + b"\n" + bad_line + b"\n" + good_line_2 + b"\n")

    rows = read_jsonl(str(path))

    assert rows == [{"label": 1}, {"label": 0}]


def test_read_jsonl_skips_malformed_json_lines_but_keeps_the_rest(tmp_path):
    path = tmp_path / "outcomes.jsonl"
    path.write_text(json.dumps({"label": 1}) + "\nnot json at all\n" + json.dumps({"label": 0}) + "\n")

    rows = read_jsonl(str(path))

    assert rows == [{"label": 1}, {"label": 0}]


def test_read_jsonl_on_a_missing_path_returns_an_empty_list_not_a_crash():
    assert read_jsonl("/does/not/exist.jsonl") == []


def test_gradient_descent_converges_on_a_trivially_separable_dataset():
    rng = np.random.default_rng(0)
    n_per_class = 100
    healthy_x = rng.normal(loc=[10, 0, 0, 0, 0, 20, 10, 1], scale=0.5, size=(n_per_class, 8))
    risky_x = rng.normal(loc=[3, 1, 1, 1, 1, 97, 90, 1], scale=0.5, size=(n_per_class, 8))
    X = np.vstack([healthy_x, risky_x])
    y = np.concatenate([np.zeros(n_per_class), np.ones(n_per_class)])

    mean = X.mean(axis=0)
    std = X.std(axis=0)
    X_norm = (X - mean) / np.where(std == 0, 1.0, std)

    weights, bias = train_logistic_regression(X_norm, y, learning_rate=0.5, epochs=1000)
    acc = accuracy(X_norm, y, weights, bias)

    assert acc > 0.95, f"gradient descent should nearly-perfectly separate two well-separated clusters, got {acc}"


def test_xgboost_converges_on_a_trivially_separable_dataset():
    """Stage H: the same correctness bar as the logistic-regression test above, now for the raw-Booster
    xgboost path — a real check that it actually converges, not just that it runs without crashing."""
    rng = np.random.default_rng(0)
    n_per_class = 100
    n_features = len(FEATURE_NAMES)
    healthy_x = rng.normal(loc=0.0, scale=0.5, size=(n_per_class, n_features))
    risky_x = rng.normal(loc=5.0, scale=0.5, size=(n_per_class, n_features))
    X = np.vstack([healthy_x, risky_x])
    y = np.concatenate([np.zeros(n_per_class), np.ones(n_per_class)])

    mean = X.mean(axis=0)
    std = X.std(axis=0)
    X_norm = (X - mean) / np.where(std == 0, 1.0, std)

    booster = train_xgboost(X_norm, y, n_estimators=50, max_depth=3, learning_rate=0.3)
    acc = xgboost_accuracy(X_norm, y, booster)

    assert acc > 0.95, f"xgboost should nearly-perfectly separate two well-separated clusters, got {acc}"


def test_build_examples_labels_outcomes_as_positive():
    outcomes = [{"nodeId": "n1", "transitionAtMillis": 10_000, "recentHistory": risky_history()}]
    examples = build_examples(outcomes, [], [], horizon_seconds=60.0)

    assert len(examples) == 1
    assert examples[0][1] == 1


def test_build_examples_labels_a_snapshot_within_the_horizon_as_positive():
    outcomes = [{"nodeId": "n1", "transitionAtMillis": 10_000, "recentHistory": risky_history()}]
    snapshots = [{"nodeId": "n1", "snapshotAtMillis": 5_000, "recentHistory": risky_history()}]  # 5s before death

    examples = build_examples(outcomes, snapshots, [], horizon_seconds=60.0)

    labels = [label for _, label in examples]
    assert 1 in labels and labels.count(1) == 2  # the outcome itself, plus the snapshot within horizon


def test_build_examples_labels_a_snapshot_outside_the_horizon_as_negative():
    outcomes = [{"nodeId": "n1", "transitionAtMillis": 100_000, "recentHistory": risky_history()}]
    snapshots = [{"nodeId": "n1", "snapshotAtMillis": 5_000, "recentHistory": healthy_history()}]  # 95s before death

    examples = build_examples(outcomes, snapshots, [], horizon_seconds=60.0)

    snapshot_labels = [label for hist, label in examples if hist == healthy_history()]
    assert snapshot_labels == [0]


def test_build_examples_labels_a_snapshot_from_an_unrelated_node_as_negative():
    outcomes = [{"nodeId": "n1", "transitionAtMillis": 10_000, "recentHistory": risky_history()}]
    snapshots = [{"nodeId": "n2", "snapshotAtMillis": 9_000, "recentHistory": healthy_history()}]

    examples = build_examples(outcomes, snapshots, [], horizon_seconds=60.0)

    snapshot_labels = [label for hist, label in examples if hist == healthy_history()]
    assert snapshot_labels == [0], "a different node's death must never label this node's snapshot at-risk"


def test_build_examples_uses_the_explicit_label_field_for_external_rows():
    external = [
        {"nodeId": "ext1", "recentHistory": risky_history(), "label": 1},
        {"nodeId": "ext2", "recentHistory": healthy_history(), "label": 0},
    ]
    examples = build_examples([], [], external, horizon_seconds=60.0)

    assert sorted(label for _, label in examples) == [0, 1]


def test_main_refuses_to_write_a_model_with_too_few_examples(tmp_path):
    outcomes_path = tmp_path / "outcomes.jsonl"
    outcomes_path.write_text(json.dumps({
        "nodeId": "n1", "transitionAtMillis": 1, "recentHistory": risky_history(),
    }) + "\n")
    output_path = tmp_path / "model.json"

    exit_code = main(["--outcomes", str(outcomes_path), "--output", str(output_path)])

    assert exit_code != 0
    assert not output_path.exists()


def test_main_refuses_to_write_a_model_with_only_one_class(tmp_path):
    outcomes_path = tmp_path / "outcomes.jsonl"
    with open(outcomes_path, "w", encoding="utf-8") as f:
        for i in range(25):
            f.write(json.dumps({
                "nodeId": f"n{i}", "transitionAtMillis": i, "recentHistory": risky_history(),
            }) + "\n")
    output_path = tmp_path / "model.json"

    exit_code = main(["--outcomes", str(outcomes_path), "--output", str(output_path)])

    assert exit_code != 0, "risk_outcomes.jsonl alone is 100% positive-class data — must refuse, not fabricate a model"
    assert not output_path.exists()


def test_main_writes_a_real_model_given_both_classes(tmp_path):
    outcomes_path = tmp_path / "outcomes.jsonl"
    snapshots_path = tmp_path / "snapshots.jsonl"
    with open(outcomes_path, "w", encoding="utf-8") as f:
        for i in range(15):
            f.write(json.dumps({
                "nodeId": f"risky{i}", "transitionAtMillis": 100_000,
                "recentHistory": risky_history(),
            }) + "\n")
    with open(snapshots_path, "w", encoding="utf-8") as f:
        for i in range(15):
            f.write(json.dumps({
                "nodeId": f"healthy{i}", "snapshotAtMillis": 1_000,
                "recentHistory": healthy_history(),
            }) + "\n")
    output_path = tmp_path / "model.json"

    exit_code = main(["--outcomes", str(outcomes_path), "--snapshots", str(snapshots_path),
                      "--output", str(output_path), "--model-type", "logistic_regression"])

    assert exit_code == 0
    assert output_path.exists()
    model = json.loads(output_path.read_text())
    assert model["modelType"] == "logistic_regression"
    assert model["trainingExampleCount"] == 30
    assert len(model["weights"]) == len(FEATURE_NAMES)
    assert model["validationAccuracy"] > 0.5


def test_main_writes_a_real_xgboost_model_given_both_classes(tmp_path):
    """Stage H: --model-type xgboost is the default; this is the same fixture as the
    logistic-regression case above, proving the sidecar booster file is actually written."""
    outcomes_path = tmp_path / "outcomes.jsonl"
    snapshots_path = tmp_path / "snapshots.jsonl"
    with open(outcomes_path, "w", encoding="utf-8") as f:
        for i in range(15):
            f.write(json.dumps({
                "nodeId": f"risky{i}", "transitionAtMillis": 100_000,
                "recentHistory": risky_history(),
            }) + "\n")
    with open(snapshots_path, "w", encoding="utf-8") as f:
        for i in range(15):
            f.write(json.dumps({
                "nodeId": f"healthy{i}", "snapshotAtMillis": 1_000,
                "recentHistory": healthy_history(),
            }) + "\n")
    output_path = tmp_path / "model.json"

    exit_code = main(["--outcomes", str(outcomes_path), "--snapshots", str(snapshots_path),
                      "--output", str(output_path)])  # --model-type defaults to xgboost

    assert exit_code == 0
    model = json.loads(output_path.read_text())
    assert model["modelType"] == "xgboost"
    assert model["trainingExampleCount"] == 30
    assert "weights" not in model
    assert (tmp_path / model["boosterPath"]).exists()
    assert model["validationAccuracy"] > 0.5
