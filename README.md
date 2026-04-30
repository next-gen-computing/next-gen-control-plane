<div align="center">

# ⚡ Next-Gen Control Plane v1.0.0

**Production-grade distributed control plane with real-time predictive scheduling under failure conditions**

[![Version](https://img.shields.io/badge/version-v1.0.0-blue.svg)](https://github.com/YOUR_USERNAME/next-gen-control-plane/releases)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![Python](https://img.shields.io/badge/Python-3.11-blue.svg)](https://python.org)
[![gRPC](https://img.shields.io/badge/gRPC-1.68-green.svg)](https://grpc.io)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://docs.docker.com/compose/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-purple.svg)](https://openjfx.io)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<p align="center">
  <b>V2 Complete</b> • Glassmorphism UI • SQLite Persistence • Registration Flow • Bidirectional Streaming
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
- **🖥️ V2 Desktop Application** — Glassmorphism UI with Server/Node registration flows
- **💾 SQLite Persistence** — Embedded database for servers, nodes, memberships, join requests
- **🔐 TLS Certificates** — Self-signed certificate generation for secure communication
- **🎫 Connection Tokens** — Token-based node joining with approval workflow
- **🔄 Bidirectional Streaming** — Real-time gRPC streaming for heartbeats and commands
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
│                       v1.0.0 (V2 Complete)                         │
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

### 🖥️ V2 Desktop Application

The V2 desktop application features a modern glassmorphism UI with SQLite persistence and registration flows.

#### Option 1: Maven JavaFX Plugin (Recommended for Development)

**Windows Command Prompt (cmd):**
```cmd
cd java-control-plane
mvn clean compile
mvn javafx:run
```

**Windows PowerShell:**
```powershell
cd java-control-plane
mvn clean compile
mvn javafx:run
```

**Linux/macOS Bash:**
```bash
cd java-control-plane
mvn clean compile
mvn javafx:run
```

#### Option 2: Fat JAR (Recommended for Production)

**Build the JAR:**
```bash
cd java-control-plane
mvn clean package -DskipTests
```

**Run the JAR:**

**Windows Command Prompt (cmd):**
```cmd
cd java-control-plane
java -jar target/control-plane-1.0-SNAPSHOT.jar
```

**Windows PowerShell:**
```powershell
cd java-control-plane
java -jar target/control-plane-1.0-SNAPSHOT.jar
```

**Linux/macOS Bash:**
```bash
cd java-control-plane
java -jar target/control-plane-1.0-SNAPSHOT.jar
```

#### Automatic File Cleanup

The V2 desktop application automatically cleans up SQLite WAL (Write-Ahead Logging) files on shutdown to prevent locked file issues on subsequent launches. When the application closes, it deletes:
- `~/.nextgen-cp-v2/cluster.db-wal` (WAL file)
- `~/.nextgen-cp-v2/cluster.db-shm` (Shared memory file)

This ensures that the database is in a clean state for the next launch, preventing "file is locked" errors.

#### V2 Desktop App Features

- **Glassmorphism UI** — Modern dark theme with neon accents
- **Registration Flow** — Server/Node registration with auto-detected system specs
- **TLS Certificate Generation** — Automatic self-signed certificate creation
- **Connection Tokens** — Secure token-based node joining
- **Server Dashboard** — Real-time node monitoring, join request approval
- **Node Dashboard** — Server discovery, join flow, membership management
- **SQLite Database** — Embedded persistence at `~/.nextgen-cp-v2/cluster.db`

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
│   ├── pom.xml                      # Maven (gRPC, JavaFX, Prometheus, JaCoCo, SQLite, Hibernate)
│   ├── Dockerfile                   # Multi-stage build
│   └── src/main/java/com/nextgen/
│       ├── Main.java                # CLI entry point (ROLE-based)
│       ├── controlplane/            # ControlPlane Server (6 classes)
│       ├── agent/                   # NodeAgent (1 class)
│       ├── desktop/                 # V1 JavaFX Desktop App (20+ classes)
│       │   ├── DesktopLauncher.java  # GUI/CLI entry with headless detection
│       │   ├── DesktopApp.java       # Main JavaFX Application
│       │   ├── model/                # Data models
│       │   ├── repository/           # Observable data store
│       │   ├── service/              # Background services
│       │   ├── viewmodel/            # MVVM ViewModels
│       │   └── exception/            # Custom exception hierarchy
│       └── desktop/v2/              # V2 Desktop App (Glassmorphism UI)
│           ├── DesktopAppV2.java     # V2 main entry point
│           ├── db/                   # Database layer
│           │   ├── DatabaseManager.java
│           │   ├── entities/         # JPA entities (Server, Node, Membership, JoinRequest)
│           │   └── repositories/     # JPA repositories
│           ├── grpc/                 # gRPC services
│           │   └── ClusterManagerServiceImpl.java
│           ├── service/              # Business logic
│           │   └── RegistrationService.java
│           ├── util/                 # Utilities
│           │   ├── TlsCertificateGenerator.java
│           │   └── SystemSpecDetector.java
│           └── view/                 # UI components
│               ├── registration/     # Registration dialogs
│               └── dashboard/        # Server/Node dashboards
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
| V2 Desktop App | JavaFX 21.0.2, SQLite 3.46.0.0, Hibernate 6.4.4, JPA 3.1.0 |
| Desktop Application (V1) | JavaFX 21.0.2, Jackson 2.17.0 |
| Predictor | Python 3.11, grpcio, prometheus-client |
| Communication | Protocol Buffers 3 (proto3) |
| OS Metrics | `com.sun.management.OperatingSystemMXBean` (real readings) |
| Deployment | Docker Compose |
| Logging | SLF4J Simple (Java), `logging` module (Python) |
| Testing | JUnit 5.10.2, Mockito 5.11.0, JaCoCo 0.8.12 |

## Phase Roadmap

- **Phase-1** ✅: 3-node cluster, real heartbeats, round-robin, predictor stub, V1 desktop app
- **V2** ✅ (Current): Glassmorphism UI, SQLite persistence, registration flow, bidirectional streaming, join request/approval
- **Phase-3**: mTLS authentication, real-time metrics charts, UI animations
- **Phase-4**: Consensus protocols, leader election, fault tolerance
- **Phase-5**: ML-based predictive scheduling, real-time anomaly detection

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