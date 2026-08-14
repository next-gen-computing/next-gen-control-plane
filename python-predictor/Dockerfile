FROM python:3.11-slim

WORKDIR /app

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

# Copy application code
COPY python-predictor/predictor_service.py /app/predictor_service.py
COPY python-predictor/features.py /app/features.py
COPY python-predictor/model_store.py /app/model_store.py
COPY python-predictor/train_risk_model.py /app/train_risk_model.py
COPY python-predictor/import_external_trace.py /app/import_external_trace.py
COPY python-predictor/load_forecast_model.py /app/load_forecast_model.py
COPY python-predictor/train_load_forecast_model.py /app/train_load_forecast_model.py
COPY python-predictor/load_forecast_store.py /app/load_forecast_store.py

# The trained model lives on a volume (see docker-compose.yml) so it survives a container recreate and
# so train_risk_model.py — normally run via `docker compose exec predictor ...` — writes somewhere the
# running server will actually see. This just ensures the directory exists before the first mount.
RUN mkdir -p /app/model

EXPOSE 50052 9091

CMD ["python", "predictor_service.py"]
