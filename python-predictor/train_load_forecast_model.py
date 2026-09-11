#!/usr/bin/env python3
"""
Trains a real LSTM load-forecasting model from real collected data and writes
model/load_forecast_weights.pt + model/load_forecast_model.json for predictor_service.py (via
LoadForecastStore) to hot-reload.

Run manually by the operator once RISK_SNAPSHOT_LOGGING_ENABLED has been on long enough to span at least
one horizon window (see RiskSnapshotLogger) — retraining is never automatic, matching
train_risk_model.py's own manual-CLI discipline:

    python train_load_forecast_model.py \\
        --snapshots  /path/to/risk_snapshots.jsonl \\
        --output-dir model/

Input: risk_snapshots.jsonl (RiskSnapshotLogger, opt-in via RISK_SNAPSHOT_LOGGING_ENABLED) — one row per
node per RiskMonitor sweep, each carrying that node's recent raw telemetry sequence. Consecutive
snapshots for the same node, sorted by time, are the training corpus: for a snapshot at time t, the
target is the (cpu_percent, memory_percent) reading of a later snapshot for the same node found at or
near t + --horizon-seconds.

Unlike train_risk_model.py's collapsed trend scalars (features.py), this model consumes each snapshot's
raw per-timestep sequence directly (load_forecast_model.py) — a forecast needs the actual shape of the
trend, not a summary of it.
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
import torch
from torch import nn

from load_forecast_model import LoadForecastLSTM, compute_feature_stats, sequence_to_tensor

LOG = logging.getLogger("train_load_forecast_model")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")

DEFAULT_OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model")
WEIGHTS_FILENAME = "load_forecast_weights.pt"
METADATA_FILENAME = "load_forecast_model.json"

# Same role as train_risk_model.py's MIN_TRAINING_EXAMPLES — a pipeline-correctness floor, not a claim
# that this many examples makes the forecast trustworthy.
MIN_TRAINING_EXAMPLES = 20


def read_jsonl(path: str) -> list[dict]:
    if not path or not os.path.exists(path):
        return []
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                LOG.warning("Skipping malformed line %d in %s: %s", line_num, path, e)
    return rows


def build_examples(snapshots: list[dict], horizon_seconds: float, sequence_length: int,
                   tolerance_seconds: float | None = None) -> list[tuple[list[dict], tuple[float, float]]]:
    """
    Returns (sequence, (target_cpu_percent, target_memory_percent)) pairs.

    Groups snapshots by node, sorted by time. For a snapshot at time t whose recentHistory carries at
    least sequence_length samples, the training target is the (cpu, memory) reading of the first later
    snapshot for the SAME node at or after t + horizon_seconds — provided it lands within
    tolerance_seconds of that target (default: half the horizon), since a snapshot found much later than
    requested is not a reasonable stand-in for "the state at exactly this horizon".
    """
    if tolerance_seconds is None:
        tolerance_seconds = horizon_seconds / 2.0
    horizon_ms = horizon_seconds * 1000.0
    tolerance_ms = tolerance_seconds * 1000.0

    by_node: dict[str, list[dict]] = defaultdict(list)
    for row in snapshots:
        node_id = row.get("nodeId")
        if node_id is None or row.get("snapshotAtMillis") is None:
            continue
        by_node[node_id].append(row)

    examples: list[tuple[list[dict], tuple[float, float]]] = []
    for rows in by_node.values():
        rows.sort(key=lambda r: r["snapshotAtMillis"])
        for i, row in enumerate(rows):
            history = row.get("recentHistory", [])
            if len(history) < sequence_length:
                continue
            target_at = row["snapshotAtMillis"] + horizon_ms

            target_row = None
            for later in rows[i + 1:]:
                if later["snapshotAtMillis"] >= target_at:
                    target_row = later
                    break
            if target_row is None or (target_row["snapshotAtMillis"] - target_at) > tolerance_ms:
                continue

            target_history = target_row.get("recentHistory", [])
            if not target_history:
                continue
            target_latest = target_history[-1]
            if not target_latest.get("cpuAvailable", False) or not target_latest.get("memoryAvailable", False):
                continue  # an honest target needs both real values — never fabricate one

            examples.append((
                history[-sequence_length:],
                (target_latest["cpuPercent"], target_latest["memoryPercent"]),
            ))
    return examples


def train_lstm(train_examples: list[tuple[list[dict], tuple[float, float]]], hidden_size: int, epochs: int,
               learning_rate: float, seed: int, mean: list[float],
               std: list[float]) -> tuple[LoadForecastLSTM, float]:
    """Trains on standardized inputs and standardized targets (cpu_percent/memory_percent share feature
    indices 0/1 of the same standardization space as the inputs) — plain percent-scale targets make
    gradient descent converge poorly here, the same reason train_risk_model.py standardizes its inputs.
    Returns (trained model, final training MSE loss in standardized units)."""
    torch.manual_seed(seed)
    model = LoadForecastLSTM(hidden_size=hidden_size)
    optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)
    loss_fn = nn.MSELoss()

    X = torch.cat([sequence_to_tensor(seq, mean, std) for seq, _ in train_examples], dim=0)
    y_raw = torch.tensor([target for _, target in train_examples], dtype=torch.float32)
    y_norm = (y_raw - torch.tensor(mean[:2])) / torch.tensor(std[:2])

    model.train()
    final_loss = float("nan")
    for _ in range(epochs):
        optimizer.zero_grad()
        prediction = model(X)
        loss = loss_fn(prediction, y_norm)
        loss.backward()
        optimizer.step()
        final_loss = float(loss.item())
    return model, final_loss


def evaluate(model: LoadForecastLSTM, examples: list[tuple[list[dict], tuple[float, float]]],
            mean: list[float], std: list[float]) -> float:
    """Mean-squared error in real percentage-point units (de-standardized), for an interpretable
    validation metric — "how many percentage-points^2 off is the forecast", not an abstract normalized
    number."""
    if not examples:
        return float("nan")
    model.eval()
    with torch.no_grad():
        X = torch.cat([sequence_to_tensor(seq, mean, std) for seq, _ in examples], dim=0)
        y_raw = torch.tensor([target for _, target in examples], dtype=torch.float32)
        prediction_norm = model(X)
        prediction_raw = prediction_norm * torch.tensor(std[:2]) + torch.tensor(mean[:2])
        loss = nn.functional.mse_loss(prediction_raw, y_raw)
    return float(loss.item())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--snapshots", default=os.environ.get("RISK_SNAPSHOTS_PATH", ""))
    parser.add_argument("--horizon-seconds", type=float, default=300.0,
                        help="how far ahead the model learns to forecast (default: 5 minutes)")
    parser.add_argument("--sequence-length", type=int, default=10,
                        help="trailing samples fed to the LSTM; shorter sequences are dropped, not padded")
    parser.add_argument("--hidden-size", type=int, default=16)
    parser.add_argument("--epochs", type=int, default=200)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    snapshots = read_jsonl(args.snapshots)
    LOG.info("Loaded %d snapshot row(s)", len(snapshots))

    examples = build_examples(snapshots, args.horizon_seconds, args.sequence_length)
    if len(examples) < MIN_TRAINING_EXAMPLES:
        LOG.error("Only %d usable (sequence -> future reading) pair(s) available (< %d required) — "
                  "refusing to write a model. Enable RISK_SNAPSHOT_LOGGING_ENABLED and let it run for "
                  "at least one horizon window (%.0fs) longer than it has so far.",
                  len(examples), MIN_TRAINING_EXAMPLES, args.horizon_seconds)
        return 1

    rng = np.random.default_rng(args.seed)
    order = rng.permutation(len(examples))
    split = max(1, int(len(examples) * 0.8))
    train_idx, val_idx = order[:split], order[split:]
    train_examples = [examples[i] for i in train_idx]
    val_examples = [examples[i] for i in val_idx] if len(val_idx) > 0 else train_examples

    mean, std = compute_feature_stats([seq for seq, _ in train_examples])

    model, train_loss = train_lstm(train_examples, args.hidden_size, args.epochs, args.learning_rate,
                                   args.seed, mean, std)
    val_loss = evaluate(model, val_examples, mean, std)

    os.makedirs(args.output_dir, exist_ok=True)
    weights_path = os.path.join(args.output_dir, WEIGHTS_FILENAME)
    torch.save(model.state_dict(), weights_path)

    metadata = {
        "sequenceLength": args.sequence_length,
        "hiddenSize": args.hidden_size,
        "horizonSeconds": args.horizon_seconds,
        "featureMean": mean,
        "featureStd": std,
        "trainingExampleCount": len(examples),
        "trainLoss": train_loss,
        "validationLoss": val_loss,
        "trainedAtEpochMillis": int(time.time() * 1000),
    }
    metadata_path = os.path.join(args.output_dir, METADATA_FILENAME)
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    LOG.info("Trained on %d examples (%d train / %d val) — train_mse=%.3f val_mse=%.3f",
              len(examples), len(train_examples), len(val_examples), train_loss, val_loss)
    LOG.info("Wrote %s and %s", weights_path, metadata_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
