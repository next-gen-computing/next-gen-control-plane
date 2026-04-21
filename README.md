# Next-Gen Control Plane

**Production-grade distributed control plane with predictive scheduling under failure conditions**  
BE-CSE Major Project | Team of 3 | April 2026

[![Java 21](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org)
[![Python 3.11](https://img.shields.io/badge/Python-3.11-blue)](https://python.org)
[![gRPC](https://img.shields.io/badge/gRPC-1.68-green)](https://grpc.io)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://docs.docker.com/compose/)

## Architecture

```
User / Integration Test
    ↓ (gRPC)
ControlPlane (Java, port 50051)
├── Node Registry (in-memory, ConcurrentHashMap)
├── Round-Robin Scheduler (AtomicInteger)
├── Heartbeat Monitor (6s timeout → SUSPECTED_DEAD)
└── Prometheus metrics (:9090)
    ↓ (gRPC)
NodeAgent (Java, ×3: node1, node2, node3)
├── Registers on startup (with retry)
├── Real CPU/Memory heartbeat every 2s (OperatingSystemMXBean)
└── Prometheus metrics (:9090 per container)
    ↓ (gRPC)
Predictor (Python, port 50052)
├── GetPrediction stub (0.45, 0.12 for Phase-1)
└── Prometheus /metrics (:9091)
```

## Quick Start (One Command)

```bash
docker compose up --build
```

This starts 5 services:
| Service | Role | Ports |
|---------|------|-------|
| `control-plane` | gRPC server + scheduler | 50051 (gRPC), 9090 (metrics) |
| `node1`, `node2`, `node3` | Node agents with real OS metrics | Internal only |
| `predictor` | Python prediction stub | 50052 (gRPC), 9091 (metrics) |

## Monitoring

### Live Terminal Monitor (Rich dashboard)
```bash
pip install grpcio grpcio-tools protobuf rich
python scripts/monitor.py
```

### View Logs
```bash
docker compose logs -f                    # All services
docker compose logs -f control-plane      # ControlPlane only
docker compose logs -f node1 node2 node3  # All agents
```

### Prometheus Metrics
```bash
curl http://localhost:9090/metrics   # ControlPlane
curl http://localhost:9091/metrics   # Predictor
```

## Integration Test

```bash
# 1. Start the cluster
docker compose up --build -d

# 2. Wait ~15 seconds for nodes to register and heartbeats to flow

# 3. Run the test
pip install grpcio grpcio-tools protobuf
python scripts/integration-test.py
```

The test verifies:
- ✅ 3 nodes register successfully
- ✅ Heartbeats flow with real OS metrics
- ✅ Tasks get round-robin scheduled
- ✅ Predictor returns expected values (0.45, 0.12)

## Stop the Cluster

```bash
docker compose down
```

## Project Structure

```
next-gen-control-plane/
├── proto/
│   └── control_plane.proto         # Shared gRPC contract
├── java-control-plane/
│   ├── pom.xml                     # Maven + gRPC + Prometheus
│   ├── Dockerfile                  # Multi-stage build
│   └── src/main/java/com/nextgen/
│       ├── Main.java               # ROLE-based entry point
│       ├── controlplane/
│       │   ├── ControlPlaneServer.java
│       │   ├── ControlPlaneServiceImpl.java
│       │   ├── HeartbeatMonitor.java
│       │   └── NodeRecord.java
│       └── agent/
│           └── NodeAgent.java      # Real OS metrics agent
├── python-predictor/
│   ├── predictor_service.py        # gRPC server (Phase-1 stub)
│   ├── requirements.txt
│   └── Dockerfile
├── scripts/
│   ├── start-cluster.sh            # One-command startup
│   ├── monitor.py                  # Rich live dashboard
│   ├── monitor.sh                  # Shell wrapper for monitor
│   └── integration-test.py         # E2E verification
├── docs/
│   ├── ARCHITECTURE.md
│   └── codingpromt.md
├── docker-compose.yml
├── DEVELOPMENT.md
└── README.md
```

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Control Plane & Agents | Java 21, gRPC 1.68, Prometheus simpleclient |
| Predictor | Python 3.11, grpcio, prometheus-client |
| Communication | Protocol Buffers 3 (proto3) |
| Deployment | Docker Compose |
| OS Metrics | `com.sun.management.OperatingSystemMXBean` (real readings) |
| Logging | SLF4J + Logback (Java), `logging` module (Python) |

## Phase Roadmap

- **Phase-1** (Current): 3-node cluster, real heartbeats, round-robin, predictor stub
- **Phase-2**: Consensus protocols, leader election, fault tolerance
- **Phase-3**: ML-based predictive scheduling, real-time anomaly detection