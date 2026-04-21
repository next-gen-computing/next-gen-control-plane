"""
Next-Gen Control Plane — Python Predictor Service (Phase-1 Stub)

gRPC server on port 50052 implementing PredictorService.GetPrediction.
Returns fixed prediction values (no random/fake data).
Prometheus metrics on port 9091.
"""

import logging
import time
from concurrent import futures

import grpc
from prometheus_client import Counter, Histogram, start_http_server

# ── Generated stubs (created at Docker build time) ────
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


class PredictorServiceServicer(pb2_grpc.PredictorServiceServicer):
    """Phase-1 stub: returns hard-coded prediction values (no random)."""

    def GetPrediction(self, request, context):
        start = time.time()
        PREDICTION_REQUESTS.inc()

        LOG.info(
            "🔮 GetPrediction: node_id=%s, cpu=%.1f%%, mem=%.1f%%",
            request.node_id,
            request.cpu,
            request.memory,
        )

        # Phase-1: fixed values, real ML will replace this in Phase-3
        response = pb2.PredictionResponse(
            predicted_load=0.45,
            failure_probability=0.12,
            recommendation="HEALTHY — Phase-1 stub prediction",
        )

        elapsed = time.time() - start
        PREDICTION_LATENCY.observe(elapsed)
        LOG.info("✅ Prediction returned in %.3fs: load=%.2f, fail_prob=%.2f",
                 elapsed, response.predicted_load, response.failure_probability)

        return response


def serve():
    # Start Prometheus metrics endpoint on port 9091
    start_http_server(9091)
    LOG.info("📊 Prometheus metrics server started on port 9091")

    # Start gRPC server on port 50052
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    pb2_grpc.add_PredictorServiceServicer_to_server(
        PredictorServiceServicer(), server
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
