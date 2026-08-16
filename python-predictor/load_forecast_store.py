"""
Loads and hot-reloads the trained LSTM load-forecasting model written by train_load_forecast_model.py,
and serves (predicted_cpu_percent, predicted_memory_percent) forecasts from it. Honestly reports
model_trained=False whenever no model exists yet, the metadata/weights file is missing or malformed, or
the given sequence is shorter than the model's sequenceLength — never fabricates a forecast.

Same mtime-polled hot-reload discipline as ModelStore (Stage G/H) — reload is cheap and correct even in
a single long-running process; retraining itself stays a manual, operator-run step
(train_load_forecast_model.py).
"""

from __future__ import annotations

import json
import logging
import os
import pickle
import threading
import time

import torch

from load_forecast_model import LoadForecastLSTM, sequence_to_tensor

LOG = logging.getLogger("load_forecast_store")

_MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model")
DEFAULT_METADATA_PATH = os.path.join(_MODEL_DIR, "load_forecast_model.json")
DEFAULT_WEIGHTS_PATH = os.path.join(_MODEL_DIR, "load_forecast_weights.pt")
RELOAD_CHECK_INTERVAL_SECONDS = 5.0


class LoadForecastStore:
    """Thread-safe holder for the current LSTM model, reloaded whenever the metadata file's mtime changes."""

    def __init__(self, metadata_path: str = DEFAULT_METADATA_PATH, weights_path: str = DEFAULT_WEIGHTS_PATH):
        self._metadata_path = metadata_path
        self._weights_path = weights_path
        self._lock = threading.Lock()
        self._model: dict | None = None
        self._last_mtime: float | None = None
        self._last_checked = 0.0
        self._maybe_reload(force=True)

    def _maybe_reload(self, force: bool = False) -> None:
        now = time.time()
        if not force and (now - self._last_checked) < RELOAD_CHECK_INTERVAL_SECONDS:
            return
        self._last_checked = now

        try:
            mtime = os.path.getmtime(self._metadata_path)
        except OSError:
            with self._lock:
                if self._model is not None:
                    LOG.warning("Load-forecast metadata %s disappeared; reverting to untrained",
                                self._metadata_path)
                self._model = None
                self._last_mtime = None
            return

        if mtime == self._last_mtime:
            return

        try:
            with open(self._metadata_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
            net = LoadForecastLSTM(hidden_size=int(meta["hiddenSize"]))
            state_dict = torch.load(self._weights_path, map_location="cpu", weights_only=True)
            net.load_state_dict(state_dict)
            net.eval()
            model = {
                "net": net,
                "sequence_length": int(meta["sequenceLength"]),
                "mean": [float(v) for v in meta["featureMean"]],
                "std": [float(v) for v in meta["featureStd"]],
                "training_example_count": int(meta["trainingExampleCount"]),
                "horizon_seconds": float(meta["horizonSeconds"]),
            }
        except (OSError, ValueError, KeyError, RuntimeError, EOFError, IndexError,
                pickle.UnpicklingError) as e:
            # Stage Z: an EMPTY .pt file raises EOFError, and GARBAGE bytes raise
            # pickle.UnpicklingError from torch's own weights_only unpickler (empirically confirmed
            # against this project's actual pinned torch version — IndexError/RuntimeError are kept too
            # as real possibilities for other corruption shapes, e.g. a truncated-but-zip-structured
            # file) — all previously uncaught here, escaping through predict() into GetPrediction and
            # breaking EVERY subsequent prediction call (not falling back to the already-loaded good
            # in-memory model) until the file was manually fixed. Same "log and keep serving the
            # last-good model" behavior already correct for every other corruption mode.
            LOG.warning("Could not load load-forecast model %s: %s", self._metadata_path, e)
            return

        with self._lock:
            self._model = model
            self._last_mtime = mtime
        LOG.info("Loaded load-forecast model (seq_len=%d) trained on %d examples",
                  model["sequence_length"], model["training_example_count"])

    def predict(self, recent_samples: list[dict]) -> tuple[float, float, bool, int]:
        """Returns (predicted_cpu_percent, predicted_memory_percent, model_trained, training_example_count)."""
        self._maybe_reload()
        with self._lock:
            model = self._model
        if model is None:
            return 0.0, 0.0, False, 0
        if len(recent_samples) < model["sequence_length"]:
            # A model exists but this caller's sequence is too short to feed it — honestly untrained
            # for THIS prediction, not a fabricated forecast from insufficient history.
            return 0.0, 0.0, False, model["training_example_count"]

        sequence = recent_samples[-model["sequence_length"]:]
        with torch.no_grad():
            output = model["net"](sequence_to_tensor(sequence, model["mean"], model["std"]))
        # The model was trained on standardized targets (cpu_percent/memory_percent share feature
        # indices 0/1 of the same standardization space as the inputs) — de-standardize back to real
        # percent values before returning.
        predicted_cpu = output[0, 0].item() * model["std"][0] + model["mean"][0]
        predicted_memory = output[0, 1].item() * model["std"][1] + model["mean"][1]
        return float(predicted_cpu), float(predicted_memory), True, model["training_example_count"]

    def horizon_seconds(self) -> float | None:
        """The currently-loaded model's forecast horizon, or None if untrained."""
        with self._lock:
            model = self._model
        return model["horizon_seconds"] if model is not None else None
