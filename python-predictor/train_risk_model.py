#!/usr/bin/env python3
"""
Trains a real logistic-regression risk model from real collected data and writes model/risk_model.json
for predictor_service.py (via model_store.py) to hot-reload.

Run manually by the operator once real data has accumulated — retraining is never automatic (see
ControlPlaneServer's ML_RISK_SCORER_ENABLED wiring, which stays off by default until this has been run
at least once):

    python train_risk_model.py \\
        --outcomes  /path/to/risk_outcomes.jsonl \\
        --snapshots /path/to/risk_snapshots.jsonl \\
        --output    model/risk_model.json

Inputs:
  --outcomes  risk_outcomes.jsonl (RiskOutcomeLogger, Stage E) — every row is a real
              ALIVE -> SUSPECTED_DEAD transition. Used directly as positive (label=1) examples.
  --snapshots risk_snapshots.jsonl (RiskSnapshotLogger, Stage G, opt-in — off by default) — every row
              is a periodic (node properties, risk assessment) snapshot. Labeled 1 if that same node's
              next real death (from --outcomes) happened within --horizon-seconds afterward, else 0.
              This is what supplies negative examples; without it (or --external), --outcomes alone is
              100% positive-class data and this script refuses to train on it (see below).
  --external  zero or more additional JSONL files already in this project's schema — e.g. produced by
              import_external_trace.py from a locally-supplied external dataset. Blended in as extra
              examples under the same labeling rules.

No scikit-learn is available in this environment or pinned in requirements.txt — logistic regression is
implemented here from scratch via batch gradient descent on standardized features (numpy only). This is
a genuinely trained model, not a placeholder: tests/test_train_risk_model.py verifies the gradient
descent actually converges to near-perfect accuracy on a synthetic, trivially-separable dataset — a real
correctness check on the implementation, not just "runs without crashing."

Stage H adds a second, default model type: real gradient-boosted trees via xgboost's raw Booster API
(--model-type xgboost, the default; --model-type logistic_regression keeps the original path). Both share
every step upstream of fitting — build_examples, MIN_TRAINING_EXAMPLES, the all-one-label refusal, the
80/20 split, and standardization — so this is one data pipeline with two interchangeable fitting
strategies, not two pipelines.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from collections import defaultdict

import numpy as np
import xgboost as xgb

from features import FEATURE_NAMES, extract_features

LOG = logging.getLogger("train_risk_model")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")

DEFAULT_OUTPUT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model", "risk_model.json")

# The xgboost booster's own native serialization lives alongside risk_model.json under this fixed name —
# referenced from the metadata file's "boosterPath" rather than duplicating weights into JSON by hand.
XGBOOST_BOOSTER_FILENAME = "xgboost_booster.json"

# A floor that only guards against training on a handful of accidental rows — a pipeline-correctness
# check, not a claim that this many examples makes the model trustworthy. Real confidence needs far
# more real data than this; see the project plan's Stage G notes.
MIN_TRAINING_EXAMPLES = 20


def read_jsonl(path: str) -> list[dict]:
    """Stage EE: reads in BINARY mode and decodes each line individually, deliberately not text mode
    with encoding="utf-8" — a text-mode file iterator decodes greedily as part of reading each line,
    so a single invalid UTF-8 byte ANYWHERE in the file previously raised UnicodeDecodeError before
    the per-line try/except below ever got a chance to run, aborting the whole read instead of skipping
    just the one bad line the same way a malformed-JSON line already was."""
    if not path or not os.path.exists(path):
        return []
    rows = []
    with open(path, "rb") as f:
        for line_num, raw_line in enumerate(f, start=1):
            try:
                line = raw_line.decode("utf-8").strip()
            except UnicodeDecodeError as e:
                LOG.warning("Skipping line %d in %s with invalid UTF-8: %s", line_num, path, e)
                continue
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                LOG.warning("Skipping malformed line %d in %s: %s", line_num, path, e)
    return rows


def build_examples(outcomes: list[dict], snapshots: list[dict], external: list[dict],
                    horizon_seconds: float) -> list[tuple[list[dict], int]]:
    """
    Returns a list of (recent_history, label) pairs.

    Every outcome row is a real death -> label 1, using the history it already carries.
    Every snapshot row is labeled 1 if the SAME node has a death (from `outcomes`) within
    `horizon_seconds` after the snapshot's own timestamp, else 0 — a standard predictive-maintenance
    labeling approach.
    External rows are expected to already carry an explicit `label` field (0 or 1) — see
    import_external_trace.py, which is responsible for producing that from whatever the source dataset's
    own failure/eviction signal is.
    """
    examples: list[tuple[list[dict], int]] = []

    deaths_by_node: dict[str, list[int]] = defaultdict(list)
    for row in outcomes:
        node_id = row.get("nodeId")
        transition_at = row.get("transitionAtMillis")
        history = row.get("recentHistory", [])
        if node_id is None or transition_at is None:
            continue
        deaths_by_node[node_id].append(transition_at)
        examples.append((history, 1))

    horizon_ms = horizon_seconds * 1000.0
    for row in snapshots:
        node_id = row.get("nodeId")
        snapshot_at = row.get("snapshotAtMillis")
        history = row.get("recentHistory", [])
        if node_id is None or snapshot_at is None:
            continue
        died_within_horizon = any(
            snapshot_at <= death_at <= snapshot_at + horizon_ms
            for death_at in deaths_by_node.get(node_id, [])
        )
        examples.append((history, 1 if died_within_horizon else 0))

    for row in external:
        history = row.get("recentHistory", [])
        label = row.get("label")
        if label not in (0, 1):
            continue
        examples.append((history, int(label)))

    return examples


def train_logistic_regression(X: np.ndarray, y: np.ndarray, learning_rate: float = 0.1,
                              epochs: int = 2000, l2: float = 1e-3) -> tuple[np.ndarray, float]:
    """Batch gradient descent on standardized features, with a small L2 penalty against overfitting."""
    n, d = X.shape
    weights = np.zeros(d)
    bias = 0.0
    for _ in range(epochs):
        z = X @ weights + bias
        p = 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))
        grad_w = (X.T @ (p - y)) / n + l2 * weights
        grad_b = float(np.mean(p - y))
        weights -= learning_rate * grad_w
        bias -= learning_rate * grad_b
    return weights, bias


def accuracy(X: np.ndarray, y: np.ndarray, weights: np.ndarray, bias: float) -> float:
    z = X @ weights + bias
    p = 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))
    predicted = (p >= 0.5).astype(np.float64)
    return float(np.mean(predicted == y))


def train_xgboost(X: np.ndarray, y: np.ndarray, n_estimators: int = 100, max_depth: int = 4,
                  learning_rate: float = 0.1) -> xgb.Booster:
    """Real gradient-boosted trees via xgboost's raw Booster API — deliberately not the sklearn wrapper,
    since this project has no scikit-learn dependency and the raw API avoids adding one. Standardized
    features aren't strictly necessary for trees, but reusing the same standardized X the
    logistic-regression path already computes keeps one pipeline with two fitting strategies rather than
    forking it. Kept shallow/small by default (max_depth=4) since MIN_TRAINING_EXAMPLES means early
    models will train on modest data."""
    dtrain = xgb.DMatrix(X, label=y)
    params = {
        "objective": "binary:logistic",
        "max_depth": max_depth,
        "eta": learning_rate,
        "eval_metric": "logloss",
    }
    return xgb.train(params, dtrain, num_boost_round=n_estimators)


def xgboost_accuracy(X: np.ndarray, y: np.ndarray, booster: xgb.Booster) -> float:
    predicted = (booster.predict(xgb.DMatrix(X)) >= 0.5).astype(np.float64)
    return float(np.mean(predicted == y))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--outcomes", default=os.environ.get("RISK_OUTCOMES_PATH", ""))
    parser.add_argument("--snapshots", default=os.environ.get("RISK_SNAPSHOTS_PATH", ""))
    parser.add_argument("--external", nargs="*", default=[])
    parser.add_argument("--output", default=DEFAULT_OUTPUT_PATH)
    parser.add_argument("--horizon-seconds", type=float, default=60.0,
                        help="a snapshot within this many seconds of a real death is labeled at-risk")
    parser.add_argument("--model-type", choices=("logistic_regression", "xgboost"), default="xgboost")
    parser.add_argument("--learning-rate", type=float, default=0.1,
                        help="logistic_regression: gradient-descent step size")
    parser.add_argument("--epochs", type=int, default=2000, help="logistic_regression only")
    parser.add_argument("--xgb-n-estimators", type=int, default=100)
    parser.add_argument("--xgb-max-depth", type=int, default=4)
    parser.add_argument("--xgb-learning-rate", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    outcomes = read_jsonl(args.outcomes)
    snapshots = read_jsonl(args.snapshots)
    external_rows: list[dict] = []
    for path in args.external:
        external_rows.extend(read_jsonl(path))

    LOG.info("Loaded %d outcome row(s), %d snapshot row(s), %d external row(s)",
              len(outcomes), len(snapshots), len(external_rows))

    examples = build_examples(outcomes, snapshots, external_rows, args.horizon_seconds)
    if len(examples) < MIN_TRAINING_EXAMPLES:
        LOG.error("Only %d labeled example(s) available (< %d required) — refusing to write a model. "
                  "Collect more real data, enable RISK_SNAPSHOT_LOGGING_ENABLED, or supply --external data.",
                  len(examples), MIN_TRAINING_EXAMPLES)
        return 1

    labels = np.array([label for _, label in examples], dtype=np.float64)
    if len(set(labels.tolist())) < 2:
        LOG.error("All %d example(s) share the same label — cannot train a classifier on one class. "
                  "This happens when only --outcomes is supplied: every row there is a real death "
                  "(label=1) with nothing to contrast against. Enable RISK_SNAPSHOT_LOGGING_ENABLED to "
                  "collect negative examples, or supply --external data with both outcomes.",
                  len(examples))
        return 1

    X_raw = np.array([extract_features(history) for history, _ in examples])

    rng = np.random.default_rng(args.seed)
    order = rng.permutation(len(examples))
    split = max(1, int(len(examples) * 0.8))
    train_idx, val_idx = order[:split], order[split:]
    if len(val_idx) == 0:
        val_idx = train_idx  # too little data for a real holdout — report training accuracy honestly as both

    mean = X_raw[train_idx].mean(axis=0)
    std = X_raw[train_idx].std(axis=0)
    std_safe = np.where(std == 0, 1.0, std)
    X_norm = (X_raw - mean) / std_safe

    output_dir = os.path.dirname(os.path.abspath(args.output))
    os.makedirs(output_dir, exist_ok=True)

    if args.model_type == "xgboost":
        booster = train_xgboost(
            X_norm[train_idx], labels[train_idx],
            n_estimators=args.xgb_n_estimators, max_depth=args.xgb_max_depth,
            learning_rate=args.xgb_learning_rate)
        train_acc = xgboost_accuracy(X_norm[train_idx], labels[train_idx], booster)
        val_acc = xgboost_accuracy(X_norm[val_idx], labels[val_idx], booster)

        booster_path = os.path.join(output_dir, XGBOOST_BOOSTER_FILENAME)
        booster.save_model(booster_path)

        model = {
            "modelType": "xgboost",
            "boosterPath": XGBOOST_BOOSTER_FILENAME,
            "featureMean": mean.tolist(),
            "featureStd": std.tolist(),
            "featureNames": FEATURE_NAMES,
            "trainingExampleCount": len(examples),
            "trainAccuracy": train_acc,
            "validationAccuracy": val_acc,
            "trainedAtEpochMillis": int(time.time() * 1000),
        }
    else:
        weights, bias = train_logistic_regression(
            X_norm[train_idx], labels[train_idx],
            learning_rate=args.learning_rate, epochs=args.epochs)

        train_acc = accuracy(X_norm[train_idx], labels[train_idx], weights, bias)
        val_acc = accuracy(X_norm[val_idx], labels[val_idx], weights, bias)

        model = {
            "modelType": "logistic_regression",
            "weights": weights.tolist(),
            "bias": bias,
            "featureMean": mean.tolist(),
            "featureStd": std.tolist(),
            "featureNames": FEATURE_NAMES,
            "trainingExampleCount": len(examples),
            "trainAccuracy": train_acc,
            "validationAccuracy": val_acc,
            "trainedAtEpochMillis": int(time.time() * 1000),
        }

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)

    LOG.info("Trained %s on %d examples (%d train / %d val) — train_acc=%.3f val_acc=%.3f",
              args.model_type, len(examples), len(train_idx), len(val_idx), train_acc, val_acc)
    LOG.info("Wrote %s", args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
