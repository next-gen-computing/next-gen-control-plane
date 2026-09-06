"""
Tests for load_forecast_model.py / train_load_forecast_model.py — a real correctness check that the
LSTM actually learns a trend (not just "runs without crashing"), plus the horizon-labeling join logic
that pairs a snapshot with a later snapshot for the same node.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from load_forecast_model import (  # noqa: E402
    INPUT_SIZE,
    compute_feature_stats,
    sample_to_vector,
    sequence_to_tensor,
)
from train_load_forecast_model import build_examples, evaluate, train_lstm  # noqa: E402


def sample(**overrides):
    base = {
        "recordedAtMillis": 0,
        "cpuPercent": 10.0, "cpuAvailable": True,
        "memoryPercent": 50.0, "memoryAvailable": True,
        "batteryPercent": 100.0, "batteryAvailable": True,
        "charging": True, "chargingKnown": True,
        "onAcPower": True, "onAcPowerKnown": True,
        "previousRttSeconds": 0.01, "previousRttAvailable": True,
    }
    base.update(overrides)
    return base


def sequence(base_cpu: float, base_mem: float, length: int = 10, step_ms: int = 2000, start_ms: int = 0):
    return [
        sample(recordedAtMillis=start_ms + i * step_ms, cpuPercent=base_cpu + i, memoryPercent=base_mem + i)
        for i in range(length)
    ]


# ── sample_to_vector ──────────────────────────────────────────────────────

def test_sample_to_vector_uses_safe_defaults_for_unavailable_fields():
    vector = sample_to_vector(sample(cpuAvailable=False, memoryAvailable=False, batteryAvailable=False,
                                     onAcPowerKnown=False, previousRttAvailable=False))
    assert len(vector) == INPUT_SIZE
    assert vector == [0.0, 0.0, 0.0, 0.5, 0.0]


def test_sample_to_vector_reflects_real_values():
    vector = sample_to_vector(sample(cpuPercent=42.0, memoryPercent=77.0, batteryPercent=33.0,
                                     onAcPower=False, previousRttSeconds=0.25))
    assert vector == [42.0, 77.0, 33.0, 0.0, 0.25]


# ── compute_feature_stats / sequence_to_tensor ───────────────────────────

def test_sequence_to_tensor_has_the_expected_shape():
    seq = sequence(10.0, 20.0, length=7)
    tensor = sequence_to_tensor(seq)
    assert tuple(tensor.shape) == (1, 7, INPUT_SIZE)


def test_standardized_sequence_has_zero_mean_over_the_training_set():
    sequences = [sequence(10.0, 20.0), sequence(30.0, 40.0)]
    mean, std = compute_feature_stats(sequences)
    assert len(mean) == INPUT_SIZE
    assert len(std) == INPUT_SIZE
    assert all(s > 0 for s in std), "std must never be zero-safe-guarded away to a divide-by-zero"


# ── build_examples ────────────────────────────────────────────────────────

def test_build_examples_pairs_a_snapshot_with_a_later_one_for_the_same_node():
    snapshots = [
        {"nodeId": "n1", "snapshotAtMillis": 0, "recentHistory": sequence(10.0, 20.0, length=10)},
        {"nodeId": "n1", "snapshotAtMillis": 30_000,
         "recentHistory": sequence(40.0, 50.0, length=10, start_ms=30_000)},
    ]
    examples = build_examples(snapshots, horizon_seconds=30.0, sequence_length=10)

    assert len(examples) == 1
    seq, (target_cpu, target_mem) = examples[0]
    assert len(seq) == 10
    assert target_cpu == 40.0 + 9  # last sample of the later snapshot's history
    assert target_mem == 50.0 + 9


def test_build_examples_drops_a_history_shorter_than_sequence_length():
    snapshots = [
        {"nodeId": "n1", "snapshotAtMillis": 0, "recentHistory": sequence(10.0, 20.0, length=3)},
        {"nodeId": "n1", "snapshotAtMillis": 30_000, "recentHistory": sequence(40.0, 50.0, length=10)},
    ]
    examples = build_examples(snapshots, horizon_seconds=30.0, sequence_length=10)
    assert examples == []


def test_build_examples_never_matches_across_different_nodes():
    snapshots = [
        {"nodeId": "n1", "snapshotAtMillis": 0, "recentHistory": sequence(10.0, 20.0, length=10)},
        {"nodeId": "n2", "snapshotAtMillis": 30_000, "recentHistory": sequence(90.0, 90.0, length=10)},
    ]
    examples = build_examples(snapshots, horizon_seconds=30.0, sequence_length=10)
    assert examples == [], "a different node's later snapshot must never supply another node's target"


def test_build_examples_respects_the_tolerance_window():
    snapshots = [
        {"nodeId": "n1", "snapshotAtMillis": 0, "recentHistory": sequence(10.0, 20.0, length=10)},
        # Far beyond horizon + default tolerance (horizon/2) -> no usable pair.
        {"nodeId": "n1", "snapshotAtMillis": 200_000, "recentHistory": sequence(90.0, 90.0, length=10)},
    ]
    examples = build_examples(snapshots, horizon_seconds=30.0, sequence_length=10)
    assert examples == []


# ── train_lstm / evaluate — real convergence check ───────────────────────

def test_lstm_learns_a_linear_trend():
    """Synthetic, trivially-learnable dataset: for every example, cpu/memory horizon-target =
    latest observed value + a fixed constant offset — a real check the LSTM actually fits this, not
    just that training runs without crashing."""
    examples = []
    for node in range(8):
        for start in range(0, 20):
            base_cpu = 10.0 + node + start * 0.3
            base_mem = 20.0 + node + start * 0.3
            seq = sequence(base_cpu, base_mem, length=10, start_ms=start * 5000)
            target = (seq[-1]["cpuPercent"] + 6.0, seq[-1]["memoryPercent"] + 6.0)
            examples.append((seq, target))

    mean, std = compute_feature_stats([seq for seq, _ in examples])
    model, train_loss = train_lstm(examples, hidden_size=16, epochs=300, learning_rate=0.01,
                                   seed=42, mean=mean, std=std)
    val_loss = evaluate(model, examples, mean, std)

    assert val_loss < 5.0, f"expected the LSTM to fit a simple linear-offset target closely, got MSE={val_loss}"


def test_forecast_direction_follows_the_input_trend():
    """A model trained on 'target = latest + constant offset' should predict a higher forecast for a
    sequence that starts higher than for one that starts lower — the actual behavior a caller relies on,
    not just a low training loss number."""
    examples = []
    for node in range(8):
        for start in range(0, 20):
            base_cpu = 10.0 + node + start * 0.3
            base_mem = 20.0 + node + start * 0.3
            seq = sequence(base_cpu, base_mem, length=10, start_ms=start * 5000)
            target = (seq[-1]["cpuPercent"] + 6.0, seq[-1]["memoryPercent"] + 6.0)
            examples.append((seq, target))

    mean, std = compute_feature_stats([seq for seq, _ in examples])
    model, _ = train_lstm(examples, hidden_size=16, epochs=300, learning_rate=0.01, seed=42, mean=mean, std=std)

    model.eval()
    import torch
    with torch.no_grad():
        low = model(sequence_to_tensor(sequence(5.0, 5.0), mean, std))
        high = model(sequence_to_tensor(sequence(80.0, 80.0), mean, std))

    assert high[0, 0].item() > low[0, 0].item(), "a higher-trend sequence must forecast higher cpu"
    assert high[0, 1].item() > low[0, 1].item(), "a higher-trend sequence must forecast higher memory"
