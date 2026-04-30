# Development Setup — Next-Gen Control Plane

**Version:** v1.0.0 | **Status:** V2 Complete

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

## 🖥️ Desktop Application (V2 - Glassmorphism UI)

The V2 desktop application features a modern glassmorphism UI with SQLite persistence and registration flows.

### Build & Launch

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

This ensures that the database is in a clean state for the next launch, preventing "file is locked" errors that can occur when the application is terminated abruptly or when multiple instances are run.

### V2 Desktop App Features

- **Glassmorphism UI** — Modern dark theme with neon accents and glass-like panels
- **Registration Flow** — Server/Node registration with auto-detected system specs
- **TLS Certificate Generation** — Automatic self-signed certificate creation for secure communication
- **Connection Tokens** — Secure token-based node joining with approval workflow
- **Server Dashboard** — Real-time node monitoring, join request approval panel
- **Node Dashboard** — Server discovery, join flow, membership management
- **SQLite Database** — Embedded persistence at `~/.nextgen-cp-v2/cluster.db`
- **Auto-refresh** — 5-second auto-refresh for real-time metrics

### V2 Control Flow

**Registration Flow:**
1. User launches V2 app → `RegistrationView`
2. Chooses Server or Node mode
3. `RegistrationDialog` auto-detects system specs via `SystemSpecDetector`
4. Generates TLS certificate via `TlsCertificateGenerator`
5. Persists to SQLite database via `RegistrationService`
6. Launches appropriate dashboard (`ServerDashboard` or `NodeDashboard`)

**Join Request Flow:**
1. Node enters connection token in `NodeDashboard`
2. gRPC `RequestJoin` to server's `ClusterManagerServiceImpl`
3. Server creates `JoinRequestEntity` (PENDING)
4. Auto-approval (or manual via dashboard's approve/reject buttons)
5. Creates `ClusterMembershipEntity` (APPROVED)
6. Returns server certificate to node

**Dashboard Flow:**
1. Dashboard loads with 5-second auto-refresh timeline
2. Queries database via JPA repositories for nodes/memberships
3. Updates tables with real-time metrics (CPU, memory, heartbeat)
4. Approve/reject actions update database status

### Database Schema

**Location:** `~/.nextgen-cp-v2/cluster.db`

**Tables:**
- `servers` — Server configurations, specs, TLS certificates, connection tokens
- `nodes` — Node configurations, specs, TLS certificates
- `cluster_memberships` — Node-server relationships, status, metrics
- `join_requests` — Join request workflow, approval status

### Entry Points

| Entry Point | Class | Purpose |
|-------------|-------|---------|
| V2 Desktop GUI | `com.nextgen.desktop.v2.DesktopAppV2` | Glassmorphism UI with registration flow |
| V1 Desktop GUI | `com.nextgen.desktop.DesktopLauncher` | JavaFX GUI with CLI fallback |
| CLI / Docker | `com.nextgen.Main` | ROLE-based CLI entry (server/agent) |

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
├── proto/                                  # gRPC contract
│   └── control_plane.proto
├── java-control-plane/                     # Java backend + Desktop App
│   ├── pom.xml                             # Maven config (gRPC, JavaFX, Prometheus, SQLite, Hibernate)
│   ├── Dockerfile
│   └── src/main/java/com/nextgen/
│       ├── Main.java                       # CLI entry point (ROLE-based)
│       ├── controlplane/                   # ControlPlane Server
│       │   ├── ControlPlaneServer.java     # gRPC + HTTP + Prometheus startup
│       │   ├── ControlPlaneServiceImpl.java# gRPC service (4 RPCs)
│       │   ├── DashboardApiHandler.java    # /api/nodes JSON endpoint
│       │   ├── HeartbeatMonitor.java       # Dead node detection (6s timeout)
│       │   ├── NodeRecord.java             # Thread-safe node data record
│       │   └── StaticFileHandler.java      # Dashboard static file server
│       ├── agent/                          # Node Agent
│       │   └── NodeAgent.java              # Real OS metrics + heartbeats
│       ├── desktop/                        # V1 JavaFX Desktop Application
│       │   ├── DesktopLauncher.java         # GUI/CLI entry point
│       │   ├── DesktopApp.java              # Main JavaFX Application
│       │   ├── ProcessService.java          # Server/Node process lifecycle
│       │   ├── OverviewView.java            # Cluster overview dashboard
│       │   ├── NodeStatusView.java          # Node agent status view
│       │   ├── NodeConfig.java              # Node mode configuration
│       │   ├── ServerConfig.java            # Server mode configuration
│       │   ├── NodeConfigDialog.java        # Node config dialog
│       │   ├── ServerConfigDialog.java      # Server config dialog
│       │   ├── model/                       # Data models (NodeStatus, configs)
│       │   ├── repository/                  # NodeRepository (observable data)
│       │   ├── service/                     # Background services
│       │   │   ├── MetricsService.java      # System metrics collection
│       │   │   ├── ApiPollingService.java    # Dashboard API poller
│       │   │   ├── ServerProcessService.java# Server lifecycle
│       │   │   ├── NodeProcessService.java  # Node lifecycle
│       │   │   ├── ConfigurationService.java# JSON config persistence
│       │   │   └── ErrorHandler.java        # Error handling
│       │   ├── viewmodel/                   # MVVM ViewModels
│       │   └── exception/                   # Custom exceptions
│       └── desktop/v2/                      # V2 Desktop App (Glassmorphism UI)
│           ├── DesktopAppV2.java             # V2 main entry point
│           ├── db/                           # Database layer
│           │   ├── DatabaseManager.java      # SQLite + Hibernate initialization
│           │   ├── entities/                 # JPA entities
│           │   │   ├── ServerEntity.java
│           │   │   ├── NodeEntity.java
│           │   │   ├── ClusterMembershipEntity.java
│           │   │   └── JoinRequestEntity.java
│           │   └── repositories/             # JPA repositories
│           │       ├── ServerRepository.java
│           │       ├── NodeRepository.java
│           │       ├── ClusterMembershipRepository.java
│           │       └── JoinRequestRepository.java
│           ├── grpc/                         # gRPC services
│           │   └── ClusterManagerServiceImpl.java  # Join requests, streaming, commands
│           ├── service/                      # Business logic
│           │   └── RegistrationService.java   # Server/Node registration
│           ├── util/                         # Utilities
│           │   ├── TlsCertificateGenerator.java  # Certificate/token generation
│           │   └── SystemSpecDetector.java       # System spec detection
│           └── view/                         # UI components
│               ├── registration/             # Registration dialogs
│               │   ├── RegistrationView.java
│               │   ├── ServerRegistrationDialog.java
│               │   └── NodeRegistrationDialog.java
│               └── dashboard/                # Server/Node dashboards
│                   ├── ServerDashboard.java
│                   └── NodeDashboard.java
├── python-predictor/                       # Python ML service
│   ├── predictor_service.py
│   ├── requirements.txt
│   └── Dockerfile
├── dashboard/                              # Web UI (served by ControlPlane)
│   ├── html/
│   │   ├── overview.html
│   │   ├── performance.html
│   │   └── nodes.html
│   ├── css/styles.css
│   ├── js/app.js
│   ├── nginx.conf
│   └── Dockerfile
├── scripts/                                # Utilities
│   ├── integration-test.py
│   ├── monitor.py
│   └── start-cluster.sh
├── docs/                                   # Documentation
│   ├── ARCHITECTURE.md
│   └── codingpromt.md
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