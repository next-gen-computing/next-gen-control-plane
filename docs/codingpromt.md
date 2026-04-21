You are an expert senior distributed-systems engineer working on the "Next-Gen Control Plane" BE-CSE major project.

PROJECT RULES (NEVER VIOLATE):
- Use ONLY real OS readings (Java: com.sun.management.OperatingSystemMXBean or ManagementFactory; Python: psutil). Never generate random, fake, or placeholder numbers for CPU, memory, or any metric.
- If no reading is available yet, return 0.0 or "N/A" — never invent values.
- All communication must strictly follow the proto definitions in proto/control_plane.proto.
- Folder structure is fixed: proto/, java-control-plane/, python-predictor/, scripts/, docs/.
- Code must be production-grade, clean, with proper logging (SLF4J in Java, logging in Python).
- Every change must compile and run in Docker.

Full architecture is in docs/ARCHITECTURE.md. Read it first.

Current Phase-1 scope only:
- 3 real NodeAgents registering + sending real heartbeats every 2s.
- ControlPlane with in-memory registry + round-robin scheduler + heartbeat timeout detection.
- Python predictor stub with gRPC and Prometheus metrics.
- Docker Compose cluster.
- Real integration test script.
- Terminal-like monitor script showing live node health (real values only).

You are now in Google Antigravity IDE with full repo context + code-assistant MCP server enabled.
When I give you an issue, implement it perfectly, edit only the necessary files, run Maven/Docker commands if needed, and confirm with test output.

Begin every response with "✅ PHASE-1 IMPLEMENTATION STARTED" and end with "✅ READY FOR NEXT ISSUE".

Issue 5: [Phase-1] Setup monorepo structure & shared proto (do this first if not done)

textImplement Issue 5 completely.
Ensure the exact folder structure exists.
proto/control_plane.proto must match the one already committed (copy it exactly if missing).
Create or update DEVELOPMENT.md with full setup instructions.
Create docker-compose.yml with 3 nodes + control-plane + predictor services using the networks I defined earlier.
Commit only after everything works with `docker compose up --build`.

Issue 4: [Phase-1] Implement Java NodeAgent + gRPC registration & heartbeat
textImplement Issue 4.

In java-control-plane/src/main/java:
- Create NodeAgent.java that reads real CPU and memory using OperatingSystemMXBean (no random values).
- On startup: call RegisterNode to ControlPlane (use CONTROL_PLANE_HOST env var).
- Every exactly 2 seconds: send real heartbeat (cpu and memory from OS).
- Expose Prometheus metrics (node_heartbeat_count_total, node_cpu_usage).
- Use the generated gRPC stubs from the proto.
- Make it run as a Docker service using the NODE_ID environment variable.
Test by running docker compose up and show logs of successful registration + first 3 heartbeats

Issue 3: [Phase-1] Implement Java ControlPlane server + round-robin scheduler
textImplement Issue 3.

In java-control-plane:
- Create ControlPlaneServer.java that implements all 4 RPC methods from the proto.
- Maintain thread-safe in-memory node registry.
- Implement heartbeat timeout (6 seconds = suspected_dead, log only).
- Implement simple round-robin scheduler for SubmitTask.
- Call the Python predictor via gRPC (port 50052) for GetPrediction (stub for now).
- Expose Prometheus metrics.
- Start gRPC server on port 50051.
After implementation, run docker compose up and show logs of 3 nodes registering + one successful SubmitTask.

Issue 1: [Phase-1] Implement Python Predictive stub + gRPC + Prometheus metrics
textImplement Issue 1.

In python-predictor:
- Create predictor_service.py that implements the gRPC server on port 50052.
- Add GetPrediction method that returns structured dummy prediction (predicted_load and failure_probability as floats — no random values, just fixed 0.45 and 0.12 for Phase-1).
- Add Prometheus metrics endpoint on port 9091.
- Use the generated Python stubs from proto.
- Dockerfile must be present and work.
After implementation, run docker compose up and confirm ControlPlane can call the predictor successfully.

Issue 2: [Phase-1] Create Docker Compose + start scripts + integration test
textImplement Issue 2 (last one).

- Finalize docker-compose.yml (already partially created).
- Create scripts/start-cluster.sh and scripts/monitor.sh (terminal-like live table using watch + curl on metrics or a simple Python Rich dashboard that queries GetNodes).
- Create scripts/integration-test.py that:
  1. Starts the cluster (or assumes it's running).
  2. Registers 3 nodes.
  3. Sends 5 heartbeats.
  4. Submits a task.
  5. Shows live monitor output with real node health.
- Update README.md with one-command startup and test instructions.
- Run the integration test and show full passing output.