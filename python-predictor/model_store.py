"""
Loads and hot-reloads the trained risk model written by train_risk_model.py, and serves predictions
from it. Honestly reports model_trained=False whenever no model file exists yet, or the file cannot be
parsed — never fabricates a score.

Reload is by mtime polling (cheap, and correct even if predictor_service.py is a single long-running
process) — see the module docstring in train_risk_model.py for why retraining itself is a manual,
operator-run step, not automatic.

Stage H: risk_model.json now carries a "modelType" ("logistic_regression" or "xgboost"; absent/old files
default to "logistic_regression" for backward compatibility with a model already on disk). For
"xgboost", the booster's own native serialization lives in a sidecar file next to risk_model.json,
referenced by the metadata's "boosterPath".
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time

import numpy as np
import xgboost as xgb

from features import extract_features

LOG = logging.getLogger("model_store")

DEFAULT_MODEL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model", "risk_model.json")
RELOAD_CHECK_INTERVAL_SECONDS = 5.0


class ModelStore:
    """Thread-safe holder for the current model, reloaded whenever the file's mtime changes."""

    def __init__(self, model_path: str = DEFAULT_MODEL_PATH):
        self._model_path = model_path
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
            mtime = os.path.getmtime(self._model_path)
        except OSError:
            with self._lock:
                if self._model is not None:
                    LOG.warning("Model file %s disappeared; reverting to untrained", self._model_path)
                self._model = None
                self._last_mtime = None
            return

        if mtime == self._last_mtime:
            return

        try:
            with open(self._model_path, "r", encoding="utf-8") as f:
                raw = json.load(f)
            # Stage Z: valid JSON that isn't an object (e.g. a real possibility from a non-atomic
            # partial overwrite mid-write) previously reached raw.get(...) below and raised an uncaught
            # AttributeError — not in the except clause's type list — breaking every subsequent
            # prediction call until the file was manually fixed.
            if not isinstance(raw, dict):
                raise ValueError(f"model file did not contain a JSON object (got {type(raw).__name__})")
            model_type = raw.get("modelType", "logistic_regression")
            model = {
                "type": model_type,
                "mean": np.array(raw["featureMean"], dtype=np.float64),
                "std": np.array(raw["featureStd"], dtype=np.float64),
                "training_example_count": int(raw["trainingExampleCount"]),
                "trained_at_epoch_millis": int(raw.get("trainedAtEpochMillis", 0)),
            }
            if model_type == "xgboost":
                booster_path = os.path.join(os.path.dirname(self._model_path), raw["boosterPath"])
                booster = xgb.Booster()
                booster.load_model(booster_path)
                model["booster"] = booster
            else:
                model["weights"] = np.array(raw["weights"], dtype=np.float64)
                model["bias"] = float(raw["bias"])
        except (OSError, ValueError, KeyError, xgb.core.XGBoostError) as e:
            LOG.warning("Could not load model file %s: %s", self._model_path, e)
            return

        with self._lock:
            self._model = model
            self._last_mtime = mtime
        LOG.info("Loaded %s model trained on %d examples", model["type"], model["training_example_count"])

    def predict(self, recent_samples: list[dict]) -> tuple[float, bool, int]:
        """Returns (failure_probability, model_trained, training_example_count)."""
        self._maybe_reload()
        with self._lock:
            model = self._model
        if model is None:
            return 0.0, False, 0

        try:
            x = extract_features(recent_samples)
        except ValueError as e:
            # Stage BB: a NaN/Inf anywhere in the request's trend samples must not silently flow through
            # to a confidently-wrong verdict — see extract_features' own Javadoc-style note above it.
            # Same tuple shape as "no model loaded" at all: an honest untrained response, not a crash.
            LOG.warning("Rejecting prediction request with non-finite features: %s", e)
            return 0.0, False, 0
        std_safe = np.where(model["std"] == 0, 1.0, model["std"])
        z = (x - model["mean"]) / std_safe

        if model["type"] == "xgboost":
            probability = float(model["booster"].predict(xgb.DMatrix(z.reshape(1, -1)))[0])
        else:
            logit = float(np.dot(model["weights"], z) + model["bias"])
            probability = 1.0 / (1.0 + np.exp(-logit))
        return probability, True, model["training_example_count"]

    def model_type(self) -> str | None:
        """The currently-loaded model's type ("logistic_regression"/"xgboost"), or None if untrained."""
        with self._lock:
            model = self._model
        return model["type"] if model is not None else None
