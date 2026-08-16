# Development Setup — Next-Gen Control Plane

**Version:** v3.0.0 | **Status:** Raft consensus, distributed Docker-Compose execution, the `nx` CLI,
real ML risk/load models, and a local account system are all live — see CHANGELOG.md for the full list.
This file covers day-to-day build/run/test commands; README.md is the up-to-date source of truth for
what the project actually does and how each piece works.

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

# Start everything (Docker mode on server laptop)
docker compose up --build

```

This will:
1. Build Java fat JAR (ControlPlane + NodeAgent) via Maven
2. Build Python predictor with gRPC stubs
3. Start the control plane, predictor, and Prometheus on `nextgen-net` Docker network
4. Dashboard API (JSON) available at http://localhost:8085/api/nodes

## 🖥️ Desktop Application

The desktop app is a single JavaFX `WebView` (`DesktopApp.java`, `desktop-ui` module) loading a local
HTTP server (`LocalUiServer`) that serves a real HTML/CSS/JS frontend from
`desktop-ui/src/main/resources/web/` — not a JavaFX view-class UI. `LocalUiServer` talks to the
ControlPlane and Predictor services over gRPC on the JVM side and exposes that data to the frontend via
a local REST + Server-Sent-Events API; nothing in the browser layer talks gRPC directly. Includes a
local, on-device account system (email/password and GitHub OAuth device-flow login, password reset via
one-time recovery codes) and a Docker-Desktop-style sidebar/topbar layout with dark/light theming.

### Build & Launch

> **One-time setup: a JDK 21 Maven Toolchain.** `javafx-web`'s module descriptor requires the
> `jdk.jsobject` module (backs WebView's Java↔JavaScript bridge), which was removed from the
> JDK in later feature releases (confirmed gone by JDK 26; present through at least JDK 21) —
> so `desktop-ui` needs to actually *run* on JDK 21 regardless of whatever JDK you otherwise
> use day to day (compiling is unaffected either way; only running needs the module). This is
> handled for you automatically by Maven Toolchains — `desktop-ui/pom.xml` requires one, and
> `javafx-maven-plugin` picks it up and launches the app with it — **without changing your
> system's default `java`/`JAVA_HOME` at all.** You just need to tell Maven where a JDK 21
> install lives, once, on each machine you build this on:
>
> 1. Install a JDK 21 if you don't already have one (e.g. [Eclipse Temurin 21](https://adoptium.net)).
> 2. Copy `desktop-ui/toolchains.xml.sample` to `~/.m2/toolchains.xml` (merge it in if that
>    file already exists) and edit `<jdkHome>` to point at your JDK 21 install.
>
> After that, plain `mvn javafx:run` (below) just works. Skipping this step fails with
> `Cannot find matching toolchain` — a clear, actionable error pointing back here, not the
> cryptic `FindException` you'd get without the toolchain requirement in place at all.

#### Option 1: Maven JavaFX Plugin (Recommended for Development)

```bash
#on node laptop
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

### Desktop App Features

See [README.md's Algorithms & Predictive Intelligence section](README.md#-algorithms--predictive-intelligence)
and its Desktop Application walkthrough for the full, current feature list and screenshots-in-words —
that's the maintained description. In short: account/login, a Docker-Desktop-style dashboard, node and
task/job management (including submitting real distributed Docker-Compose jobs via the same code path
the `nx` CLI uses), live monitoring via SSE, a Containers/Images/Volumes/Networks view backed by real
per-node Docker inventory, and settings. Predictor responses honestly report `model_trained=false`
until an operator has actually run training at least once — never a fabricated prediction.

### Entry Points

| Entry Point | Class | Module | Purpose |
|-------------|-------|--------|---------|
| Desktop GUI | `com.nextgen.desktop.ui.DesktopApp` | `desktop-ui` | JavaFX WebView hosting the HTML/JS UI |
| CLI / Docker | `com.nextgen.Main` | `java-control-plane` | ROLE-based CLI entry (server/agent/pki-init) |
| `nx` CLI | `com.nextgen.cli.Cli` | `cli` | Submit/inspect distributed jobs and Docker resources from a terminal |

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
java -jar target/control-plane-1.0-SNAPSHOT-all.jar
```

**Bash (Linux/Mac):**
```bash
cd java-control-plane
ROLE=server PREDICTOR_HOST=localhost java -jar target/control-plane-1.0-SNAPSHOT-all.jar
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
java -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

**Bash:**
```bash
ROLE=agent NODE_ID=node1 CONTROL_PLANE_HOST=localhost \
  java -jar java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar
```

**Expected output:**
```
🖥  NodeAgent 'node1' starting...
Hostname: your-host, IP: 192.168.x.x
📊 Prometheus metrics on port 9090
✅ Registered with ControlPlane: status=REGISTERED
💓 Heartbeat #1: cpu=12.3%, mem=45.6% → OK
```

### Step 3b: Submit a real distributed job with the `nx` CLI

With the control plane and at least one node agent running (Steps 2-3), submit a real multi-service
project and watch it run — live-verified end to end, including on nodes sharing one machine:

```bash
java -jar cli/target/nextgen-cli-1.0-SNAPSHOT.jar up my-compose.yml \
  --project demo --control-plane localhost:50051
```

No `nx enrol` needed here — the CLI talks plaintext by default, matching `TLS_ENABLED=false` above; add
`--tls` (after `nx enrol --token ... --control-plane ...`) only if the server was started with
`TLS_ENABLED=true`. `nx ps`/`nx down`/`nx nodes`/`nx logs` all take the same `--control-plane` flag.

Prototyping several node agents as separate processes on this ONE machine has one topology-specific
gotcha: pass `RELAY_ADVERTISED_HOST=host.docker.internal` to the server in Step 2 (not the default
`localhost`) so a container can reach the relay listener on its own host — see
[ARCHITECTURE.md's Distributed container execution section](ARCHITECTURE.md#distributed-container-execution).
Genuinely separate physical nodes need no such override.

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
| Dashboard API | http://localhost:8085/api/nodes | JSON node data (no bundled HTML frontend — the desktop app is the maintained UI) |
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
java -jar target/control-plane-1.0-SNAPSHOT-all.jar
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

Launch the Desktop UI on each node laptop (`cd desktop-ui && mvn javafx:run`) and join it to the
server. The exact join flow (enrolment token handling, mTLS vs. plaintext, the current screen names) is
maintained in one place — see
[README's Physical Cluster on a Shared Wi-Fi / Mobile Hotspot section](README.md#-physical-cluster-on-a-shared-wi-fi--mobile-hotspot)
rather than duplicating it here, since a second copy is exactly what went stale last time.

The node will:
- Connect to the server via gRPC on port 50051 (or via `CONTROL_PLANE_ENDPOINTS` for a Raft-aware,
  multi-replica cluster)
- Register itself with the ControlPlane
- Start sending heartbeats with real CPU/memory/battery metrics
- Appear in the Desktop UI's Nodes view

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
- [ ] Desktop GUI launches with `mvn javafx:run` and its Nodes view shows individual node details
- [ ] `http://localhost:8085/api/nodes` returns real JSON (no bundled HTML frontend lives here anymore)
- [ ] All expected nodes registered (check logs)
- [ ] Heartbeats flowing every 2 seconds
- [ ] Prometheus metrics accessible on both the control plane's and each node's metrics port

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
**Fix:** Ensure the JDK 21 Maven Toolchain is set up (see the callout above) and, on headless Linux, run
a node/server process directly via `ROLE=agent`/`ROLE=server` instead of the desktop app.

**Issue:** `Cannot find matching toolchain`
**Fix:** The one-time JDK 21 Maven Toolchain setup above wasn't done — `desktop-ui` needs it to *run*
(compiling is unaffected).

### Enable Debug Logging

Java services use SLF4J simple logger. Set level in environment:
```bash
# Debug mode
_JAVA_OPTIONS="-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
```

## 📁 Project Structure

```
next-gen-control-plane/
├── pom.xml                                 # Root parent POM (multi-module: java-control-plane, desktop-ui, cli)
├── proto/                                  # Shared gRPC contract
│   └── control_plane.proto
├── java-control-plane/                     # Backend: control plane server + node agent
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/nextgen/
│       ├── Main.java                       # ROLE-based CLI entry (server/agent/pki-init)
│       ├── controlplane/                   # Control plane server
│       │   ├── ControlPlaneServer.java, ControlPlaneServiceImpl.java, NodeRegistry.java, ...
│       │   ├── raft/                       # Raft consensus (leader election, log replication, WAL)
│       │   ├── risk/                       # Rule-based + ML (XGBoost) failure-risk scoring
│       │   ├── capacity/                   # Capability-aware job splitting
│       │   ├── task/, job/                 # Task/job dispatch, Docker-Compose execution scheduling
│       │   ├── docker/                     # Real per-node Docker inventory + container control
│       │   └── training/                   # Real outcome/snapshot logging for model training
│       ├── agent/                          # NodeAgent — registration, heartbeats, task execution
│       │   ├── task/                       # Task execution (prime counting, Docker Compose services)
│       │   └── docker/                     # Docker inventory collection/reporting
│       └── security/                       # mTLS, CertificateAuthority, enrolment, policy
├── desktop-ui/                             # Desktop app: JavaFX WebView hosting a real HTML/JS UI
│   ├── pom.xml
│   └── src/main/java/com/nextgen/desktop/ui/
│       ├── DesktopApp.java                 # JavaFX Application entry point
│       ├── account/                        # Local account system (login, GitHub OAuth, recovery codes)
│       ├── client/                         # gRPC clients to the control plane/predictor
│       ├── server/                         # LocalUiServer + route/stream handlers the WebView calls
│       ├── service/, model/, profile/      # Polling services, observable models, local JSON stores
│   └── src/main/resources/web/             # The actual UI: HTML/CSS/JS served to the WebView
│       ├── index.html, css/, js/views/, js/components/
├── cli/                                    # The `nx` CLI (nextgen-cli) — docker-compose-style commands
│   └── src/main/java/com/nextgen/cli/Cli.java, ComposeFileParser.java
├── python-predictor/                       # Real ML: XGBoost risk classifier + LSTM load forecaster
│   ├── predictor_service.py, train_risk_model.py, auto_retrain.py, features.py, ...
│   └── requirements.txt, Dockerfile
├── examples/                               # Runnable example projects (e.g. hello-cluster compose)
├── datasets/                                # Local-only training data (gitignored, not distributed)
├── scripts/                                 # Utilities (integration/e2e test scripts)
├── deploy/                                  # Prometheus config for the Raft-aware compose topology
├── docker-compose.yml, docker-compose.raft.yml
├── README.md, DEVELOPMENT.md, CHANGELOG.md, CONTRIBUTING.md, LICENSE, ARCHITECTURE.md, ALGORITHMS.md
```

## 🔄 Git Workflow

### Branch Naming

```
feature/add-health-checks
bugfix/heartbeat-timeout
refactor/predictor-service
docs/api-examples
```

### Commit Messages (Conventional Commits)

```bash
feat: add health check endpoints
fix: resolve heartbeat timeout edge case
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

The four most basic ones, to get a plain single-node deployment running:

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `ROLE` | `server` | Main.java | `server` / `agent` / `pki-init` |
| `NODE_ID` | `unknown` | NodeAgent | Unique identifier for this node |
| `CONTROL_PLANE_HOST` | `control-plane` | NodeAgent | Hostname of ControlPlane |
| `PREDICTOR_HOST` | `predictor` | ControlPlane | Hostname of Predictor service |

There are several dozen more — TLS/mTLS, Raft, ML risk scoring, auto-retrain, capacity scoring, Docker
relay ports, and more, most opt-in and off by default. **README.md's own environment variable
reference tables are the maintained, complete list** — kept here would just go stale again, exactly
like this section did before.

## 🚀 Releasing

### Version Bump Process

1. Update `pom.xml` version: `<version>X.Y.Z</version>`
2. Update `README.md` version badge
3. Update `CHANGELOG.md` with changes
4. Create git tag: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
5. Push tag: `git push origin vX.Y.Z`
6. GitHub Actions will build and create release

### Current Version

See CHANGELOG.md's `[Unreleased]` and `[3.0.0]` entries for the current, accurate feature list —
duplicating a version summary here is exactly the kind of second copy that goes stale.

## 📚 Additional Resources

- [README.md](README.md) — Project overview and quick start
- [CHANGELOG.md](CHANGELOG.md) — Version history
- [CONTRIBUTING.md](CONTRIBUTING.md) — Contribution guidelines
- [ARCHITECTURE.md](ARCHITECTURE.md) — Architecture decisions, including the full trust model and Raft's consensus/replication design
- [ALGORITHMS.md](ALGORITHMS.md) — Every algorithm this project runs, with real formulas and evidence

## ❓ Getting Help

- Open an issue on GitHub
- Check existing issues and discussions
- Review troubleshooting section above
- Check logs with `docker compose logs -f [service]`

---

**Last Updated:** August 2026 | **Maintainers:** Team Next-Gen