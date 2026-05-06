# Development Setup — Next-Gen Control Plane

**Version:** v1.0.0 | **Status:** Phase-2 Desktop UI Complete

## 📋 Prerequisites

| Tool | Minimum Version | Purpose | Verification |
|------|----------------|---------|--------------|
| Docker Desktop | 24.x+ | Container runtime | `docker --version` |
| Docker Compose | v2.x+ | Multi-container orchestration | `docker compose version` |
| Java JDK | 21 | ControlPlane, NodeAgent & Desktop App | `java -version` |
| Maven | 3.9+ | Java build system | `mvn -version` |
| Python | 3.11+ | Predictor service | `python --version` |
| JavaFX | 21.0.2 | Desktop GUI (bundled via Maven) | Included in pom.xml |

## 🚀 Quick Start (One Command)

```bash
# Clone repository
git clone https://github.com/YOUR_USERNAME/next-gen-control-plane.git
cd next-gen-control-plane

# Start everything (Docker mode)
docker compose up --build
```

This will:
1. Build Java fat JAR (ControlPlane + NodeAgent) via Maven
2. Build Python predictor with gRPC stubs
3. Start all 5 services on `nextgen-net` Docker network
4. Dashboard available at http://localhost:8085

## 🖥️ Desktop Application (Phase-2 UI)

The Phase-2 desktop application is a modern JavaFX UI in a separate `desktop-ui` Maven module. It connects to the ControlPlane and Predictor services via gRPC and displays real-time data. The UI uses an MVVM architecture with dark/light theme support.

### Build & Launch

#### Option 1: Maven JavaFX Plugin (Recommended for Development)

```bash
cd desktop-ui
mvn clean compile
mvn javafx:run
```

#### Option 2: Fat JAR (Recommended for Production)

```bash
cd desktop-ui
mvn clean package -DskipTests
java -jar target/desktop-ui-1.0-SNAPSHOT.jar
```

### Phase-2 Desktop App Features

- **Modern Dark/Light Themes** — Toggle between dark and light modes
- **Dashboard** — Cluster summary cards and live node metrics with real-time updates
- **Node Management** — Connect to ControlPlane, view connected nodes in a table
- **Task Execution** — Submit tasks (Matrix Multiplication, Array Sum, Prime Counter) and track progress
- **Live Monitoring** — Real-time logs and performance metrics
- **Settings** — Connection configuration, refresh interval, theme toggle
- **Real Data Only** — All metrics fetched live from ControlPlane gRPC; no mock data
- **Predictor Placeholder** — PredictorService shows `N/A` until the service is running

### Entry Points

| Entry Point | Class | Module | Purpose |
|-------------|-------|--------|---------|
| Phase-2 Desktop GUI | `com.nextgen.desktop.ui.DesktopApp` | `desktop-ui` | Modern JavaFX UI with gRPC client |
| CLI / Docker | `com.nextgen.Main` | `java-control-plane` | ROLE-based CLI entry (server/agent) |

## 🔧 Local Development (Without Docker)

### Step 1: Build Java Project

```bash
cd java-control-plane
mvn clean package -DskipTests
```

### Step 2: Start ControlPlane Server

**PowerShell (Windows):**
```powershell
cd java-control-plane
$env:ROLE="server"; $env:PREDICTOR_HOST="localhost"; 
java -cp target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main
```

**Bash (Linux/Mac):**
```bash
cd java-control-plane
ROLE=server PREDICTOR_HOST=localhost java -cp target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main
```

**Expected output:**
```
=== Next-Gen Control Plane | Role: SERVER ===
📊 Prometheus metrics server started on port 9090
📡 Dashboard on http://localhost:8085
📊 API endpoint: http://localhost:8085/api/nodes
🚀 ControlPlane gRPC server RUNNING on port 50051
```

### Step 3: Start NodeAgent(s)

Terminal 1 (Node 1):

**PowerShell:**
```powershell
$env:ROLE="agent"; $env:NODE_ID="node1"; $env:CONTROL_PLANE_HOST="localhost"
java -cp java-control-plane/target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main
```

**Bash:**
```bash
ROLE=agent NODE_ID=node1 CONTROL_PLANE_HOST=localhost \
  java -cp java-control-plane/target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main
```

**Expected output:**
```
🖥  NodeAgent 'node1' starting...
Hostname: your-host, IP: 192.168.x.x
📊 Prometheus metrics on port 9090
✅ Registered with ControlPlane: status=REGISTERED
💓 Heartbeat #1: cpu=12.3%, mem=45.6% → OK
```

### Step 4: Start Python Predictor

```bash
cd python-predictor
pip install -r requirements.txt

# Generate stubs (if not present)
python -m grpc_tools.protoc -I../proto --python_out=. --grpc_python_out=. ../proto/control_plane.proto

python predictor_service.py
```

**Expected output:**
```
📊 Prometheus metrics server started on port 9091
🐍 Predictor gRPC server RUNNING on port 50052
```

## 📊 Service Endpoints

| Service | URL | Purpose |
|---------|-----|---------|
| Dashboard | http://localhost:8085 | Live monitoring UI |
| Dashboard API | http://localhost:8085/api/nodes | JSON node data |
| ControlPlane gRPC | localhost:50051 | Node registration & heartbeats |
| ControlPlane Metrics | http://localhost:9090/metrics | Prometheus |
| Predictor gRPC | localhost:50052 | ML predictions |
| Predictor Metrics | http://localhost:9091/metrics | Prometheus |

## 🌐 Connecting Physical Nodes (Laptops)

### Step 1: Start the Server on the Host Laptop

**PowerShell:**
```powershell
cd java-control-plane
$env:ROLE="server"; $env:PREDICTOR_HOST="localhost"
java -cp target/control-plane-1.0-SNAPSHOT.jar com.nextgen.Main
```

**Check the server logs for LAN IPs:**
```
🚀 ControlPlane gRPC server RUNNING on port 50051
🌐 LAN IPs (use these from other laptops):
     → 192.168.1.100 on Wi-Fi
     → 10.0.0.5 on Ethernet
```

### Step 2: Allow Firewall Access (Windows)

On the **server laptop**, allow port 50051 through Windows Firewall:
```powershell
# Run as Administrator
New-NetFirewallRule -DisplayName "ControlPlane gRPC" -Direction Inbound -Protocol TCP -LocalPort 50051 -Action Allow
```

### Step 3: Connect Node Laptops via Desktop UI

On each **node laptop**:

1. Launch the Desktop UI:
```bash
cd desktop-ui
mvn javafx:run
```

2. Go to **Node Management** screen
3. In the **"Join Server as Node"** panel, enter:
   - **Node Name**: e.g., `laptop-kitchen`
   - **Server IP**: The LAN IP from server logs (e.g., `192.168.1.100`)
   - **Token**: Connection token (if required by your server config)
4. Click **Join Server**

The node will:
- Connect to the server via gRPC on port 50051
- Register itself with the ControlPlane
- Start sending heartbeats with CPU/memory metrics
- Appear in the Dashboard and Node Management table

### Step 4: Verify Connection

On the **server laptop**, check the logs:
```
✅ Node registered: laptop-kitchen (192.168.1.101)
💓 Heartbeat #1 from laptop-kitchen: cpu=15.2%, mem=42.1% → OK
```

Or open the Desktop UI on any machine and view the **Dashboard** — all connected nodes will appear as live cards with real-time metrics.

## 🧪 Testing

### Unit Tests (Java)

```bash
cd java-control-plane
mvn clean test

# View coverage report
# Windows:
start target/site/jacoco/index.html
# macOS:
open target/site/jacoco/index.html
```

**Coverage Requirements:**
- Minimum 60% instruction coverage (enforced by JaCoCo)
- Target: 80%+ for all components

### Integration Tests

```bash
# Start cluster in background
docker compose up --build -d

# Wait for services to start
sleep 15

# Run integration test
python scripts/integration-test.py

# Tear down
docker compose down
```

### Manual Testing Checklist

- [ ] `mvn clean compile` produces BUILD SUCCESS with no errors
- [ ] Dashboard loads at http://localhost:8085
- [ ] Overview page shows real-time charts
- [ ] Performance page shows per-node metrics
- [ ] Nodes page shows individual node details
- [ ] All 3 nodes registered (check logs)
- [ ] Heartbeats flowing every 2 seconds
- [ ] Prometheus metrics accessible
- [ ] Desktop GUI launches with `mvn javafx:run`

## 🏗️ Proto Code Generation

### Java (Auto-generated by Maven)

```bash
cd java-control-plane
mvn compile
```

Stubs generated at:
- `target/generated-sources/protobuf/java/` — Protobuf message classes
- `target/generated-sources/protobuf/grpc-java/` — gRPC service stubs

### Python (Manual or Docker build)

```bash
python -m grpc_tools.protoc \
  -I proto \
  --python_out=python-predictor \
  --grpc_python_out=python-predictor \
  proto/control_plane.proto
```

Output files:
- `control_plane_pb2.py` - Message classes
- `control_plane_pb2_grpc.py` - Service stubs

## 🔍 Debugging

### View Service Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f control-plane
docker compose logs -f node1
docker compose logs -f predictor
```

### Common Issues

**Issue:** `docker: not found`
**Fix:** Start Docker Desktop

**Issue:** `Port 50051 already in use`
**Fix:** `docker compose down` then retry, or check `netstat -ano | findstr 50051` (Windows)

**Issue:** `Could not register after 10 attempts`
**Fix:** Ensure ControlPlane is running before starting NodeAgents

**Issue:** Dashboard shows no data
**Fix:** Check browser console (F12), verify API call to `http://localhost:8085/api/nodes`

**Issue:** JavaFX not found or GUI fails to start
**Fix:** Ensure JDK 21 is installed. On headless Linux, use `--cli` flag or set `ROLE` env var.

**Issue:** V2 database not found at `~/.nextgen-cp-v2/cluster.db`
**Fix:** Database is auto-created on first launch. Check file permissions.

**Issue:** Lombok compilation errors
**Fix:** V2 does not use Lombok. Ensure no Lombok annotations remain in V2 code.

### Enable Debug Logging

Java services use SLF4J simple logger. Set level in environment:
```bash
# Debug mode
_JAVA_OPTIONS="-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
```

## 📁 Project Structure

```
next-gen-control-plane/
├── pom.xml                                 # Root parent POM (multi-module)
├── proto/                                  # Shared gRPC contract
│   └── control_plane.proto
├── java-control-plane/                     # Backend: gRPC services, DB, business logic
│   ├── pom.xml                             # Child POM (inherits from root)
│   ├── Dockerfile
│   └── src/main/java/com/nextgen/
│       ├── Main.java                       # CLI entry point (ROLE-based)
│       ├── controlplane/                   # ControlPlane Server
│       │   ├── ControlPlaneServer.java
│       │   ├── ControlPlaneServiceImpl.java
│       │   ├── DashboardApiHandler.java
│       │   ├── HeartbeatMonitor.java
│       │   ├── NodeRecord.java
│       │   └── StaticFileHandler.java
│       ├── agent/                          # Node Agent
│       │   └── NodeAgent.java
│       └── desktop/v2/                     # Backend services & DB (UI removed)
│           ├── db/
│           │   ├── DatabaseManager.java
│           │   ├── entities/
│           │   │   ├── ServerEntity.java
│           │   │   ├── NodeEntity.java
│           │   │   ├── ClusterMembershipEntity.java
│           │   │   └── JoinRequestEntity.java
│           │   └── repositories/
│           │       ├── ServerRepository.java
│           │       ├── NodeRepository.java
│           │       ├── ClusterMembershipRepository.java
│           │       └── JoinRequestRepository.java
│           ├── grpc/
│           │   ├── ClusterManagerServiceImpl.java
│           │   ├── ControlPlaneServiceImpl.java
│           │   └── PredictorServiceImpl.java
│           ├── service/
│           │   └── RegistrationService.java
│           └── util/
│               ├── TlsCertificateGenerator.java
│               └── SystemSpecDetector.java
├── desktop-ui/                             # Phase-2 Desktop UI (JavaFX)
│   ├── pom.xml                             # Child POM (JavaFX, gRPC client)
│   └── src/main/java/com/nextgen/desktop/ui/
│       ├── DesktopApp.java                 # JavaFX Application entry point
│       ├── client/                         # gRPC clients
│       │   ├── GrpcConnectionManager.java
│       │   ├── ControlPlaneClient.java
│       │   └── PredictorClient.java
│       ├── model/                          # Observable data models
│       │   ├── NodeModel.java
│       │   ├── TaskModel.java
│       │   └── ClusterSummary.java
│       ├── service/                        # UI services
│       │   ├── NodeMonitoringService.java
│       │   ├── TaskExecutionService.java
│       │   └── ThemeService.java
│       ├── view/                           # Screens
│       │   ├── MainWindow.java
│       │   ├── Sidebar.java
│       │   ├── DashboardView.java
│       │   ├── NodeManagementView.java
│       │   ├── TaskSubmissionView.java
│       │   ├── MonitoringView.java
│       │   ├── SettingsView.java
│       │   └── NodeCard.java
│       └── viewmodel/                      # (reserved for future ViewModels)
├── python-predictor/                       # Python ML service
│   ├── predictor_service.py
│   ├── requirements.txt
│   └── Dockerfile
├── dashboard/                              # Web UI (served by ControlPlane)
│   ├── html/
│   ├── css/styles.css
│   ├── js/app.js
│   └── Dockerfile
├── scripts/                                # Utilities
├── docs/                                   # Documentation
├── docker-compose.yml
├── README.md
├── DEVELOPMENT.md                          # This file
├── CHANGELOG.md
├── CONTRIBUTING.md
└── LICENSE
```

## 🔄 Git Workflow

### Branch Naming

```
feature/add-health-checks
bugfix/dashboard-loading
refactor/predictor-service
docs/api-examples
```

### Commit Messages (Conventional Commits)

```bash
feat: add health check endpoints
fix: resolve dashboard CSS loading issue
docs: update API documentation
refactor: simplify heartbeat monitor logic
test: add NodeRecord unit tests
```

### Pull Request Process

1. Create feature branch: `git checkout -b feature/name`
2. Make changes and commit
3. Push branch: `git push -u origin feature/name`
4. Open PR with description
5. Ensure CI passes
6. Request review
7. Merge after approval

## 📝 Code Standards

### Java
- Java 21 features (switch expressions, var, etc.)
- Compiler configured with `--release 21` for proper system module resolution
- No `System.out.println()` — use SLF4J logging
- All metrics must be **real OS readings** — never fake
- Thread-safe by default (volatile, ConcurrentHashMap, AtomicInteger)
- Use `com.sun.management.OperatingSystemMXBean` for CPU/memory metrics
- Use `URI.create().toURL()` instead of deprecated `new URL(String)`

### Python
- Type hints where possible
- f-strings for formatting
- `logging` module (not print)
- Follow PEP 8 style

### General
- No random/fake data in production code
- All public methods need tests
- Update README.md for user-facing changes
- Update DEVELOPMENT.md for dev-facing changes

## 🌐 Environment Variables

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `ROLE` | `server` | Main.java | `server` = ControlPlane, `agent` = NodeAgent |
| `NODE_ID` | `unknown` | NodeAgent | Unique identifier for this node |
| `CONTROL_PLANE_HOST` | `control-plane` | NodeAgent | Hostname of ControlPlane |
| `PREDICTOR_HOST` | `predictor` | ControlPlane | Hostname of Predictor service |

## 🚀 Releasing

### Version Bump Process

1. Update `pom.xml` version: `<version>X.Y.Z</version>`
2. Update `README.md` version badge
3. Update `CHANGELOG.md` with changes
4. Create git tag: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
5. Push tag: `git push origin vX.Y.Z`
6. GitHub Actions will build and create release

### Current Version

**v1.0.0** — V2 Complete (Glassmorphism UI, SQLite Persistence, Registration Flow, Bidirectional Streaming)

## 📚 Additional Resources

- [README.md](README.md) — Project overview and quick start
- [CHANGELOG.md](CHANGELOG.md) — Version history
- [CONTRIBUTING.md](CONTRIBUTING.md) — Contribution guidelines
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Architecture decisions

## ❓ Getting Help

- Open an issue on GitHub
- Check existing issues and discussions
- Review troubleshooting section above
- Check logs with `docker compose logs -f [service]`

---

**Last Updated:** April 2026 | **Maintainers:** Team Next-Gen