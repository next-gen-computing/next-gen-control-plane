# Phase-1 Architecture – Next-Gen Control Plane

## High-Level Overview (Phase-1 Scope)
A 3-node simulated distributed cluster running in Docker Compose.
- All nodes use **real** OS metrics (CPU % and memory % from OperatingSystemMXBean / psutil).
- No random or fake values anywhere.
- Communication is purely gRPC (proto-defined contracts).
- Control Plane maintains live node registry and round-robin scheduler.
- Python predictor is a stub that returns real prediction placeholders (hard-coded for Phase-1; real ML in Phase-3).

## Component Diagram (Text Version)

User / Integration Test
↓ (gRPC)
ControlPlane (Java, port 50051)
├── Node Registry (in-memory, thread-safe)
├── Round-Robin Scheduler
├── Heartbeat Monitor (6s timeout → mark suspected_dead)
└── Prometheus metrics endpoint
↓ (gRPC)
NodeAgent (Java, 3 instances: node1, node2, node3)
├── Registers on startup
├── Sends real CPU/memory heartbeat every 2s
├── Listens for tasks
└── Exposes Prometheus metrics
↓ (gRPC)
Predictor (Python, port 50052 + 9091)
├── Dummy GetPrediction (returns structured prediction)
└── Prometheus /metrics


text## Data Flow (Real Readings Only)
1. NodeAgent starts → calls RegisterNode (real hostname, IP from Docker).
2. Every 2s: reads **real** CPU % and Memory % via OS MXBean → SendHeartbeat.
3. ControlPlane stores nodes and validates heartbeats.
4. On SubmitTask → ControlPlane calls Predictor → applies round-robin → forwards to best node.
5. Terminal monitor (scripts/monitor.sh) shows live table of nodes + last heartbeat + real CPU/memory.

## Technology Stack (Phase-1)
- Java 21 + gRPC + Prometheus simpleclient (Control Plane & NodeAgent)
- Python 3.11 + grpcio + prometheus-client (Predictor)
- Docker Compose (single host simulation)
- Maven + Poetry
- Real OS metrics only (no Math.random, no fake data)

## Non-Functional Requirements for Phase-1
- All values must be real OS readings or empty until first heartbeat.
- Zero random values in any log, metric, or response.
- Cluster must start with `docker compose up --build`.
- Integration test must pass end-to-end.
- Terminal-like monitor must show live node health.
