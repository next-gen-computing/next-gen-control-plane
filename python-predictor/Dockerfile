FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY python-predictor/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Copy proto and generate Python stubs
COPY proto/control_plane.proto /app/proto/control_plane.proto
RUN python -m grpc_tools.protoc \
    -I/app/proto \
    --python_out=/app \
    --grpc_python_out=/app \
    /app/proto/control_plane.proto

# Copy application code
COPY python-predictor/predictor_service.py /app/predictor_service.py

EXPOSE 50052 9091

CMD ["python", "predictor_service.py"]
