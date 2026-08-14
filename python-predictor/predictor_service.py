"""
Next-Gen Control Plane — Python Predictor Service (Phase-3: real trained models)

gRPC server on port 50052 implementing PredictorService.GetPrediction. Serves two independent, real
predictions:
  - failure classification, from whatever train_risk_model.py most recently wrote to
    model/risk_model.json (ModelStore — logistic_regression or xgboost, Stage H)
  - load forecasting, from whatever train_load_forecast_model.py most recently wrote to
    model/load_forecast_model.json + model/load_forecast_weights.pt (LoadForecastStore — an LSTM,
    Stage I)
Both hot-reload by mtime polling and honestly report their own model_trained=False (never a fabricated
number) whenever no model has been trained yet — one being trained and the other not is a normal,
expected state, not an error. Matches the discipline MLRiskScorer.java (the Java caller) already expects
and falls back on.

Prometheus metrics on port 9091.
"""

import logging
import time
from concurrent import futures

import grpc
from prometheus_client import Counter, Gauge, Histogram, start_http_server

from load_forecast_store import LoadForecastStore
from model_store import ModelStore

# ── Generated stubs (created at Docker build time, or locally via grpc_tools.protoc) ────
import control_plane_pb2 as pb2
import control_plane_pb2_grpc as pb2_grpc

# ── Logging ───────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%H:%M:%S",
)
LOG = logging.getLogger("predictor")

# ── Prometheus Metrics ────────────────────────────────
PREDICTION_REQUESTS = Counter(
    "prediction_requests_total",
    "Total GetPrediction requests received",
)
PREDICTION_LATENCY = Histogram(
    "prediction_latency_seconds",
    "Latency of GetPrediction calls",
)
MODEL_TRAINED = Gauge(
    "predictor_model_trained",
    "1 when a real trained model is loaded, 0 when serving the untrained fallback",
)
MODEL_TRAINING_EXAMPLES = Gauge(
    "predictor_model_training_examples",
    "Number of examples the currently-loaded model was trained on",
)
LOAD_MODEL_TRAINED = Gauge(
    "predictor_load_model_trained",
    "1 when a real trained LSTM load-forecast model is loaded, 0 when none exists yet",
)
LOAD_MODEL_TRAINING_EXAMPLES = Gauge(
    "predictor_load_model_training_examples",
    "Number of (sequence -> future reading) pairs the currently-loaded load-forecast model was trained on",
)


def _trend_sample_to_dict(sample) -> dict:
    """Mirrors the exact key names RiskOutcomeLogger/RiskSnapshotLogger write on the Java side, so the
    same features.extract_features(...) works identically whether called from training or serving."""
    return {
        "recordedAtMillis": sample.recorded_at_epoch_millis,
        "cpuPercent": sample.cpu_percent,
        "cpuAvailable": sample.cpu_available,
        "memoryPercent": sample.memory_percent,
        "memoryAvailable": sample.memory_available,
        "batteryPercent": sample.battery_percent,
        "batteryAvailable": sample.battery_available,
        "charging": sample.charging,
        "chargingKnown": sample.charging_known,
        "onAcPower": sample.on_ac_power,
        "onAcPowerKnown": sample.on_ac_power_known,
        "previousRttSeconds": sample.previous_rtt_seconds,
        "previousRttAvailable": sample.previous_rtt_available,
    }


class PredictorServiceServicer(pb2_grpc.PredictorServiceServicer):
    """Phase-3: serves real predictions from ModelStore (failure classification) and, independently,
    LoadForecastStore (load forecasting) — honest model_trained=False on either until each has a real
    model of its own."""

    def __init__(self, model_store: ModelStore, load_forecast_store: LoadForecastStore | None = None):
        self._model_store = model_store
        self._load_forecast_store = load_forecast_store or LoadForecastStore()

    def GetPrediction(self, request, context):
        start = time.time()
        PREDICTION_REQUESTS.inc()

        LOG.info(
            "🔮 GetPrediction: node_id=%s, cpu=%.1f%%, mem=%.1f%%, samples=%d",
            request.node_id, request.cpu, request.memory, len(request.recent_samples),
        )

        recent_samples = [_trend_sample_to_dict(s) for s in request.recent_samples]
        failure_probability, model_trained, training_example_count = self._model_store.predict(recent_samples)
        model_type = self._model_store.model_type() or ""

        predicted_cpu, predicted_memory, load_model_trained, load_training_example_count = (
            self._load_forecast_store.predict(recent_samples)
        )
        horizon_seconds = (self._load_forecast_store.horizon_seconds() or 0.0) if load_model_trained else 0.0

        MODEL_TRAINED.set(1 if model_trained else 0)
        MODEL_TRAINING_EXAMPLES.set(training_example_count)
        LOAD_MODEL_TRAINED.set(1 if load_model_trained else 0)
        LOAD_MODEL_TRAINING_EXAMPLES.set(load_training_example_count)

        if model_trained:
            recommendation = "AT_RISK" if failure_probability >= 0.5 else "HEALTHY"
        else:
            recommendation = "UNTRAINED — no model has been trained yet (see train_risk_model.py)"

        # predicted_load is the LSTM's real forecast once trained; falls back to the original
        # instantaneous-cpu stub (never a crash, never a fabricated forecast) when it isn't.
        predicted_load = (predicted_cpu / 100.0) if load_model_trained else (request.cpu / 100.0 if request.cpu else 0.0)

        response = pb2.PredictionResponse(
            predicted_load=predicted_load,
            failure_probability=failure_probability,
            recommendation=recommendation,
            model_trained=model_trained,
            training_example_count=training_example_count,
            model_type=model_type,
            predicted_memory_percent=predicted_memory if load_model_trained else 0.0,
            load_model_trained=load_model_trained,
            load_model_training_example_count=load_training_example_count,
            horizon_seconds=horizon_seconds,
        )

        elapsed = time.time() - start
        PREDICTION_LATENCY.observe(elapsed)
        LOG.info("✅ Prediction returned in %.3fs: trained=%s, fail_prob=%.2f (n=%d); "
                 "load_trained=%s, predicted_cpu=%.1f%%, predicted_mem=%.1f%% (n=%d)",
                 elapsed, model_trained, failure_probability, training_example_count,
                 load_model_trained, predicted_cpu, predicted_memory, load_training_example_count)

        return response


def serve():
    # Start Prometheus metrics endpoint on port 9091
    start_http_server(9091)
    LOG.info("📊 Prometheus metrics server started on port 9091")

    model_store = ModelStore()
    load_forecast_store = LoadForecastStore()

    # Start gRPC server on port 50052
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    pb2_grpc.add_PredictorServiceServicer_to_server(
        PredictorServiceServicer(model_store, load_forecast_store), server
    )
    server.add_insecure_port("[::]:50052")
    server.start()

    LOG.info("══════════════════════════════════════════════════")
    LOG.info("  🐍 Predictor gRPC server RUNNING on port 50052  ")
    LOG.info("══════════════════════════════════════════════════")

    try:
        server.wait_for_termination()
    except KeyboardInterrupt:
        LOG.info("Predictor server shutting down...")
        server.stop(grace=5)


if __name__ == "__main__":
    serve()
