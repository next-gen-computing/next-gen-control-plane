"""
Tests for predictor_service.py's GetPrediction — the model-present vs model-absent paths, driven
directly against the servicer (no real gRPC transport needed: GetPrediction never touches its
`context` argument, so passing None is safe and keeps these tests fast and dependency-free).
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import control_plane_pb2 as pb2  # noqa: E402
from features import FEATURE_NAMES  # noqa: E402
from load_forecast_model import LoadForecastLSTM  # noqa: E402
from load_forecast_store import LoadForecastStore  # noqa: E402
from model_store import ModelStore  # noqa: E402
from predictor_service import PredictorServiceServicer  # noqa: E402
from train_risk_model import train_xgboost  # noqa: E402

N_FEATURES = len(FEATURE_NAMES)


def make_request(node_id="n1", cpu=50.0, memory=60.0, sequence_length=1):
    samples = [
        pb2.TrendSample(
            recorded_at_epoch_millis=1000 + i * 1000,
            cpu_percent=cpu, cpu_available=True,
            memory_percent=memory, memory_available=True,
            battery_percent=50.0, battery_available=True,
            on_ac_power=True, on_ac_power_known=True,
            previous_rtt_seconds=0.01, previous_rtt_available=True,
        )
        for i in range(sequence_length)
    ]
    return pb2.PredictionRequest(node_id=node_id, cpu=cpu, memory=memory, recent_samples=samples)


def write_load_forecast_model(tmp_path, sequence_length=5, hidden_size=4, training_example_count=30):
    """Builds a real (randomly-initialized, not necessarily converged) LoadForecastLSTM and writes it in
    the exact metadata+weights shape LoadForecastStore expects — mirrors what
    train_load_forecast_model.py writes, without paying for a full training run in this test."""
    import torch

    torch.manual_seed(0)
    model = LoadForecastLSTM(hidden_size=hidden_size)
    weights_path = tmp_path / "load_forecast_weights.pt"
    torch.save(model.state_dict(), str(weights_path))

    metadata_path = tmp_path / "load_forecast_model.json"
    metadata_path.write_text(json.dumps({
        "sequenceLength": sequence_length,
        "hiddenSize": hidden_size,
        "horizonSeconds": 300.0,
        "featureMean": [0.0] * 5,
        "featureStd": [1.0] * 5,
        "trainingExampleCount": training_example_count,
        "trainedAtEpochMillis": 1_700_000_000_000,
    }))
    return str(metadata_path), str(weights_path)


def test_no_model_file_reports_untrained_honestly(tmp_path):
    store = ModelStore(model_path=str(tmp_path / "does_not_exist.json"))
    servicer = PredictorServiceServicer(store)

    response = servicer.GetPrediction(make_request(), None)

    assert response.model_trained is False
    assert response.failure_probability == 0.0
    assert response.training_example_count == 0
    assert "UNTRAINED" in response.recommendation
    # Stage I: the load-forecast model is independent and, with no model file of its own either, must
    # be reported untrained too — never a fabricated forecast.
    assert response.load_model_trained is False
    assert response.predicted_memory_percent == 0.0


def test_a_real_model_file_produces_a_trained_response(tmp_path):
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "modelType": "logistic_regression",
        "weights": [0.1] * N_FEATURES,
        "bias": -0.5,
        "featureMean": [10.0] * N_FEATURES,
        "featureStd": [5.0] * N_FEATURES,
        "trainingExampleCount": 42,
        "trainedAtEpochMillis": 1_700_000_000_000,
    }))
    store = ModelStore(model_path=str(model_path))
    servicer = PredictorServiceServicer(store)

    response = servicer.GetPrediction(make_request(), None)

    assert response.model_trained is True
    assert response.training_example_count == 42
    assert response.model_type == "logistic_regression"
    assert 0.0 <= response.failure_probability <= 1.0
    assert response.recommendation in ("HEALTHY", "AT_RISK")


def test_a_nan_cpu_in_the_request_falls_back_to_untrained_rather_than_a_confident_verdict(tmp_path):
    """Stage BB: a NaN request.cpu (a proto float field gRPC never range-checks) previously flowed
    through, unguarded, to a NaN failure_probability that `NaN >= 0.5` evaluates False for — a
    confidently-wrong "HEALTHY" instead of an honest untrained response. Also exercises the
    predicted_load truthiness fix: bool(nan) is True in Python, so the old `request.cpu else 0.0`
    fallback would still have leaked NaN into predicted_load even with model_trained now correct."""
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "modelType": "logistic_regression",
        "weights": [0.1] * N_FEATURES,
        "bias": -0.5,
        "featureMean": [10.0] * N_FEATURES,
        "featureStd": [5.0] * N_FEATURES,
        "trainingExampleCount": 42,
        "trainedAtEpochMillis": 1_700_000_000_000,
    }))
    store = ModelStore(model_path=str(model_path))
    servicer = PredictorServiceServicer(store)

    response = servicer.GetPrediction(make_request(cpu=float("nan"), sequence_length=1), None)

    assert response.model_trained is False
    assert response.recommendation.startswith("UNTRAINED")
    assert response.predicted_load == 0.0


def test_a_model_file_with_no_modelType_defaults_to_logistic_regression(tmp_path):
    """Backward compatibility: a model file written before Stage H has no modelType field at all."""
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "weights": [0.1] * N_FEATURES,
        "bias": -0.5,
        "featureMean": [10.0] * N_FEATURES,
        "featureStd": [5.0] * N_FEATURES,
        "trainingExampleCount": 42,
        "trainedAtEpochMillis": 1_700_000_000_000,
    }))
    store = ModelStore(model_path=str(model_path))

    response = PredictorServiceServicer(store).GetPrediction(make_request(), None)

    assert response.model_trained is True
    assert response.model_type == "logistic_regression"


def test_an_xgboost_model_file_produces_a_trained_response(tmp_path):
    """Stage H: the xgboost dispatch path in ModelStore, via a real trained booster (not a mock)."""
    import numpy as np

    rng = np.random.default_rng(0)
    X = rng.normal(size=(40, N_FEATURES))
    y = (rng.random(40) >= 0.5).astype(np.float64)
    booster = train_xgboost(X, y, n_estimators=10, max_depth=2)
    booster_path = tmp_path / "xgboost_booster.json"
    booster.save_model(str(booster_path))

    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "modelType": "xgboost",
        "boosterPath": "xgboost_booster.json",
        "featureMean": [0.0] * N_FEATURES,
        "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 40,
        "trainedAtEpochMillis": 1_700_000_000_000,
    }))
    store = ModelStore(model_path=str(model_path))
    servicer = PredictorServiceServicer(store)

    response = servicer.GetPrediction(make_request(), None)

    assert response.model_trained is True
    assert response.model_type == "xgboost"
    assert response.training_example_count == 40
    assert 0.0 <= response.failure_probability <= 1.0


def test_an_xgboost_model_with_a_missing_sidecar_keeps_serving_the_previous_model(tmp_path):
    """Malformed/missing-sidecar handling follows the existing rule: warn, keep the previous model,
    never crash the predictor process."""
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "modelType": "logistic_regression",
        "weights": [0.1] * N_FEATURES, "bias": 0.0,
        "featureMean": [0.0] * N_FEATURES, "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 5, "trainedAtEpochMillis": 1,
    }))
    store = ModelStore(model_path=str(model_path))
    first = store.model_type()
    assert first == "logistic_regression"

    store._last_checked = 0  # noqa: SLF001 — test-only bypass of the cheap-polling throttle
    model_path.write_text(json.dumps({
        "modelType": "xgboost",
        "boosterPath": "does_not_exist.json",
        "featureMean": [0.0] * N_FEATURES, "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 99, "trainedAtEpochMillis": 2,
    }))
    new_time = os.path.getmtime(model_path) + 1
    os.utime(model_path, (new_time, new_time))

    _, trained, n = store.predict([])
    assert trained is True
    assert n == 5, "a missing sidecar must not silently switch to an untrained/different model"
    assert store.model_type() == "logistic_regression"


def test_a_model_file_that_is_valid_json_but_not_an_object_reverts_to_untrained(tmp_path):
    """Stage Z: valid JSON that isn't an object (e.g. from a non-atomic partial overwrite) previously
    reached raw.get(...) and raised an uncaught AttributeError, breaking every subsequent prediction
    call. Must degrade to honestly untrained instead."""
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps([1, 2, 3]))  # a JSON array, not an object

    store = ModelStore(model_path=str(model_path))

    probability, trained, n = store.predict([])
    assert trained is False
    assert n == 0


def test_a_model_file_that_becomes_non_dict_keeps_serving_the_previous_model(tmp_path):
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "weights": [0.1] * N_FEATURES, "bias": 0.0,
        "featureMean": [0.0] * N_FEATURES, "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 7, "trainedAtEpochMillis": 1,
    }))
    store = ModelStore(model_path=str(model_path))
    assert store.predict([])[2] == 7

    store._last_checked = 0  # noqa: SLF001 — test-only bypass of the cheap-polling throttle
    model_path.write_text(json.dumps("just a string, not an object"))
    new_time = os.path.getmtime(model_path) + 1
    os.utime(model_path, (new_time, new_time))

    _, trained, n = store.predict([])
    assert trained is True
    assert n == 7, "a non-dict overwrite must not crash or silently switch away from the last-good model"


def test_model_hot_reloads_when_the_file_changes(tmp_path):
    model_path = tmp_path / "risk_model.json"
    model_path.write_text(json.dumps({
        "weights": [0.0] * N_FEATURES, "bias": 0.0,
        "featureMean": [0.0] * N_FEATURES, "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 10, "trainedAtEpochMillis": 1,
    }))
    store = ModelStore(model_path=str(model_path))
    servicer = PredictorServiceServicer(store)
    first = servicer.GetPrediction(make_request(), None)
    assert first.training_example_count == 10

    # Force the reload check to actually re-stat the file regardless of the polling interval.
    store._last_checked = 0  # noqa: SLF001 — test-only bypass of the cheap-polling throttle
    model_path.write_text(json.dumps({
        "weights": [0.0] * N_FEATURES, "bias": 0.0,
        "featureMean": [0.0] * N_FEATURES, "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 99, "trainedAtEpochMillis": 2,
    }))
    # Ensure the mtime actually advances on filesystems with coarse timestamp resolution.
    new_time = os.path.getmtime(model_path) + 1
    os.utime(model_path, (new_time, new_time))

    second = servicer.GetPrediction(make_request(), None)

    assert second.training_example_count == 99


# ── Stage I: load-forecast dispatch, independent of the failure classifier ──────────

def test_no_load_forecast_model_falls_back_to_the_instantaneous_cpu_stub(tmp_path):
    """A trained failure classifier with no load-forecast model at all is a normal, expected state —
    predicted_load must fall back to the original request.cpu-based stub, not crash or fabricate."""
    risk_model_path = tmp_path / "risk_model.json"
    risk_model_path.write_text(json.dumps({
        "modelType": "logistic_regression",
        "weights": [0.0] * N_FEATURES, "bias": 0.0,
        "featureMean": [0.0] * N_FEATURES, "featureStd": [1.0] * N_FEATURES,
        "trainingExampleCount": 10, "trainedAtEpochMillis": 1,
    }))
    model_store = ModelStore(model_path=str(risk_model_path))
    load_store = LoadForecastStore(metadata_path=str(tmp_path / "does_not_exist.json"),
                                   weights_path=str(tmp_path / "does_not_exist.pt"))
    servicer = PredictorServiceServicer(model_store, load_store)

    response = servicer.GetPrediction(make_request(cpu=64.0), None)

    assert response.model_trained is True  # the OTHER model is trained
    assert response.load_model_trained is False
    assert response.predicted_memory_percent == 0.0
    assert abs(response.predicted_load - 0.64) < 1e-5  # falls back to request.cpu / 100.0 (float32 on the wire)


def test_a_real_load_forecast_model_produces_a_trained_response(tmp_path):
    """The xgboost-analogue for the LSTM path: a real trained (if not necessarily converged) model
    file, not a mock, drives the response."""
    metadata_path, weights_path = write_load_forecast_model(tmp_path, sequence_length=5, training_example_count=77)
    load_store = LoadForecastStore(metadata_path=metadata_path, weights_path=weights_path)
    model_store = ModelStore(model_path=str(tmp_path / "does_not_exist.json"))  # failure classifier untrained
    servicer = PredictorServiceServicer(model_store, load_store)

    response = servicer.GetPrediction(make_request(sequence_length=5), None)

    assert response.load_model_trained is True
    assert response.load_model_training_example_count == 77
    # predicted_load now comes from the LSTM forecast, and predicted_memory_percent is populated too —
    # both real (finite) numbers, not the untrained-path's fixed zeros.
    import math
    assert math.isfinite(response.predicted_load)
    assert math.isfinite(response.predicted_memory_percent)


def test_a_sequence_shorter_than_the_models_required_length_reports_untrained(tmp_path):
    metadata_path, weights_path = write_load_forecast_model(tmp_path, sequence_length=10)
    load_store = LoadForecastStore(metadata_path=metadata_path, weights_path=weights_path)
    model_store = ModelStore(model_path=str(tmp_path / "does_not_exist.json"))
    servicer = PredictorServiceServicer(model_store, load_store)

    # Only 3 samples on the wire; the model needs 10.
    response = servicer.GetPrediction(make_request(sequence_length=3), None)

    assert response.load_model_trained is False
    assert response.predicted_memory_percent == 0.0


def test_an_empty_load_forecast_weights_file_keeps_serving_the_previous_model(tmp_path):
    """Stage Z: an EMPTY .pt file raises EOFError from torch.load — previously uncaught, breaking
    every subsequent prediction call until the file was manually fixed."""
    metadata_path, weights_path = write_load_forecast_model(tmp_path, sequence_length=5, training_example_count=42)
    load_store = LoadForecastStore(metadata_path=metadata_path, weights_path=weights_path)
    assert load_store.predict([{"cpu_percent": 1, "memory_percent": 1, "battery_percent": 1,
                                 "on_ac_power": 1, "previous_rtt_seconds": 0.0}] * 5)[3] == 42

    load_store._last_checked = 0  # noqa: SLF001 — test-only bypass of the cheap-polling throttle
    with open(weights_path, "wb"):
        pass  # truncate to zero bytes
    new_time = os.path.getmtime(metadata_path) + 1
    os.utime(metadata_path, (new_time, new_time))

    result = load_store.predict([{"cpu_percent": 1, "memory_percent": 1, "battery_percent": 1,
                                   "on_ac_power": 1, "previous_rtt_seconds": 0.0}] * 5)
    assert result[2] is True, "an empty weights file must not silently degrade the last-good model"
    assert result[3] == 42


def test_a_load_forecast_weights_file_with_garbage_bytes_keeps_serving_the_previous_model(tmp_path):
    """Stage Z: GARBAGE (non-empty, non-pickle) bytes raise IndexError from torch.load — same
    previously-uncaught crash class as the empty-file case above."""
    metadata_path, weights_path = write_load_forecast_model(tmp_path, sequence_length=5, training_example_count=13)
    load_store = LoadForecastStore(metadata_path=metadata_path, weights_path=weights_path)
    sample = [{"cpu_percent": 1, "memory_percent": 1, "battery_percent": 1,
               "on_ac_power": 1, "previous_rtt_seconds": 0.0}] * 5
    assert load_store.predict(sample)[3] == 13

    load_store._last_checked = 0  # noqa: SLF001 — test-only bypass of the cheap-polling throttle
    with open(weights_path, "wb") as f:
        f.write(b"\x00\x01\x02not-a-real-pytorch-checkpoint\xff\xfe")
    new_time = os.path.getmtime(metadata_path) + 1
    os.utime(metadata_path, (new_time, new_time))

    result = load_store.predict(sample)
    assert result[2] is True, "garbage weight bytes must not silently degrade the last-good model"
    assert result[3] == 13


def test_load_forecast_model_hot_reloads_when_the_file_changes(tmp_path):
    metadata_path, weights_path = write_load_forecast_model(tmp_path, sequence_length=5, training_example_count=10)
    load_store = LoadForecastStore(metadata_path=metadata_path, weights_path=weights_path)
    model_store = ModelStore(model_path=str(tmp_path / "does_not_exist.json"))
    servicer = PredictorServiceServicer(model_store, load_store)

    first = servicer.GetPrediction(make_request(sequence_length=5), None)
    assert first.load_model_training_example_count == 10

    load_store._last_checked = 0  # noqa: SLF001 — test-only bypass of the cheap-polling throttle
    write_load_forecast_model(tmp_path, sequence_length=5, training_example_count=55)
    new_time = os.path.getmtime(metadata_path) + 1
    os.utime(metadata_path, (new_time, new_time))

    second = servicer.GetPrediction(make_request(sequence_length=5), None)
    assert second.load_model_training_example_count == 55
