"""
LSTM load-forecasting model (Stage I) — predicts future CPU/memory utilization from the raw
per-timestep telemetry sequence.

Deliberately a different input shape from features.py: that module collapses a node's history into a
handful of trend scalars for the failure classifier (train_risk_model.py); a forecast needs to see the
actual shape of the trend over time, not a summary of it, so this model consumes the raw sequence
directly via torch.nn.LSTM.

Per-timestep input features (raw, not collapsed), see sample_to_vector:
  cpu_percent, memory_percent, battery_percent (0.0 if unavailable),
  on_ac_power (1.0/0.0, 0.5 if unknown), previous_rtt_seconds (0.0 if unavailable)
"""

from __future__ import annotations

import numpy as np
import torch
from torch import nn

INPUT_SIZE = 5
OUTPUT_SIZE = 2  # (cpu_percent, memory_percent) at the forecast horizon


class LoadForecastLSTM(nn.Module):
    """A single-layer LSTM plus a linear head predicting (cpu_percent, memory_percent) at the horizon."""

    def __init__(self, hidden_size: int = 16):
        super().__init__()
        self.hidden_size = hidden_size
        self.lstm = nn.LSTM(input_size=INPUT_SIZE, hidden_size=hidden_size, num_layers=1, batch_first=True)
        self.head = nn.Linear(hidden_size, OUTPUT_SIZE)

    def forward(self, sequence: torch.Tensor) -> torch.Tensor:
        """sequence: (batch, seq_len, INPUT_SIZE) -> (batch, OUTPUT_SIZE)."""
        _, (h_n, _) = self.lstm(sequence)
        last_hidden = h_n[-1]  # (batch, hidden_size) — final layer's final hidden state
        return self.head(last_hidden)


def sample_to_vector(sample: dict) -> list[float]:
    """Projects one raw telemetry sample (JSONL/TrendSample-shaped dict, same keys features.py uses)
    into the 5 raw per-timestep input features this model consumes."""
    cpu = sample.get("cpuPercent", 0.0) if sample.get("cpuAvailable", False) else 0.0
    memory = sample.get("memoryPercent", 0.0) if sample.get("memoryAvailable", False) else 0.0
    battery = sample.get("batteryPercent", 0.0) if sample.get("batteryAvailable", False) else 0.0
    if sample.get("onAcPowerKnown", False):
        on_ac = 1.0 if sample.get("onAcPower", False) else 0.0
    else:
        on_ac = 0.5  # unknown — neither "definitely on battery" nor "definitely on AC"
    rtt = sample.get("previousRttSeconds", 0.0) if sample.get("previousRttAvailable", False) else 0.0
    return [cpu, memory, battery, on_ac, rtt]


def compute_feature_stats(sequences: list[list[dict]]) -> tuple[list[float], list[float]]:
    """Per-feature mean/std across every timestep of every given sequence (train-set-only, same
    discipline train_risk_model.py already uses for its own standardization). Used to standardize model
    inputs; since cpu_percent/memory_percent are feature indices 0/1 of this same space, these same two
    stats also de-standardize the model's output back to real percent values at serving time."""
    all_vectors = np.array([sample_to_vector(s) for seq in sequences for s in seq], dtype=np.float64)
    mean = all_vectors.mean(axis=0)
    std = all_vectors.std(axis=0)
    std_safe = np.where(std == 0, 1.0, std)
    return mean.tolist(), std_safe.tolist()


def sequence_to_tensor(samples: list[dict], mean: list[float] | None = None,
                       std: list[float] | None = None) -> torch.Tensor:
    """samples: oldest-first list of raw telemetry dicts -> (1, len(samples), INPUT_SIZE) float32 tensor.
    Standardizes each feature when mean/std are given (from compute_feature_stats); returns raw values
    otherwise."""
    vectors = [sample_to_vector(s) for s in samples]
    if mean is not None and std is not None:
        vectors = [[(v - m) / s for v, m, s in zip(vec, mean, std)] for vec in vectors]
    return torch.tensor([vectors], dtype=torch.float32)
