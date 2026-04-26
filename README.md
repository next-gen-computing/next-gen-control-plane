<div align="center">

# ⚡ Next-Gen Control Plane v0.2.0

**Production-grade distributed control plane with real-time predictive scheduling under failure conditions**

[![Version](https://img.shields.io/badge/version-v0.2.0-blue.svg)](https://github.com/YOUR_USERNAME/next-gen-control-plane/releases)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![Python](https://img.shields.io/badge/Python-3.11-blue.svg)](https://python.org)
[![gRPC](https://img.shields.io/badge/gRPC-1.68-green.svg)](https://grpc.io)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://docs.docker.com/compose/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-purple.svg)](https://openjfx.io)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<p align="center">
  <b>Phase-1 Complete</b> • Distributed Cluster • Real OS Metrics • Predictive Scheduling • Desktop GUI
</p>

[Quick Start](#quick-start) • [Features](#features) • [Architecture](#architecture) • [Desktop App](#-desktop-application) • [Documentation](#documentation)

</div>

---

## 🎯 Features

### Core Capabilities

- **✅ Real OS Metrics** — Actual CPU & memory from `com.sun.management.OperatingSystemMXBean`
- **⚡ Predictive Scheduling** — ML-ready architecture with predictor service
- **🔒 Fault Tolerance** — Automatic node failure detection & recovery (6s timeout)
- **📊 Live Monitoring** — Real-time web dashboard with 2-second refresh
- **🖥️ Desktop Application** — JavaFX GUI (Docker Desktop-style) with Server/Node modes
- **🔄 Round-Robin Load Balancing** — Fair task distribution across healthy nodes
- **📈 Prometheus Metrics** — Full observability on all services
- **🔧 gRPC Communication** — High-performance binary protocol (proto3)
- **🐳 Docker Ready** — One-command deployment

### Technical Highlights

- **100% Real Data** — All metrics from actual OS readings, zero random values
- **Thread-Safe** — volatile, ConcurrentHashMap, AtomicInteger throughout
- **Dual Entry Points** — Desktop GUI (JavaFX) or CLI (ROLE env var)
- **Production Ready** — Health checks, graceful shutdown, structured logging
- **Java 21** — switch expressions, `--release 21` compilation

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    NEXT-GEN CONTROL PLANE                        │
│                       v0.2.0 (Phase-1)                           │
└─────────────────────────────────────────────────────────────────┘
           │                                        │
           ▼ JavaFX Desktop                         ▼ HTTP/REST
┌──────────────────────┐             ┌─────────────────────────────┐
│  🖥️  Desktop App    │             │  🎛️  Web Dashboard (:8085)  │
│  ├─ Server Mode      │             │  ├─ Real-time Charts        │
│  ├─ Node Mode        │             │  ├─ Overview, Performance   │
│  └─ System Metrics   │             │  └─ Auto-refresh 2s         │
└──────────────────────┘             └─────────────────────────────┘
                                                    │
                                                    ▼ gRPC (:50051)
┌─────────────────────────────────────────────────────────────────┐
│  🎯 ControlPlane (Java 21)                                       │
│  ├─ Node Registry (ConcurrentHashMap)                            │
│  ├─ Round-Robin Scheduler (AtomicInteger)                        │
│  ├─ Heartbeat Monitor (6s timeout → SUSPECTED_DEAD)              │
│  ├─ Dashboard API (/api/nodes on :8085)                          │
│  ├─ Static File Server (dashboard HTML/CSS/JS)                   │
│  └─ Prometheus Metrics (:9090)                                   │
└─────────────────────────────────────────────────────────────────┘
         │                           │                           │
         ▼ gRPC                      ▼ gRPC                      ▼ gRPC
┌──────────────┐            ┌──────────────┐            ┌──────────────┐
│  💻 Node 1   │            │  💻 Node 2   │            │  💻 Node 3   │
│  (Java 21)   │            │  (Java 21)   │            │  (Java 21)   │
│  ├─ Real CPU  │            │  ├─ Real CPU  │            │  ├─ Real CPU  │
│  ├─ Real Mem  │            │  ├─ Real Mem  │            │  ├─ Real Mem  │
│  └─ Heartbeat │            │  └─ Heartbeat │            │  └─ Heartbeat │
│     every 2s  │            │     every 2s  │            │     every 2s  │
└──────────────┘            └──────────────┘            └──────────────┘
         │                           │                           │
         └───────────────────────────┼───────────────────────────┘
                                     ▼
                      ┌──────────────────────────────┐
                      │  🔮 Predictor (Python 3.11)  │
                      │  ├─ GetPrediction RPC         │
                      │  ├─ ML-ready architecture     │
                      │  └─ Prometheus (:9091)        │
                      └──────────────────────────────┘
```

## Quick Start

### Docker (One Command)

```bash
docker compose up --build
```

This starts 5 services:
| Service | Role | Ports |
|---------|------|-------|
| `control-plane` | gRPC server + scheduler + dashboard | 50051, 8085, 9090 |
| `node1`, `node2`, `node3` | Node agents with real OS metrics | Internal only |
| `predictor` | Python prediction stub | 50052, 9091 |

### 🖥️ Desktop Application

```bash
cd java-control-plane
mvn clean compile
mvn javafx:run
```

The desktop app lets you choose between **Server Mode** and **Node Mode** with a modern dark-themed UI.

### Local CLI

```powershell
# Build
cd java-control-plane
mvn clean package -DskipTests

# Start server
$env:ROLE="server"; $env:PREDICTOR_HOST="localhost"
java -cp target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main

# Start node (separate terminal)
$env:ROLE="agent"; $env:NODE_ID="node1"; $env:CONTROL_PLANE_HOST="localhost"
java -cp target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main
```

## Monitoring

### Web Dashboard
Open http://localhost:8085 for the live monitoring UI with:
- **Overview** — Cluster status, node count, avg CPU/memory
- **Performance** — Per-node metric charts
- **Nodes** — Individual node details

### Live Terminal Monitor
```bash
pip install grpcio grpcio-tools protobuf rich
python scripts/monitor.py
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

# 2. Wait ~15 seconds for nodes to register

# 3. Run the test
pip install grpcio grpcio-tools protobuf
python scripts/integration-test.py
```

The test verifies:
- ✅ 3 nodes register successfully
- ✅ Heartbeats flow with real OS metrics
- ✅ Tasks get round-robin scheduled
- ✅ Predictor returns expected values

## Project Structure

```
next-gen-control-plane/
├── proto/
│   └── control_plane.proto          # Shared gRPC contract (2 services, 9 messages)
├── java-control-plane/
│   ├── pom.xml                      # Maven (gRPC, JavaFX, Prometheus, JaCoCo)
│   ├── Dockerfile                   # Multi-stage build
│   └── src/main/java/com/nextgen/
│       ├── Main.java                # CLI entry point (ROLE-based)
│       ├── controlplane/            # ControlPlane Server (6 classes)
│       ├── agent/                   # NodeAgent (1 class)
│       └── desktop/                 # JavaFX Desktop App (20+ classes)
│           ├── DesktopLauncher.java  # GUI/CLI entry with headless detection
│           ├── DesktopApp.java       # Main JavaFX Application
│           ├── model/                # Data models
│           ├── repository/           # Observable data store
│           ├── service/              # Background services
│           ├── viewmodel/            # MVVM ViewModels
│           └── exception/            # Custom exception hierarchy
├── python-predictor/                # Python ML service
├── dashboard/                       # Web UI (HTML/CSS/JS)
├── scripts/                         # Utilities & testing
├── docs/                            # Architecture docs
├── docker-compose.yml
├── DEVELOPMENT.md                   # Developer guide
├── CHANGELOG.md                     # Version history
├── CONTRIBUTING.md                  # Contribution guidelines
└── README.md                        # This file
```

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Control Plane & Agents | Java 21, gRPC 1.68, Protobuf 3.25.5, Prometheus 0.16.0 |
| Desktop Application | JavaFX 21.0.2, Jackson 2.17.0 |
| Predictor | Python 3.11, grpcio, prometheus-client |
| Communication | Protocol Buffers 3 (proto3) |
| OS Metrics | `com.sun.management.OperatingSystemMXBean` (real readings) |
| Deployment | Docker Compose |
| Logging | SLF4J Simple (Java), `logging` module (Python) |
| Testing | JUnit 5.10.2, Mockito 5.11.0, JaCoCo 0.8.12 |

## Phase Roadmap

- **Phase-1** ✅ (Current): 3-node cluster, real heartbeats, round-robin, predictor stub, desktop app
- **Phase-2**: Consensus protocols, leader election, fault tolerance
- **Phase-3**: ML-based predictive scheduling, real-time anomaly detection

---

## 🧪 Testing

### Running Tests

```bash
cd java-control-plane
mvn clean test

# View coverage report
start target/site/jacoco/index.html  # Windows
```

### Coverage Requirements
- Minimum: 60% instruction coverage (enforced by JaCoCo)
- Target: 80%+ for all components

---

## 📚 Documentation

- **[DEVELOPMENT.md](DEVELOPMENT.md)** — Developer setup, building, debugging
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — Detailed architecture decisions
- **[CHANGELOG.md](CHANGELOG.md)** — Version history and release notes
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — How to contribute

---

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## 📜 License

MIT License — see [LICENSE](LICENSE) file for details.

---

<div align="center">

**[⬆ Back to Top](#-next-gen-control-plane-v020)**

Made with ❤️ by Team Next-Gen | 2026

</div>