"""
Tests for auto_retrain.py — the safety property under test throughout is the promotion gate itself:
a candidate that doesn't clear the bar must never overwrite the live model, and every skip condition
(not enough total data, not enough *new* data, one-label data) must not touch the live model either.
"""

import json
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from auto_retrain import AutoRetrainer  # noqa: E402

_RNG = random.Random(1234)


def healthy_sample(t):
    return {
        "recordedAtMillis": t, "cpuPercent": 10.0 + _RNG.uniform(-8, 8), "cpuAvailable": True,
        "memoryPercent": 20.0 + _RNG.uniform(-10, 10), "memoryAvailable": True,
        "batteryPercent": 90.0 + _RNG.uniform(-5, 5), "batteryAvailable": True,
        "onAcPower": True, "onAcPowerKnown": True,
        "previousRttSeconds": max(0.001, 0.01 + _RNG.uniform(-0.005, 0.02)), "previousRttAvailable": True,
    }


def risky_sample(t):
    # Real jitter, not a perfectly deterministic pair of points — a classifier trained on this gets
    # real, bounded (not trivially 100%) accuracy, which is what test_rejects_a_regressing_candidate
    # below actually needs: an artificially-perfect fake "live" model that a genuine candidate can't
    # just coincidentally match by the data being too trivially separable to mean anything.
    return {
        "recordedAtMillis": t, "cpuPercent": 92.0 + _RNG.uniform(-10, 8), "cpuAvailable": True,
        "memoryPercent": 97.0 + _RNG.uniform(-12, 3), "memoryAvailable": True,
        "batteryPercent": 3.0 + _RNG.uniform(-2, 6), "batteryAvailable": True,
        "onAcPower": False, "onAcPowerKnown": True,
        "previousRttSeconds": max(0.001, 2.0 + _RNG.uniform(-1.2, 1.2)), "previousRttAvailable": True,
    }


def write_jsonl(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")


def separable_outcomes_and_snapshots(n_per_class=15):
    """Enough real, cleanly-separable synthetic examples to actually train past MIN_TRAINING_EXAMPLES —
    mirrors test_train_risk_model.py's own separable-dataset convention, just shaped as JSONL rows."""
    outcomes = []
    snapshots = []
    for i in range(n_per_class):
        node = f"dying-{i}"
        base = 1_000_000 + i * 10_000
        outcomes.append({
            "nodeId": node,
            "transitionAtMillis": base + 5000,
            "recentHistory": [risky_sample(base + j * 1000) for j in range(5)],
            "riskScoreAtAssessment": 0.9,
            "riskReasons": ["memory_pressure"],
            "migrationAlreadyTriggered": False,
        })
    for i in range(n_per_class):
        node = f"healthy-{i}"
        base = 2_000_000 + i * 10_000
        snapshots.append({
            "nodeId": node,
            "snapshotAtMillis": base + 5000,
            "recentHistory": [healthy_sample(base + j * 1000) for j in range(5)],
            "riskScoreAtAssessment": 0.0,
        })
    return outcomes, snapshots


def overlapping_sample(t, base_cpu):
    """Deliberately noisy/overlapping features (wide, shared range) — a real classifier trained on this
    has genuinely bounded accuracy, not a coincidental 100% from too-clean synthetic separation. Used
    only by the rejection test, which needs a real candidate that reliably falls short of an
    artificially-perfect claimed baseline, by construction rather than by luck of the random seed."""
    return {
        "recordedAtMillis": t, "cpuPercent": base_cpu + _RNG.uniform(-35, 35), "cpuAvailable": True,
        "memoryPercent": 50.0 + _RNG.uniform(-35, 35), "memoryAvailable": True,
        "batteryPercent": 50.0 + _RNG.uniform(-35, 35), "batteryAvailable": True,
        "onAcPower": _RNG.random() > 0.5, "onAcPowerKnown": True,
        "previousRttSeconds": max(0.001, 1.0 + _RNG.uniform(-0.9, 0.9)), "previousRttAvailable": True,
    }


def noisy_outcomes_and_snapshots(n_per_class=15, mislabel_every=3):
    """Real label noise (every Nth example's features contradict its label), not just statistical
    overlap — this puts a hard, deterministic ceiling on achievable accuracy regardless of validation-
    split luck or how well the model happens to overfit, which pure feature-noise couldn't guarantee on
    a tiny validation set (confirmed: XGBoost found a lucky 100% split against overlapping-but-not-
    contradictory data more than once during test development)."""
    outcomes, snapshots = [], []
    for i in range(n_per_class):
        base = 1_000_000 + i * 10_000
        contradict = (i % mislabel_every == 0)
        cpu_base = 40.0 if contradict else 60.0  # a "dying" row with healthy-looking features
        outcomes.append({
            "nodeId": f"dying-{i}", "transitionAtMillis": base + 5000,
            "recentHistory": [overlapping_sample(base + j * 1000, base_cpu=cpu_base) for j in range(5)],
            "riskScoreAtAssessment": 0.9, "riskReasons": ["memory_pressure"],
            "migrationAlreadyTriggered": False,
        })
    for i in range(n_per_class):
        base = 2_000_000 + i * 10_000
        contradict = (i % mislabel_every == 0)
        cpu_base = 60.0 if contradict else 40.0  # a "healthy" row with risky-looking features
        snapshots.append({
            "nodeId": f"healthy-{i}", "snapshotAtMillis": base + 5000,
            "recentHistory": [overlapping_sample(base + j * 1000, base_cpu=cpu_base) for j in range(5)],
            "riskScoreAtAssessment": 0.0,
        })
    return outcomes, snapshots


def make_retrainer(tmp_path, **overrides):
    outcomes_path = str(tmp_path / "risk_outcomes.jsonl")
    snapshots_path = str(tmp_path / "risk_snapshots.jsonl")
    if not os.path.exists(outcomes_path):
        write_jsonl(outcomes_path, [])
    if not os.path.exists(snapshots_path):
        write_jsonl(snapshots_path, [])
    kwargs = dict(
        outcomes_path=outcomes_path,
        snapshots_path=snapshots_path,
        model_path=str(tmp_path / "risk_model.json"),
        state_path=str(tmp_path / "auto_retrain_state.json"),
        rejected_candidate_path=str(tmp_path / "risk_model_candidate_rejected.json"),
        min_new_examples=5,
    )
    kwargs.update(overrides)
    return AutoRetrainer(**kwargs)


def test_skips_when_below_the_absolute_minimum(tmp_path):
    outcomes, _ = separable_outcomes_and_snapshots(n_per_class=2)
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", [])

    retrainer = make_retrainer(tmp_path)
    outcome = retrainer.run_once()

    assert outcome.status == "skipped"
    assert not os.path.exists(retrainer.model_path)


def test_skips_when_all_examples_share_one_label(tmp_path):
    outcomes, _ = separable_outcomes_and_snapshots(n_per_class=25)  # clears MIN_TRAINING_EXAMPLES alone
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", [])  # no negatives at all

    retrainer = make_retrainer(tmp_path)
    outcome = retrainer.run_once()

    assert outcome.status == "skipped"
    assert "one label" in outcome.reason
    assert not os.path.exists(retrainer.model_path)


def test_promotes_when_no_live_model_exists_yet(tmp_path):
    outcomes, snapshots = separable_outcomes_and_snapshots(n_per_class=15)
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", snapshots)

    retrainer = make_retrainer(tmp_path, model_type="xgboost")
    outcome = retrainer.run_once()

    assert outcome.status == "promoted"
    assert outcome.live_val_accuracy is None  # nothing to compare against the first time
    assert os.path.exists(retrainer.model_path)
    assert retrainer.promotions_total == 1
    with open(retrainer.model_path, encoding="utf-8") as f:
        written = json.load(f)
    assert written["trainingExampleCount"] == 30
    booster_path = os.path.join(os.path.dirname(retrainer.model_path), "xgboost_booster.json")
    assert os.path.exists(booster_path)
    # Not just "the file exists" — it must actually be loadable by the same API ModelStore uses, which
    # would fail loudly if the promoted file were still in a format load_model doesn't expect.
    import xgboost as xgb
    xgb.Booster().load_model(booster_path)


def test_second_call_with_no_new_data_is_skipped_not_retrained(tmp_path):
    outcomes, snapshots = separable_outcomes_and_snapshots(n_per_class=15)
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", snapshots)
    retrainer = make_retrainer(tmp_path)

    first = retrainer.run_once()
    assert first.status == "promoted"

    second = retrainer.run_once()

    assert second.status == "skipped"
    assert "new example" in second.reason
    assert retrainer.promotions_total == 1  # unchanged — no needless retrain on stale data


def test_growing_real_data_triggers_a_second_real_retrain(tmp_path):
    outcomes, snapshots = separable_outcomes_and_snapshots(n_per_class=15)
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", snapshots)
    retrainer = make_retrainer(tmp_path)
    assert retrainer.run_once().status == "promoted"

    more_outcomes, more_snapshots = separable_outcomes_and_snapshots(n_per_class=10)
    # Give the new batch distinct node ids so build_examples treats them as genuinely new nodes.
    for row in more_outcomes:
        row["nodeId"] = "second-batch-" + row["nodeId"]
    for row in more_snapshots:
        row["nodeId"] = "second-batch-" + row["nodeId"]
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes + more_outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", snapshots + more_snapshots)

    outcome = retrainer.run_once()

    assert outcome.status in ("promoted", "rejected")  # real data accumulated -> a real cycle ran
    assert outcome.example_count == 50
    assert retrainer.promotions_total + retrainer.rejections_total == 2


def test_rejects_a_regressing_candidate_and_leaves_the_live_model_untouched(tmp_path):
    outcomes, snapshots = noisy_outcomes_and_snapshots(n_per_class=15)
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", snapshots)
    retrainer = make_retrainer(tmp_path, min_new_examples=1, max_regression=0.0)

    # A live model claiming implausibly-perfect accuracy that a freshly-trained real candidate on this
    # small dataset is very unlikely to match with zero tolerance for regression.
    live_model = {
        "modelType": "xgboost", "boosterPath": "xgboost_booster.json",
        "featureMean": [0.0] * 14, "featureStd": [1.0] * 14,
        "featureNames": [], "trainingExampleCount": 999999,
        "trainAccuracy": 1.0, "validationAccuracy": 0.999999,
        "trainedAtEpochMillis": 1,
    }
    with open(retrainer.model_path, "w", encoding="utf-8") as f:
        json.dump(live_model, f)
    live_model_bytes_before = open(retrainer.model_path, "rb").read()

    outcome = retrainer.run_once()

    assert outcome.status == "rejected"
    assert retrainer.rejections_total == 1
    assert retrainer.promotions_total == 0
    # The live model file must be byte-for-byte unchanged — the whole point of the gate.
    assert open(retrainer.model_path, "rb").read() == live_model_bytes_before
    assert os.path.exists(retrainer.rejected_candidate_path)
    with open(retrainer.rejected_candidate_path, encoding="utf-8") as f:
        rejected = json.load(f)
    assert rejected["validationAccuracy"] < 0.999999


def test_start_and_stop_background_thread_runs_a_real_cycle(tmp_path):
    outcomes, snapshots = separable_outcomes_and_snapshots(n_per_class=15)
    write_jsonl(tmp_path / "risk_outcomes.jsonl", outcomes)
    write_jsonl(tmp_path / "risk_snapshots.jsonl", snapshots)
    retrainer = make_retrainer(tmp_path, check_interval_seconds=0.05)

    retrainer.start()
    deadline = time.time() + 5.0
    while retrainer.promotions_total == 0 and time.time() < deadline:
        time.sleep(0.05)
    retrainer.stop()

    assert retrainer.promotions_total == 1
    assert os.path.exists(retrainer.model_path)
