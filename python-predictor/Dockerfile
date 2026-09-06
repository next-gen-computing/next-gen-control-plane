FROM python:3.11-slim

WORKDIR /app

# libgomp1: xgboost's manylinux wheel dynamically links against libgomp for its OpenMP-based
# multithreading. python:3.11-slim doesn't include it by default (unlike the full python:3.11 image),
# so importing xgboost inside this container would otherwise fail with an ImportError/OSError that
# never surfaced during earlier development (no Docker daemon was available then to actually build and
# run this image against a real python:3.11-slim base — see requirements.txt's own xgboost comment,
# written when this was still a named, unverified risk rather than a confirmed one).
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

# Install dependencies. torch (Stage I) is installed first from its CPU-only wheel index — the default
# PyPI index resolves plain `torch` to a much larger CUDA-bundled build that's irrelevant to this
# CPU-only container; see requirements.txt's Stage I comment. The version here must stay in sync with
# requirements.txt's own torch== pin.
COPY python-predictor/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir torch==2.13.0 --index-url https://download.pytorch.org/whl/cpu && \
    pip install --no-cache-dir -r requirements.txt

# Copy proto and generate Python stubs
COPY proto/control_plane.proto /app/proto/control_plane.proto
RUN python -m grpc_tools.protoc \
    -I/app/proto \
    --python_out=/app \
    --grpc_python_out=/app \
    /app/proto/control_plane.proto

# Copy application code. auto_retrain.py is a real, previously-missing entry here — predictor_service.py
# imports it directly ("from auto_retrain import AutoRetrainer"), so its absence crashed the container
# with ModuleNotFoundError on every startup, invisible to any test that runs predictor_service.py
# directly from the full source checkout rather than through this Dockerfile's restrictive COPY list.
# The four trace-import/enrichment scripts are copied too so `docker compose exec predictor python
# <script>.py` (the documented operator workflow — see CHANGELOG.md) actually has something to run
# inside the container, not just on a host checkout.
COPY python-predictor/predictor_service.py /app/predictor_service.py
COPY python-predictor/features.py /app/features.py
COPY python-predictor/model_store.py /app/model_store.py
COPY python-predictor/auto_retrain.py /app/auto_retrain.py
COPY python-predictor/train_risk_model.py /app/train_risk_model.py
COPY python-predictor/import_external_trace.py /app/import_external_trace.py
COPY python-predictor/import_alibaba_pai_trace.py /app/import_alibaba_pai_trace.py
COPY python-predictor/import_alibaba_2018_trace.py /app/import_alibaba_2018_trace.py
COPY python-predictor/import_google_cluster_trace.py /app/import_google_cluster_trace.py
COPY python-predictor/enrich_alibaba_memory.py /app/enrich_alibaba_memory.py
COPY python-predictor/load_forecast_model.py /app/load_forecast_model.py
COPY python-predictor/train_load_forecast_model.py /app/train_load_forecast_model.py
COPY python-predictor/load_forecast_store.py /app/load_forecast_store.py

# The trained model lives on a volume (see docker-compose.yml) so it survives a container recreate and
# so train_risk_model.py — normally run via `docker compose exec predictor ...` — writes somewhere the
# running server will actually see. This just ensures the directory exists before the first mount.
RUN mkdir -p /app/model

EXPOSE 50052 9091

CMD ["python", "predictor_service.py"]
