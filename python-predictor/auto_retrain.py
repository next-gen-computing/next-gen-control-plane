"""
Safe automatic retraining: as real risk_outcomes.jsonl/risk_snapshots.jsonl grow from an actual running
cluster, a background thread periodically trains a *candidate* model and only replaces the live one if
the candidate does not regress — never a silent, unreviewed swap of what the risk model believes.

Why this exists as a gate, not a blind auto-promote: train_risk_model.py's own docstring is explicit
that retraining is meant to be an operator-run, reviewed step — a bad batch of real data (a weird
one-off outage, a burst of mislabeled snapshots) could otherwise silently change live risk assessments
with nobody noticing. This module keeps "the model gets better as real data accumulates" while keeping
that safety property: a candidate that doesn't clear the bar is saved for review, never installed.

Off by default (AUTO_RETRAIN_ENABLED=false) — every existing deployment is unaffected until an operator
opts in, the same precedent RISK_SNAPSHOT_LOGGING_ENABLED and ML_RISK_SCORER_ENABLED already set.

Honest methodology note: the candidate's validation accuracy and the live model's recorded validation
accuracy come from two different random 80/20 splits (the live model's split from whenever it was last
trained, the candidate's from right now) — this is a real, if minor, methodological softness, not a
controlled paired comparison against one fixed held-out set. Flagged here rather than silently assumed
away; a fixed golden validation set that persists across retraining runs is named future work.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from dataclasses import dataclass

import numpy as np

from features import FEATURE_NAMES, extract_features
from train_risk_model import (
    MIN_TRAINING_EXAMPLES,
    accuracy,
    build_examples,
    read_jsonl,
    train_logistic_regression,
    train_xgboost,
    xgboost_accuracy,
)

LOG = logging.getLogger("auto_retrain")

_MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model")
DEFAULT_MODEL_PATH = os.path.join(_MODEL_DIR, "risk_model.json")
DEFAULT_STATE_PATH = os.path.join(_MODEL_DIR, "auto_retrain_state.json")
DEFAULT_REJECTED_CANDIDATE_PATH = os.path.join(_MODEL_DIR, "risk_model_candidate_rejected.json")
XGBOOST_BOOSTER_FILENAME = "xgboost_booster.json"


@dataclass
class RetrainOutcome:
    status: str  # "skipped" | "promoted" | "rejected"
    reason: str
    candidate_val_accuracy: float | None = None
    live_val_accuracy: float | None = None
    example_count: int | None = None

    @staticmethod
    def skipped(reason: str) -> "RetrainOutcome":
        return RetrainOutcome("skipped", reason)


class AutoRetrainer:
    def __init__(
        self,
        outcomes_path: str,
        snapshots_path: str,
        model_path: str = DEFAULT_MODEL_PATH,
        state_path: str = DEFAULT_STATE_PATH,
        rejected_candidate_path: str = DEFAULT_REJECTED_CANDIDATE_PATH,
        check_interval_seconds: float = 3600.0,
        min_new_examples: int = 20,
        max_regression: float = 0.02,
        model_type: str = "xgboost",
        horizon_seconds: float = 60.0,
        seed: int = 42,
        on_cycle_complete=None,
    ):
        self.outcomes_path = outcomes_path
        self.snapshots_path = snapshots_path
        self.model_path = model_path
        self.state_path = state_path
        self.rejected_candidate_path = rejected_candidate_path
        self.check_interval_seconds = check_interval_seconds
        self.min_new_examples = min_new_examples
        self.max_regression = max_regression
        self.model_type = model_type
        self.horizon_seconds = horizon_seconds
        self.seed = seed
        # Deliberately kept prometheus-agnostic (this module has no prometheus_client dependency) —
        # predictor_service.py passes a callback that syncs its own metric objects instead, so this
        # class stays testable without a metrics registry involved.
        self._on_cycle_complete = on_cycle_complete

        self._promotions_total = 0
        self._rejections_total = 0
        self._last_run_epoch_millis: int | None = None
        self._last_outcome: RetrainOutcome | None = None
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()

    # ── Metrics accessors — predictor_service.py reads these into Prometheus gauges ──────
    @property
    def promotions_total(self) -> int:
        return self._promotions_total

    @property
    def rejections_total(self) -> int:
        return self._rejections_total

    @property
    def last_outcome(self) -> RetrainOutcome | None:
        return self._last_outcome

    def _load_state(self) -> dict:
        try:
            with open(self.state_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def _save_state(self, state: dict) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(self.state_path)), exist_ok=True)
        tmp_path = self.state_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(state, f)
        os.replace(tmp_path, self.state_path)  # atomic on the same filesystem

    def _read_live_validation_accuracy(self) -> float | None:
        try:
            with open(self.model_path, "r", encoding="utf-8") as f:
                return float(json.load(f)["validationAccuracy"])
        except (OSError, ValueError, KeyError):
            return None

    def run_once(self) -> RetrainOutcome:
        """One check-and-maybe-retrain cycle. Public and side-effect-observable via the return value
        specifically so tests can drive it deterministically without waiting on the background thread."""
        outcomes = read_jsonl(self.outcomes_path)
        snapshots = read_jsonl(self.snapshots_path)
        examples = build_examples(outcomes, snapshots, [], self.horizon_seconds)

        outcome: RetrainOutcome
        if len(examples) < MIN_TRAINING_EXAMPLES:
            outcome = RetrainOutcome.skipped(
                f"only {len(examples)} example(s) available (< {MIN_TRAINING_EXAMPLES} required)")
        else:
            state = self._load_state()
            considered_before = int(state.get("lastConsideredExampleCount", 0))
            new_since_last_check = len(examples) - considered_before

            if new_since_last_check < self.min_new_examples:
                outcome = RetrainOutcome.skipped(
                    f"only {new_since_last_check} new example(s) since the last check "
                    f"(< {self.min_new_examples} required) — real data just hasn't grown enough yet")
            else:
                labels = np.array([label for _, label in examples], dtype=np.float64)
                if len(set(labels.tolist())) < 2:
                    outcome = RetrainOutcome.skipped(
                        "all examples share one label — cannot train a classifier on one class yet "
                        "(enable RISK_SNAPSHOT_LOGGING_ENABLED for negative examples)")
                else:
                    outcome = self._train_and_maybe_promote(examples, labels)

            self._save_state({
                "lastConsideredExampleCount": len(examples),
                "lastAttemptEpochMillis": int(time.time() * 1000),
            })

        self._last_run_epoch_millis = int(time.time() * 1000)
        self._last_outcome = outcome
        LOG.info("Auto-retrain cycle: %s (%s)", outcome.status, outcome.reason)
        return outcome

    def _train_and_maybe_promote(self, examples: list, labels: np.ndarray) -> RetrainOutcome:
        X_raw = np.array([extract_features(history) for history, _ in examples])

        rng = np.random.default_rng(self.seed)
        order = rng.permutation(len(examples))
        split = max(1, int(len(examples) * 0.8))
        train_idx, val_idx = order[:split], order[split:]
        if len(val_idx) == 0:
            val_idx = train_idx

        mean = X_raw[train_idx].mean(axis=0)
        std = X_raw[train_idx].std(axis=0)
        std_safe = np.where(std == 0, 1.0, std)
        X_norm = (X_raw - mean) / std_safe

        model_dir = os.path.dirname(os.path.abspath(self.model_path))
        os.makedirs(model_dir, exist_ok=True)

        if self.model_type == "xgboost":
            booster = train_xgboost(X_norm[train_idx], labels[train_idx])
            train_acc = xgboost_accuracy(X_norm[train_idx], labels[train_idx], booster)
            val_acc = xgboost_accuracy(X_norm[val_idx], labels[val_idx], booster)
            # Must end in .json — xgboost's save_model picks its serialization format from the file
            # extension (silently falling back to UBJSON for an unrecognized one like ".candidate"),
            # and the eventual promoted path (XGBOOST_BOOSTER_FILENAME) is JSON, matching what
            # ModelStore.load_model expects.
            candidate_booster_path = os.path.join(model_dir, "xgboost_booster_candidate.json")
            booster.save_model(candidate_booster_path)
            candidate = {
                "modelType": "xgboost",
                "boosterPath": XGBOOST_BOOSTER_FILENAME,
                "featureMean": mean.tolist(), "featureStd": std.tolist(), "featureNames": FEATURE_NAMES,
                "trainingExampleCount": len(examples),
                "trainAccuracy": train_acc, "validationAccuracy": val_acc,
                "trainedAtEpochMillis": int(time.time() * 1000),
            }
        else:
            weights, bias = train_logistic_regression(X_norm[train_idx], labels[train_idx])
            train_acc = accuracy(X_norm[train_idx], labels[train_idx], weights, bias)
            val_acc = accuracy(X_norm[val_idx], labels[val_idx], weights, bias)
            candidate_booster_path = None
            candidate = {
                "modelType": "logistic_regression",
                "weights": weights.tolist(), "bias": bias,
                "featureMean": mean.tolist(), "featureStd": std.tolist(), "featureNames": FEATURE_NAMES,
                "trainingExampleCount": len(examples),
                "trainAccuracy": train_acc, "validationAccuracy": val_acc,
                "trainedAtEpochMillis": int(time.time() * 1000),
            }

        live_val_acc = self._read_live_validation_accuracy()
        should_promote = live_val_acc is None or val_acc >= (live_val_acc - self.max_regression)

        if should_promote:
            if candidate_booster_path:
                os.replace(candidate_booster_path, os.path.join(model_dir, XGBOOST_BOOSTER_FILENAME))
            tmp_path = self.model_path + ".tmp"
            with open(tmp_path, "w", encoding="utf-8") as f:
                json.dump(candidate, f, indent=2)
            os.replace(tmp_path, self.model_path)  # ModelStore's mtime poll picks this up within 5s
            self._promotions_total += 1
            LOG.info("Promoted new %s model: val_acc=%.3f (previous live: %s) trained on %d examples",
                      self.model_type, val_acc,
                      f"{live_val_acc:.3f}" if live_val_acc is not None else "none yet", len(examples))
            return RetrainOutcome("promoted", "candidate did not regress vs. the live model",
                                   candidate_val_accuracy=val_acc, live_val_accuracy=live_val_acc,
                                   example_count=len(examples))
        else:
            if candidate_booster_path:
                os.replace(candidate_booster_path, os.path.join(model_dir, "xgboost_booster_candidate_rejected.json"))
            with open(self.rejected_candidate_path, "w", encoding="utf-8") as f:
                json.dump(candidate, f, indent=2)
            self._rejections_total += 1
            LOG.warning(
                "Candidate model REJECTED (not promoted): val_acc=%.3f regressed more than %.3f below "
                "the live model's %.3f — saved to %s for review, live model unchanged",
                val_acc, self.max_regression, live_val_acc, self.rejected_candidate_path)
            return RetrainOutcome("rejected", "candidate regressed beyond the allowed tolerance",
                                   candidate_val_accuracy=val_acc, live_val_accuracy=live_val_acc,
                                   example_count=len(examples))

    # ── Background thread ────────────────────────────────────────────────────────────────
    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._loop, name="auto-retrain", daemon=True)
        self._thread.start()
        LOG.info("Auto-retrain thread started (check every %.0fs, min %d new examples, "
                  "max regression %.1f%%, model_type=%s)",
                  self.check_interval_seconds, self.min_new_examples, self.max_regression * 100, self.model_type)

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None

    def _loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.run_once()
            except Exception:  # noqa: BLE001 — a bad retrain cycle must never take the predictor down
                LOG.exception("Auto-retrain cycle failed with an unexpected error; will retry next interval")
            if self._on_cycle_complete is not None:
                try:
                    self._on_cycle_complete(self)
                except Exception:  # noqa: BLE001 — a broken metrics callback must not stop retraining
                    LOG.exception("auto-retrain on_cycle_complete callback failed")
            self._stop_event.wait(self.check_interval_seconds)
