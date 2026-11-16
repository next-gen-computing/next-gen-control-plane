# 🚀 Deployment Guide — Next-Gen Control Plane

This document is the authoritative, production-grade guide for deploying Next-Gen Control Plane across all supported topologies. Whether you are running on a single developer laptop, a physical Wi-Fi/hotspot cluster, across the public internet through CGNAT, or using containerized multi-node topologies, this guide covers architecture, network contracts, security, and exact step-by-step procedures.

---

## 📑 Table of Contents

1. [Topology Matrix & Decision Guide](#1-topology-matrix--decision-guide)
2. [Prerequisites & System Requirements](#2-prerequisites--system-requirements)
3. [Topology 1: Single Laptop (Development & Evaluation)](#3-topology-1-single-laptop-development--evaluation)
4. [Topology 2: Laptop with Shared Hotspot / Wi-Fi (Physical Edge Cluster)](#4-topology-2-laptop-with-shared-hotspot--wi-fi-physical-edge-cluster)
5. [Topology 3: Laptops over WAN & Internet (Zero-Trust NAT Traversal)](#5-topology-3-laptops-over-wan--internet-zero-trust-nat-traversal)
6. [Topology 4: Desktop UI Native Operator Client (JavaFX 21 WebView)](#6-topology-4-desktop-ui-native-operator-client-javafx-21-webview)
7. [Topology 5: Virtual Laptops via Docker (Multi-Container Topologies)](#7-topology-5-virtual-laptops-via-docker-multi-container-topologies)
   - [5.1 Single-Server Stack (`docker-compose.yml`)](#51-single-server-stack-docker-composeyml)
   - [5.2 3-Replica High-Availability Raft Cluster (`docker-compose.raft.yml`)](#52-3-replica-high-availability-raft-cluster-docker-composeraftyml)
   - [5.3 Enterprise Full Stack with PostgreSQL 16 & Next.js (`docker-compose.full-stack.yml`)](#53-enterprise-full-stack-with-postgresql-16--nextjs-docker-composefull-stackyml)
   - [5.4 Simulating Heterogeneous Worker Nodes with Docker Containers](#54-simulating-heterogeneous-worker-nodes-with-docker-containers)
8. [Configuration & Environment Variables](#8-configuration--environment-variables)
9. [Network Port Reference & Firewall Rules](#9-network-port-reference--firewall-rules)
10. [Troubleshooting & Diagnostics](#10-troubleshooting--diagnostics)

---

## 1. Topology Matrix & Decision Guide

| Topology | Target Environment | Ingress Requirement | Consensus / HA | Persistence | Recommended For |
|---|---|---|---|---|---|
| **1. Single Laptop** | Local developer machine | Loopback (`127.0.0.1`) | Single in-memory | In-memory / JSONL | Fast prototyping, unit/e2e test runs |
| **2. Shared Hotspot / Wi-Fi** | 2–10 physical laptops on local Wi-Fi / 4G Hotspot | Server binds LAN IP; nodes dial out | Single server | On-disk JSONL | Classrooms, hackathons, edge lab demos |
| **3. Laptops over WAN** | Public VPS + remote home/campus nodes | VPS port `50051` open; nodes need **0 open ports** | Optional Raft on VPS | JSONL + mTLS CA | Real decentralized crowd computing, remote teams |
| **4. Desktop UI** | Cross-platform operator workstation | Connects to gRPC `50051` | Transparent to UI | Local SQLite / app state | Visual monitoring, node management, Glyph Animals |
| **5. Virtual Docker Nodes** | Single workstation or CI server | Internal Docker bridge network | Raft 3-node or Single | PostgreSQL 16 / WAL volumes | Integration testing, automated chaos benchmarks |

---

## 2. Prerequisites & System Requirements

### Hardware Requirements
- **Server Role**: Minimum 2 CPU cores, 4 GB RAM, 10 GB free disk space.
- **Worker Node Role**: Any commodity laptop (x86_64 or ARM64), 1 CPU core, 512 MB available RAM. Battery/AC telemetry reporting supported on Windows, macOS, and Linux.

### Software Toolchains
- **Java**: OpenJDK 21 LTS (`java -version` must report `21.x`).
- **Build Tool**: Apache Maven 3.9.x+ (`mvn -version`).
- **Python (Optional for ML Predictor)**: Python 3.10+ with `numpy`, `xgboost`, and `torch`.
- **Docker (Optional for Container Stacks)**: Docker Engine 24+ and Docker Compose v2.x+.
- **Node.js (Optional for standalone Web UI)**: Node.js 20+ with `pnpm` or `npm`.

---

## 3. Topology 1: Single Laptop (Development & Evaluation)

In this mode, both the Control Plane and worker instances run as separate processes or in-memory threads on a single machine.

```
┌───────────────────────────────────────────────────────────┐
│                      SINGLE LAPTOP                        │
│                                                           │
│   ┌──────────────────────┐      ┌─────────────────────┐   │
│   │ ControlPlaneServer   │      │ NodeAgent (Worker)  │   │
│   │ gRPC :50051          │◀─────│ Dial localhost:50051│   │
│   │ Spring REST :8080    │      │ Outbound-only HTTP/2│   │
│   │ Dashboard API :8085  │      └─────────────────────┘   │
│   └──────────┬───────────┘                                │
│              │                                            │
│              ▼                                            │
│   ┌──────────────────────┐      ┌─────────────────────┐   │
│   │ Next.js Web UI :3000 │  or  │ Desktop UI (JavaFX) │   │
│   └──────────────────────┘      └─────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

### Step 1: Clone and Build
```bash
git clone https://github.com/next-gen-computing/next-gen-control-plane.git
cd next-gen-control-plane

# Build the complete multi-module project (Control Plane, CLI, Desktop UI)
mvn clean install -DskipTests
```

### Step 2: Start the Control Plane Server
```bash
# Working Directory: Repository root (next-gen-control-plane)

# Windows PowerShell:
$env:ROLE = "server"
$env:TLS_ENABLED = "false"
$env:SPRING_PROFILES_ACTIVE = "dev"
java -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar

# Linux / macOS:
ROLE=server TLS_ENABLED=false SPRING_PROFILES_ACTIVE=dev \
java -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```
*The server initializes and binds gRPC on port `50051`, Spring Boot REST & WebSocket on port `8080`, and the lightweight Node API on port `8085`.*

### Step 3: Start a Local Node Agent
Open a second terminal window:
```bash
# Working Directory: Repository root (next-gen-control-plane)

# Windows PowerShell:
$env:ROLE = "agent"
$env:CONTROL_PLANE_HOST = "localhost"
$env:CONTROL_PLANE_PORT = "50051"
$env:TLS_ENABLED = "false"
java -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar

# Linux / macOS:
ROLE=agent CONTROL_PLANE_HOST=localhost CONTROL_PLANE_PORT=50051 TLS_ENABLED=false \
java -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

### Step 4: Verify Cluster Health
```bash
# Query the live cluster nodes via cURL or browser
curl http://localhost:8085/api/nodes
```
Expected output:
```json
[
  {
    "nodeId": "node-...",
    "hostname": "...",
    "state": "ALIVE",
    "cpuPercent": 12.4,
    "memoryPercent": 48.2,
    "atRisk": false
  }
]
```

---

## 4. Topology 2: Laptop with Shared Hotspot / Wi-Fi (Physical Edge Cluster)

Turn any gathering of 2 to 10 commodity laptops into a real distributed compute cluster using a phone hotspot or local Wi-Fi router.

```
                     ┌──────────────────────────────┐
                     │     SERVER LAPTOP (AP/LAN)   │
                     │     IP: 192.168.1.100        │
                     │                              │
                     │  ControlPlaneServer (:50051) │
                     │  CertificateAuthority (PKI)  │
                     │  JobCoordinator & Scheduler  │
                     └──────────────▲───────────────┘
                                    │
           ┌────────────────────────┼────────────────────────┐
           │ gRPC Stream (:50051)   │ gRPC Stream (:50051)   │ gRPC Stream (:50051)
           │ (Outbound Only)        │ (Outbound Only)        │ (Outbound Only)
┌──────────┴──────────┐  ┌──────────┴──────────┐  ┌──────────┴──────────┐
│ WORKER LAPTOP #1    │  │ WORKER LAPTOP #2    │  │ WORKER LAPTOP #3    │
│ IP: 192.168.1.101   │  │ IP: 192.168.1.102   │  │ IP: 192.168.1.103   │
│ Battery / AC Sensor │  │ Battery / AC Sensor │  │ Battery / AC Sensor │
│ Zero open ports     │  │ Zero open ports     │  │ Zero open ports     │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

### Architectural Rules for Local Hotspot Clusters
1. **Outbound Invariant**: Worker laptops do **not** accept incoming connections. Windows Firewall or macOS Application Firewall will **never** block workers because workers only make outbound HTTP/2 socket connections to the server.
2. **Server Ingress**: The server laptop must allow inbound TCP connections on port `50051`.
3. **mTLS Security**: The server acts as an embedded Certificate Authority (EC P-256). Nodes exchange single-use enrollment tokens for X.509 certificates.

### Step 1: Configure the Server Laptop
Determine the server's Wi-Fi IP address:
```bash
# Windows:
ipconfig | Select-String "IPv4"
# Example output: 192.168.43.150

# Linux / macOS:
ifconfig | grep "inet " || ip addr show
```

Start the Control Plane on the server laptop with enrollment tokens:
```bash
# Working Directory: Repository root (next-gen-control-plane)
# Run on Server Laptop:
java -DROLE=server \
     -DTLS_ENABLED=true \
     -DTLS_SAN_HOSTS=192.168.43.150,localhost \
     -DENROLLMENT_TOKENS=alpha-token,beta-token,gamma-token \
     -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

### Step 2: Configure Worker Laptops
On Worker Laptop #1:
```bash
# Working Directory: Repository root (next-gen-control-plane)
java -DROLE=agent \
     -DCONTROL_PLANE_HOST=192.168.43.150 \
     -DCONTROL_PLANE_PORT=50051 \
     -DTLS_ENABLED=true \
     -DENROLLMENT_TOKEN=alpha-token \
     -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

On Worker Laptop #2:
```bash
# Working Directory: Repository root (next-gen-control-plane)
java -DROLE=agent \
     -DCONTROL_PLANE_HOST=192.168.43.150 \
     -DCONTROL_PLANE_PORT=50051 \
     -DTLS_ENABLED=true \
     -DENROLLMENT_TOKEN=beta-token \
     -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

### Step 3: Submit a Distributed Job
From any machine, submit a compute-intensive workload split across workers using the `nx` CLI:
```bash
# Working Directory: Repository root (next-gen-control-plane)
java -jar cli/target/nextgen-cli-1.0-SNAPSHOT.jar submit --kind prime-count --start 1 --end 50000000 --server 192.168.43.150:50051
```
The `HeuristicNodeCapacityScorer` weights each laptop according to its current CPU cores, RAM, and live battery status using the largest-remainder method.

---

## 5. Topology 3: Laptops over WAN & Internet (Zero-Trust NAT Traversal)

Deploying edge workers across geographical locations (residential homes, universities, office networks) where nodes reside behind Carrier-Grade NAT (CGNAT) or restrictive corporate firewalls.

```
                           ┌───────────────────────────────────────────────┐
                           │          PUBLIC CLOUD VPS / INSTANCE          │
                           │          (AWS / GCP / DigitalOcean)           │
                           │          Public IP: 203.0.113.50              │
                           │          Domain: control.mycluster.org        │
                           │                                               │
                           │  ControlPlaneServer (:50051)                  │
                           │  Python Predictor (XGBoost/LSTM) (:50052)     │
                           │  Prometheus Scraper (:9090)                   │
                           └───────────────────────▲───────────────────────┘
                                                   │
                          TLS 1.3 Outbound-Only gRPC Streams (Port 50051)
                                                   │
         ┌─────────────────────────────────────────┼────────────────────────────────────────┐
         │                                         │                                        │
┌────────┴───────────────────┐    ┌────────────────┴───────────┐    ┌───────────────────────┴────────┐
│ HOME LAPTOP (Behind CGNAT) │    │ UNIVERSITY LAB WORKSTATION │    │ COFFEE SHOP LAPTOP (Wi-Fi)     │
│ NAT: 100.64.0.0/10 Carrier │    │ NAT: Strict Institutional  │    │ NAT: Dynamic Dynamic IP        │
│ Inbound Open Ports: ZERO   │    │ Inbound Open Ports: ZERO   │    │ Inbound Open Ports: ZERO       │
│ Persistent HTTP/2 Stream   │    │ Persistent HTTP/2 Stream   │    │ Persistent HTTP/2 Stream       │
└────────────────────────────┘    └────────────────────────────┘    └────────────────────────────────┘
```

### How NAT Traversal Works Without Inbound Puncturing
- **Outbound Long-Lived gRPC Stream**: The node agent dials `control.mycluster.org:50051`. NAT routers establish a stateful outbound translation table entry.
- **Bi-Directional Streaming**: Task dispatch, heartbeat signals, risk assessment, and telemetry flow back and forth over the established outbound HTTP/2 transport.
- **Zero Firewall Ports Required**: No UPnP, no STUN/TURN, no port-forwarding rules on consumer routers, and no VPN mesh required.

### Server Setup (Cloud VPS)
1. Launch an Ubuntu 22.04 or Debian 12 cloud instance with a static public IPv4.
2. Open port `50051` (and port `3000`/`8080` if web UI is exposed) in the cloud security group.
3. Deploy via Docker Compose:
```bash
git clone https://github.com/next-gen-computing/next-gen-control-plane.git
cd next-gen-control-plane

# Create environment configuration
cat <<EOF > .env
TLS_ENABLED=true
TLS_SAN_HOSTS=control.mycluster.org,203.0.113.50
ENROLLMENT_TOKENS=node-tok-nyc,node-tok-lon,node-tok-tokyo
ML_RISK_SCORER_ENABLED=true
EOF

# Start the public control plane
docker compose up -d
```

### Remote Node Setup
On any remote machine anywhere in the world:
```bash
# Working Directory: Repository root (next-gen-control-plane)
# Run the node agent pointing to the public VPS domain
java -DROLE=agent \
     -DCONTROL_PLANE_HOST=control.mycluster.org \
     -DCONTROL_PLANE_PORT=50051 \
     -DTLS_ENABLED=true \
     -DENROLLMENT_TOKEN=node-tok-nyc \
     -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

---

## 6. Topology 4: Desktop UI Native Operator Client (JavaFX 21 WebView)

The Desktop UI module provides a native, desktop-class control panel for operators.

```
┌────────────────────────────────────────────────────────┐
│                   DESKTOP APPLICATION                  │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ JavaFX 21 Application (DesktopApp.java)          │  │
│  │                                                  │  │
│  │  ┌────────────────────────────────────────────┐  │  │
│  │  │ JavaFX WebView (Chromium/WebKit engine)    │  │  │
│  │  │                                            │  │  │
│  │  │ • 3D Cluster Visualizer (Network Canvas)   │  │  │
│  │  │ • Live Telemetry & CPU/RAM Sparklines      │  │  │
│  │  │ • Proactive Migration Real-Time Alerts     │  │  │
│  │  │ • Procedural ASCII "Glyph Animals" Display │  │  │
│  │  └──────────────────────▲─────────────────────┘  │  │
│  │                         │ HTTP / SSE              │  │
│  │  ┌──────────────────────▼─────────────────────┐  │  │
│  │  │ LocalUiServer (Lightweight embedded HTTP)  │  │  │
│  │  │ Proxies gRPC calls to JVM Control Plane    │  │  │
│  │  └────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

### Building and Launching the Desktop UI
```bash
# Build desktop module
mvn clean install -pl desktop-ui -am -DskipTests

# Run the desktop application directly
mvn -pl desktop-ui javafx:run
```

### Running the Desktop UI against an External Cluster
By default, the desktop UI connects to `localhost:50051`. To point it at a remote cluster:
```bash
mvn -pl desktop-ui javafx:run -Dcontrol.plane.host=control.mycluster.org -Dcontrol.plane.port=50051
```

### Standalone Executable (Fat JAR)
```bash
# Working Directory: Repository root (next-gen-control-plane)
java -jar desktop-ui/target/desktop-ui-1.0-SNAPSHOT.jar
```

---

## 7. Topology 5: Virtual Laptops via Docker (Multi-Container Topologies)

For continuous integration, regression testing, and local microservices testing, the repository provides three battle-tested Docker Compose configurations.

### 5.1 Single-Server Stack (`docker-compose.yml`)
Runs the Control Plane, Python Predictor, and Prometheus scraper.

```bash
docker compose up -d --build
```
- **Control Plane**: Port `50051` (gRPC), `8080` (Spring REST), `8085` (API)
- **Predictor**: Port `50052` (gRPC), `9091` (Metrics)
- **Prometheus**: Port `9464` (Metrics UI)

Check logs:
```bash
docker compose logs -f control-plane
```

### 5.2 3-Replica High-Availability Raft Cluster (`docker-compose.raft.yml`)
Runs a true distributed consensus cluster implementing the Raft protocol with Figure-8 commit safety and CRC32 durable WAL logs.

```bash
docker compose -f docker-compose.raft.yml up -d --build
```

```
┌─────────────────────────────────────────────────────────────────┐
│                    3-REPLICA RAFT TOPOLOGY                      │
│                                                                 │
│  ┌─────────────────┐   AppendEntries   ┌─────────────────┐      │
│  │ control-plane-1 │◀─────────────────▶│ control-plane-2 │      │
│  │ Term: 1 (LEADER)│                   │ Term: 1 (FOLLWR)│      │
│  │ Port: 50051     │                   │ Port: 50061     │      │
│  └────────┬────────┘                   └────────┬────────┘      │
│           │                                     │               │
│           │            RequestVote              │               │
│           └──────────────────┬──────────────────┘               │
│                              │                                  │
│                              ▼                                  │
│                    ┌─────────────────┐                          │
│                    │ control-plane-3 │                          │
│                    │ Term: 1 (FOLLWR)│                          │
│                    │ Port: 50071     │                          │
│                    └─────────────────┘                          │
└─────────────────────────────────────────────────────────────────┘
```

#### Verifying Raft Leader Election & Failover:
1. Identify the current leader:
```bash
curl http://localhost:8085/api/raft/status
```
2. Simulate a chaos event by killing the leader container:
```bash
docker stop next-gen-control-plane-control-plane-1-1
```
3. Observe automatic failover in under 1.2 seconds:
```bash
docker compose -f docker-compose.raft.yml logs -f control-plane-2
```
Followers detect leader silence, increment the term, and elect a new leader with zero state loss.

### 5.3 Enterprise Full Stack with PostgreSQL 16 & Next.js (`docker-compose.full-stack.yml`)
The complete production stack including an enterprise relational database, Flyway schema migrations, Spring Boot REST/WebSocket backend, and the modern Next.js 16 Web Dashboard.

```bash
docker compose -f docker-compose.full-stack.yml up -d --build
```

#### Services and Endpoints:
| Service | Technology | Port | Purpose |
|---|---|---|---|
| `db` | PostgreSQL 16 Alpine | `5432` | Relational storage for nodes, jobs, audit logs |
| `java-cp` | Spring Boot 3 + gRPC | `50051`, `8080`, `8085` | Control plane core engine & API |
| `web-ui` | Next.js 16 + React 19 | `3000` | Real-time web dashboard & Glyph visualizer |
| `prometheus` | Prometheus v2.54 | `9090` | Time-series metrics and scrape engine |

Access the Web Dashboard at: **`http://localhost:3000`**

### 5.4 Simulating Heterogeneous Worker Nodes with Docker Containers
You can spawn multiple virtual nodes with simulated battery and memory constraints on a single host:

```bash
# Worker 1: High-spec node
docker run -d --name worker-desktop \
  --network nextgen-net \
  -e ROLE=agent \
  -e CONTROL_PLANE_HOST=control-plane \
  -e CONTROL_PLANE_PORT=50051 \
  -e TLS_ENABLED=false \
  next-gen-control-plane-java-cp

# Worker 2: Low-memory laptop node
docker run -d --name worker-laptop \
  --network nextgen-net \
  --memory=512m \
  -e ROLE=agent \
  -e CONTROL_PLANE_HOST=control-plane \
  -e CONTROL_PLANE_PORT=50051 \
  -e TLS_ENABLED=false \
  next-gen-control-plane-java-cp
```

---

## 8. Configuration & Environment Variables

| Variable | Default | Role | Description |
|---|---|---|---|
| `ROLE` | `server` | Both | `server` starts Control Plane; `agent` starts worker node |
| `GRPC_PORT` | `50051` | Server | TCP port for inbound worker node gRPC streams |
| `CONTROL_PLANE_HOST` | `localhost` | Agent | Target server address to dial |
| `CONTROL_PLANE_PORT` | `50051` | Agent | Target server port |
| `TLS_ENABLED` | `false` | Both | Enables TLS 1.3 / mTLS mutual certificate authentication |
| `TLS_SAN_HOSTS` | `localhost` | Server | Comma-separated IP addresses/domains in server certificate |
| `ENROLLMENT_TOKENS` | *none* | Server | Comma-separated list of pre-authorized node joining tokens |
| `ENROLLMENT_TOKEN` | *none* | Agent | Single-use secret passed during initial PKI enrollment |
| `RAFT_ENABLED` | `false` | Server | Activates 3-replica embedded Raft consensus |
| `RAFT_PEERS` | *none* | Server | Static peer list: `id1=host:port,id2=host:port` |
| `RAFT_NODE_ID` | *none* | Server | Unique Raft replica identifier |
| `ML_RISK_SCORER_ENABLED` | `false` | Server | Enables XGBoost machine-learning risk scoring via Python |
| `PREDICTOR_HOST` | `localhost` | Server | Hostname of Python predictor gRPC service |
| `PREDICTOR_PORT` | `50052` | Server | Port of Python predictor gRPC service |
| `SPRING_DATASOURCE_URL` | *in-memory* | Server | PostgreSQL JDBC URL (`jdbc:postgresql://db:5432/nextgen`) |

---

## 9. Network Port Reference & Firewall Rules

| Port | Protocol | Direction | Service | Firewall Requirement |
|---|---|---|---|---|
| `50051` | TCP / HTTP/2 | Inbound to Server | gRPC Control Plane Core | **Required on Server only**. Never open on workers. |
| `8080` | TCP / HTTP/1.1 | Inbound to Server | Spring REST & WebSockets | Optional: for Web UI & external integrations. |
| `8085` | TCP / HTTP/1.1 | Inbound to Server | Lightweight JSON API | Optional: for operator scripting (`/api/nodes`). |
| `50052` | TCP / HTTP/2 | Internal Docker | Python Predictor gRPC | Kept internal to container network. |
| `3000` | TCP / HTTP | Inbound to Web UI | Next.js Dashboard | Open if hosting Web UI for remote operators. |
| `9090`/`9464` | TCP / HTTP | Inbound to Metrics | Prometheus UI & Metrics | Internal or secured monitoring network. |

---

## 10. Troubleshooting & Diagnostics

### Symptom: Worker node cannot connect (`UNAVAILABLE: io exception`)
1. **Verify server process is listening**:
   ```bash
   # Windows PowerShell
   Get-NetTCPConnection -LocalPort 50051
   # Linux / macOS
   netstat -tlpn | grep 50051 || lsof -i :50051
   ```
2. **Check IP reachability**: Run `ping <server-ip>` and `telnet <server-ip> 50051` from the worker machine.
3. **Firewall check**: Ensure the server laptop's operating system firewall allows inbound traffic on port `50051`.

### Symptom: Node connects but reports `UNAUTHENTICATED` / SSL handshake error
1. Check if `TLS_ENABLED` matches on both the server and worker (both must be `true` or both `false`).
2. Verify that the IP or domain dialed by the worker is present in the server's `TLS_SAN_HOSTS`.
3. If using tokens, verify the token has not expired or already been consumed by another node.

### Symptom: Dashboard returns 404 or connection refused from another laptop
1. **Target Port**: The rich browser Web Dashboard is hosted on port **`8080`** (`http://<server-ip>:8080/`), served by Spring Boot embedded Tomcat. Port `8085` provides the lightweight JSON node endpoint (`/api/nodes`) and automatically 302-redirects root web traffic to port `8080`.
2. **IP Address Check**: Ensure the other laptop navigates to `http://<server-ip>:8080/` (e.g. `http://192.168.1.100:8080/`), where `<server-ip>` is the server laptop's Wi-Fi / LAN IP (found via `ipconfig` on Windows or `ifconfig` on macOS/Linux). Do not type `localhost` on the second laptop, as `localhost` points to the second laptop itself.
3. **Firewall Ingress**: On the server laptop, ensure Windows Firewall or macOS Application Firewall allows inbound TCP traffic on ports `8080`, `8085`, and `50051`.
   ```powershell
   # Windows PowerShell (Run as Administrator on Server Laptop):
   New-NetFirewallRule -DisplayName "NextGen Control Plane Inbound" -Direction Inbound -LocalPort 8080,8085,50051 -Protocol TCP -Action Allow
   ```
   ```bash
   # Linux (ufw):
   sudo ufw allow 8080/tcp && sudo ufw allow 8085/tcp && sudo ufw allow 50051/tcp
   ```

### Symptom: Proactive migration not triggering
1. Note that proactive migration requires the worker to send telemetry showing degradation (e.g. battery < 15% on battery power, or 5 consecutive rising RTT samples).
2. Inspect live telemetry at `http://localhost:8085/api/nodes`.
3. Check if ML risk scoring is enabled without a running predictor; if unneeded, ensure `ML_RISK_SCORER_ENABLED=false` to use deterministic kernel heuristics.

---
*Next-Gen Control Plane — High-Performance Distributed Systems Engineering.*

