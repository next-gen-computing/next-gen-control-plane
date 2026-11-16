<div align="center">

# ⚡ Next-Gen Control Plane

**Enterprise-Grade Distributed Control Plane & Edge Compute Orchestrator**  
*Autonomous cluster management across the open internet featuring embedded Raft consensus, zero-trust NAT traversal, sub-second proactive task migration, and dual-layer predictive risk intelligence.*

[![Build Status](https://img.shields.io/badge/build-1.0--SNAPSHOT-blue.svg)](pom.xml)
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg?logo=openjdk)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Next.js](https://img.shields.io/badge/Next.js-16.4-black?logo=next.js)](https://nextjs.org)
[![React](https://img.shields.io/badge/React-19.3-61DAFB?logo=react)](https://react.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.0-3178C6?logo=typescript)](https://www.typescriptlang.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql)](https://www.postgresql.org)
[![gRPC](https://img.shields.io/badge/gRPC-1.83-green.svg?logo=grpc)](https://grpc.io)
[![Python](https://img.shields.io/badge/Python-3.11%2B-blue.svg?logo=python)](https://python.org)
[![Docker](https://img.shields.io/badge/Docker-Compose%20v2-2496ED.svg?logo=docker)](https://docs.docker.com/compose/)
[![Prometheus](https://img.shields.io/badge/Prometheus-Monitoring-E6522C?logo=prometheus)](https://prometheus.io)
[![Tests](https://img.shields.io/badge/Tests-880%2B%20Passing-brightgreen.svg)](java-control-plane/src/test/)
[![JaCoCo](https://img.shields.io/badge/JaCoCo-%3E80%25%20Coverage-success.svg)](pom.xml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<p align="center">
  <b>Multi-Module High-Performance Architecture</b> • Physical commodity nodes across the open internet • Zero inbound open ports • Custom Figure-8 Raft consensus • Sub-second pre-failure task migration • Dual operator interfaces (Web & Desktop)
</p>

[Quick Start](#-quick-start) • [Architecture](#-system-architecture) • [Core Pillars](#-core-technical-pillars) • [Technology Stack](#-technology-stack) • [Algorithms](#-core-algorithms) • [Documentation Hub](#-documentation-hub)

</div>

---

## 🌟 Executive Overview

**Next-Gen Control Plane** is a distributed control plane engineered to aggregate heterogeneous, commodity computing devices (laptops, workstations, and edge servers) across the open internet into a resilient, self-healing compute cluster. 

Unlike conventional orchestrators (such as Kubernetes or Nomad) that assume high-bandwidth, flat datacenter networks with open inbound ports and static failure timeouts, Next-Gen Control Plane solves the fundamental challenges of edge computing:
- **Zero Inbound Ingress**: Worker nodes communicate via long-lived, outbound-only HTTP/2 gRPC streaming connections, traversing residential Carrier-Grade NAT (CGNAT) and strict campus firewalls without VPNs or port forwarding.
- **Embedded Consensus**: High availability without external dependencies (no etcd, Consul, or ZooKeeper) via a native, pure-Java 3-replica Raft implementation with Figure-8 commit safety and CRC32 durable write-ahead logging.
- **Pre-Failure Proactive Migration**: Eliminates reactive heartbeat timeouts by evaluating real-time node telemetry trends (battery degradation under DC load, strictly rising RTT latency, memory saturation) to migrate in-flight tasks before nodes disconnect.
- **Heterogeneous Workload Apportionment**: Dynamically weights compute slices using the Hamilton-Hare largest-remainder method, ensuring optimal distribution across disparate hardware with zero integer rounding drift.

---

## 🚀 Core Technical Pillars

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   CORE TECHNICAL PILLARS                                        │
├──────────────────────────────┬──────────────────────────────┬───────────────────────────────────┤
│ 1. Zero-Trust NAT Traversal  │ 2. Embedded Raft Consensus   │ 3. Sub-Second Proactive Migration │
│ • Outbound-only gRPC streams │ • 3-replica pure Java Raft   │ • Rising-edge failure prediction  │
│ • TLS 1.3 / EC P-256 PKI     │ • Figure-8 commit safety     │ • ~1.65 ms measured MTTR recovery │
│ • Zero open inbound ports    │ • Durable CRC32 log.wal      │ • Atomic server fencing tokens    │
├──────────────────────────────┼──────────────────────────────┼───────────────────────────────────┤
│ 4. Capacity Apportionment    │ 5. Dual-Layer Risk Engine    │ 6. Dual Operator Control Plane    │
│ • Largest-remainder method   │ • Kernel heuristics fallback │ • Spring Boot 3 REST & WebSockets │
│ • Live CPU/RAM headroom      │ • XGBoost ML failure model   │ • Next.js 16 + React 19 Dashboard │
│ • Zero rounding drift slices │ • PyTorch LSTM load forecast │ • Native JavaFX 21 Desktop GUI    │
└──────────────────────────────┴──────────────────────────────┴───────────────────────────────────┘
```

### 1. Zero-Trust NAT Traversal & Ingress Security
- **Outbound Streaming Transport**: Edge worker nodes initiate persistent outbound gRPC client connections to the Control Plane. The connection remains open for bidirectional RPCs, task dispatch, and telemetry streaming.
- **Cryptographic Trust Model**: An embedded Elliptic Curve (EC P-256) Certificate Authority issues X.509 client certificates via single-use 256-bit enrollment tokens. Mutual TLS (mTLS) secures every RPC.
- **Zero Firewall Ingress**: Worker machines require zero open inbound ports, operating seamlessly behind symmetric NAT and corporate proxies.

### 2. High-Availability Embedded Raft Consensus
- **Zero-Dependency High Availability**: Replaces external stores like etcd with an in-memory, embedded 3-replica Raft state machine (`com.nextgen.controlplane.raft`).
- **Figure-8 Commit Safety**: Strictly implements Raft's Figure-8 invariant, preventing uncommitted log entries from prior terms from being committed by replica counting alone.
- **Crash-Durable WAL**: Every term transition, candidate vote, and log entry is written to a durable `log.wal` with binary CRC32 checksums for guaranteed crash-recovery consistency.

### 3. Predictive Fault Tolerance & Sub-Second Proactive Migration
- **Pre-Failure Rescheduling**: While traditional orchestrators wait 40–340 seconds for heartbeat failure timeouts, the `RiskMonitor` sweeps node telemetry every 5 seconds. When degradation is detected, the `ProactiveMigrator` redispatches active tasks in **~1.65 ms**.
- **Atomic Fencing Tokens**: Every reassigned task is tagged with an authenticated server-side fencing token. Stale results submitted by degraded or zombie nodes are atomically dropped.

### 4. Dynamic Heterogeneous Capacity Apportionment
- **Hardware-Aware Scoring**: Computes dynamic node capacity based on physical hardware, real-time headroom, and risk:
  $$\text{Capacity} = (\text{Cores} \times 1.0 + \text{RAM}_{\text{GB}} \times 0.25) \times \text{Headroom} \times (1.0 - \text{RiskPenalty})$$
- **Largest-Remainder Partitioning**: Allocates sub-task integer bounds using the Hamilton-Hare method, eliminating quota drift across distributed nodes.

### 5. Dual-Layer Predictive Risk Engine with Circuit Breaker
- **Layer 1 (Deterministic Heuristics)**: Zero-dependency evaluation of low battery (<15%), 5-sample strictly increasing RTT, and monotonic memory pressure ($\ge 90\%$).
- **Layer 2 (Machine Learning)**: Opt-in Python gRPC microservice serving an XGBoost Booster trained on 14 engineered features alongside a PyTorch LSTM 5-minute sequential load forecaster.
- **Deterministic Circuit Breaker**: If the ML service experiences network jitter or timeouts, the control plane degrades to Layer 1 heuristics instantaneously with zero dropped tasks.

---

## 🏗️ System Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              OPERATOR CONTROL INTERFACES                               │
│                                                                                        │
│   ┌─────────────────────────────────────┐      ┌────────────────────────────────────┐  │
│   │ Next.js 16 Web Dashboard (:3000)    │      │ JavaFX 21 Native Desktop UI        │  │
│   │ React 19 • Tailwind v4 • WebSocket  │      │ WebView • SSE Proxy • Glyph Canvas │  │
│   └──────────────────┬──────────────────┘      └─────────────────┬──────────────────┘  │
└──────────────────────┼───────────────────────────────────────────┼─────────────────────┘
                       │ HTTP / REST / WebSocket                   │ Local Proxy / gRPC
                       ▼                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                            CONTROL PLANE CORE (Spring Boot 3)                          │
│                                                                                        │
│   ┌────────────────────────────────────────────────────────────────────────────────┐   │
│   │ Ports: 50051 (gRPC) • 8080 (REST/WS) • 8085 (Node API) • 9090 (Metrics)        │   │
│   └──────────────────────────────────────┬─────────────────────────────────────────┘   │
│                                          │                                             │
│   ┌────────────────────┬─────────────────┴───┬──────────────────┬──────────────────┐   │
│   │ Embedded Raft      │ Proactive Migrator  │ Task Registry    │ EC P-256 PKI     │   │
│   │ 3-Replica Consensus│ ~1.65ms MTTR Engine │ Atomic Fencing   │ Certificate Auth │   │
│   └─────────┬──────────┴─────────┬───────────┴────────┬─────────┴────────┬─────────┘   │
│             │                    │                    │                  │             │
│   ┌─────────▼──────────┐ ┌───────▼───────────┐ ┌──────▼────────┐ ┌───────▼─────────┐   │
│   │ PostgreSQL 16      │ │ Capacity Scorer   │ │ Alerting       │ │ Python Predictor│   │
│   │ Flyway Migrations  │ │ Largest Remainder │ │ Webhook/Email  │ │ XGBoost + LSTM  │   │
│   └────────────────────┘ └───────────────────┘ └───────────────┘ └─────────────────┘   │
└──────────────────────────────────────────▲─────────────────────────────────────────────┘
                                           │
                    TLS 1.3 Outbound-Only gRPC Streams (Port 50051)
                  (Zero Inbound Firewall Ports Required on Workers)
                                           │
         ┌─────────────────────────────────┼─────────────────────────────────┐
         │                                 │                                 │
┌────────┴──────────────┐       ┌──────────┴─────────────┐       ┌───────────┴────────────┐
│ Physical Laptop #1    │       │ Physical Laptop #2     │       │ Remote Cloud Worker    │
│ Residential CGNAT     │       │ Campus Wi-Fi Firewall  │       │ Docker Container       │
│ Battery / RTT Telemetry│      │ Battery / RTT Telemetry│       │ Headless NodeAgent     │
└───────────────────────┘       └────────────────────────┘       └────────────────────────┘
```

---

## ⚡ Quick Start

Next-Gen Control Plane supports multiple execution topologies. For comprehensive deployment documentation across single laptop, local hotspot, WAN, desktop UI, and virtual Docker networks, see **[DEPLOYMENT.md](DEPLOYMENT.md)**.

### Path A: One-Command Full Stack (Docker Compose)
Runs the complete production topology: PostgreSQL 16, Spring Boot Control Plane, Next.js 16 Web Dashboard, and Prometheus:
```bash
docker compose -f docker-compose.full-stack.yml up -d --build
```
- **Web Dashboard**: [`http://localhost:3000`](http://localhost:3000)
- **Spring REST API**: [`http://localhost:8080/api/nodes`](http://localhost:8080/api/nodes)
- **gRPC Server**: `localhost:50051`

### Path B: Standalone Java Build & Launch
```bash
# Working Directory: Repository root (next-gen-control-plane)

# 1. Build the multi-module project
mvn clean install -DskipTests

# 2. Start the Control Plane Server (Fat JAR)
java -DROLE=server -DTLS_ENABLED=false -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
# Open Web Dashboard: http://localhost:8080/ (or http://<server-ip>:8080/ on another laptop)

# 3. Start a Worker Node Agent (in a separate terminal)
java -DROLE=agent -DCONTROL_PLANE_HOST=localhost -DTLS_ENABLED=false -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

### Path C: Launch the Cross-Platform Desktop UI
```bash
mvn -pl desktop-ui javafx:run
```

---

## 💻 Technology Stack

| Layer | Technologies & Frameworks | Description |
|---|---|---|
| **Backend & Core Engine** | Java 21 LTS, Spring Boot 3.3.x, gRPC 1.83, Protocol Buffers v3.25 | Reactive control plane, gRPC streaming, REST API, WebSocket event broker |
| **Consensus & Clustering** | Custom Java Raft Engine, CRC32 WAL, Figure-8 Commit Safety | Embedded 3-replica state machine, zero external coordination dependencies |
| **Web Dashboard** | Next.js 16.4, React 19.3, TypeScript 5.0, Tailwind CSS v4 | High-performance operator dashboard, real-time telemetry, procedural Glyph visualizer |
| **Desktop Application** | JavaFX 21, JavaFX WebView, Local Embedded HTTP Server | Cross-platform operator control panel, 3D cluster visualizer, Server-Sent Events |
| **Predictive Intelligence** | Python 3.11+, XGBoost 3.4, PyTorch, NumPy | 14-feature failure risk classifier, LSTM sequential load forecasting |
| **Persistence & Database** | PostgreSQL 16 Alpine, Flyway Database Migrations | Relational storage for nodes, audit logs, and distributed job records |
| **Security & PKI** | BouncyCastle, TLS 1.3, EC P-256, mTLS, Enrollment Tokens | In-memory Certificate Authority, token-based node enrollment, zero-trust auth |
| **Observability & Metrics** | Prometheus v2.54, Micrometer, Custom Exporters | End-to-end telemetry scraping, cluster health indicators, SLA tracking |
| **Build & Quality Tooling** | Apache Maven 3.9, JUnit 5, Mockito, Awaitility, JaCoCo (>80%) | Rigorous test verification suite with 880+ automated unit & integration tests |

---

## 🧠 Core Algorithms

The control plane implements 10 specialized algorithms designed for decentralized, heterogeneous environments. For mathematical derivations and proofs, see **[ALGORITHMS.md](ALGORITHMS.md)**.

1. **Round-Robin Node Rotation**: Binary search cursor rotation over sorted node identities, eliminating modulo bias and starvation.
2. **Hamilton-Hare Largest-Remainder Partitioning**: Slices integer job ranges proportionally by node capacity with zero rounding drift.
3. **Deterministic Heuristic Risk Scoring**: Multi-signal evaluator monitoring low battery on DC, monotonic memory saturation, and RTT spikes.
4. **Proactive Pre-Failure Migration**: Sub-second task migration triggered during pre-failure degradation windows.
5. **XGBoost 14-Feature Risk Classification**: Gradient-boosted decision trees trained on cluster telemetry.
6. **LSTM Sequential Load Forecasting**: Single-layer recurrent neural network predicting 5-minute forward CPU/RAM load.
7. **Raft Leader Election with Randomized Backoff**: Non-blocking consensus election preventing split-vote deadlocks.
8. **Figure-8 Prior-Term Commit Safety**: Restricts log commits to the current term, preventing log divergence.
9. **Least-Squares RTT Drift Slope**: Regression slope over sliding ping windows detecting network link degradation.
10. **Dual-Layer Circuit Breaker**: Sub-millisecond failover from remote ML scoring to local kernel heuristics.

---

## 📊 Chaos Engineering & Benchmarks

The project includes an empirical chaos engineering harness verifying all performance claims with zero simulated data. For comprehensive methodology, confidence intervals, and reproduction commands, see **[BENCHMARKS.md](BENCHMARKS.md)**.

```bash
# Execute master chaos benchmark suite
python benchmarks/run_chaos_benchmarks.py
```

### Empirical Summary Table
- **Mean MTTR (Proactive Migration)**: **1.65 ms** (30 independent trials; P95: 2.25 ms).
- **Speedup vs Kubernetes Default Eviction**: **~200,000× faster** (1.65 ms vs 340,000 ms).
- **Raft Leader Election Failover**: **916.1 ms** (25 chaos partition experiments; 0 split-brain incidents).
- **Host Failure Classifier ROC-AUC**: **0.912–1.000** on physical host degradation telemetry.
- **Alibaba PAI Cluster Trace ROC-AUC**: **0.663** across 300,000 real cluster trace records.
- **LSTM Load Forecasting Error**: **3.94% CPU RMSE** (47.8% relative error reduction vs baseline).

---

## 📁 Repository Structure

```
next-gen-control-plane/
├── benchmarks/               # Reproducible chaos engineering benchmark suite
│   ├── mttr_benchmark.py     # 30-trial MTTR proactive migration benchmark
│   ├── raft_partition_benchmark.py # 25-experiment Raft partition chaos harness
│   ├── risk_classification_benchmark.py # ROC-AUC evaluation suite
│   ├── load_forecast_benchmark.py # RMSE sequence forecasting benchmark
│   └── run_chaos_benchmarks.py # Master benchmark orchestrator
├── cli/                      # 'nx' command-line interface for cluster job management
├── datasets/                 # Real Alibaba PAI and Google cluster trace datasets
├── deploy/                   # Prometheus scraping configurations
├── desktop-ui/               # JavaFX 21 WebView native desktop operator application
├── docs/                     # Architectural presentations, interview guides, and roadmaps
├── java-control-plane/       # Spring Boot 3, Raft consensus, gRPC, and core control plane
│   └── src/main/java/com/nextgen/
│       ├── agent/            # NodeAgent client, power/battery and system metrics sensors
│       ├── controlplane/     # Central server, scheduler, task coordinator, database
│       │   ├── api/          # Spring REST controllers (Node, Job, Task, Audit, Topology)
│       │   ├── capacity/     # Hamilton-Hare capacity scoring & apportionment
│       │   ├── raft/         # Figure-8 Raft consensus state machine & durable WAL
│       │   ├── risk/         # Dual-layer risk scoring engine & circuit breaker
│       │   └── task/         # ProactiveMigrator, TaskRegistry, atomic fencing
│       └── security/         # In-memory EC P-256 PKI, Certificate Authority, mTLS
├── proto/                    # Protocol Buffer service and message definitions
├── python-predictor/         # XGBoost failure risk classifier & PyTorch LSTM forecaster
├── scripts/                  # End-to-end integration tests & cluster automation
├── web-ui/                   # Next.js 16 + React 19 real-time web dashboard
├── docker-compose.yml        # Single-node server compose stack
├── docker-compose.raft.yml   # 3-replica Raft high-availability cluster stack
├── docker-compose.full-stack.yml # Enterprise PostgreSQL + Spring + Next.js stack
├── DEPLOYMENT.md             # Complete 5-topology deployment guide
├── BENCHMARKS.md             # Chaos engineering benchmarks & IEEE Section VI proofs
├── ARCHITECTURE.md           # Deep distributed systems design documentation
├── ALGORITHMS.md             # Algorithmic specifications and mathematical models
├── DEVELOPMENT.md            # Developer onboarding, testing, and contribution setup
└── pom.xml                   # Root Maven multi-module reactor POM
```

---

## 📚 Documentation Hub

- 🚀 **[DEPLOYMENT.md](DEPLOYMENT.md)** — Step-by-step instructions for all 5 deployment topologies (Single Laptop, Hotspot LAN, Internet/WAN CGNAT, Desktop UI, Docker Virtual Laptops).
- 📊 **[BENCHMARKS.md](BENCHMARKS.md)** — Empirical chaos engineering methodology, Section VI IEEE proofs, MTTR tables, and reproduction commands.
- 🏗️ **[ARCHITECTURE.md](ARCHITECTURE.md)** — In-depth architectural analysis of the outbound streaming transport, mTLS trust chain, and Raft replication boundaries.
- 📐 **[ALGORITHMS.md](ALGORITHMS.md)** — Mathematical formulations, code pointers, and proofs for the 10 core algorithmic subsystems.
- 🛠️ **[DEVELOPMENT.md](DEVELOPMENT.md)** — Developer onboarding, local toolchain configuration, build commands, and debugging tips.
- 🤝 **[CONTRIBUTING.md](CONTRIBUTING.md)** — Code style, pull request guidelines, and community participation rules.

---

## 📜 License

Next-Gen Control Plane is open-source software licensed under the **[MIT License](LICENSE)**.
