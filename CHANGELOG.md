# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned
- Consensus protocol (Raft or Paxos) for fault tolerance
- Leader election mechanism
- Real ML model in Predictor service
- WebSocket support for dashboard
- Alert system for node failures
- Multi-region deployment support
- Automated node recovery
- Task queue persistence

## [2.0.0] - 2026-04-30

### Added - V2 Desktop Application Complete

#### Core V2 Features
- **Desktop Application V2** - JavaFX-based GUI with glassmorphism design
  - Dual mode: Server Mode and Node Mode
  - SQLite persistence (Hibernate ORM)
  - Registration flows with auto-detected system specs
  - Real-time dashboards with translucent UI
  - No Docker required - runs directly on host OS

#### Database Layer
- **SQLite Database** - Embedded persistence at `~/.nextgen-cp-v2/cluster.db`
  - ServerEntity - Server registration data
  - NodeEntity - Node registration data
  - ClusterMembershipEntity - Node-Server relationships
  - JoinRequestEntity - Pending join approvals
- **Repository Pattern** - CRUD operations with Hibernate
- **Transaction Support** - ACID operations with rollback capability

#### Security
- **TLS Certificate Generation** - Self-signed RSA-2048 certificates
- **Connection Tokens** - 128-bit secure random tokens
- **Approval Workflow** - Manual server approval for node joining

#### gRPC V2 Services
- **ClusterManager Service** - Enhanced cluster management
  - `RequestJoin` - Secure node join requests
  - `RespondToJoin` - Approve/reject workflow
  - `EstablishStream` - Bidirectional streaming
  - `GetPendingJoinRequests` - View pending approvals
  - `GetClusterNodes` - View cluster members
  - `SendCommand` - Server-to-node commands

#### Testing
- **Comprehensive Test Suite** - 15+ new test classes
  - Entity unit tests (4 classes)
  - Repository tests (4 classes)
  - Service tests (RegistrationService)
  - Utility tests (SystemSpecDetector, TlsCertificateGenerator)
  - gRPC service tests (ClusterManagerServiceImpl)
  - Integration tests (End-to-end, gRPC, Database)
- **Test Coverage** - 80%+ line coverage for V2

#### Documentation
- **Updated Architecture** - V2 architecture diagrams and docs
- **Testing Guide** - Complete testing documentation
- **Development Guide** - Updated build and run instructions

### Changed
- Default JavaFX main class: `com.nextgen.desktop.v2.DesktopAppV2`
- Shade plugin main class updated to V2 entry point
- Removed Lombok dependency (manual getters/setters)
- Migrated from in-memory registry to SQLite persistence

### Fixed
- Maven lifecycle phase error for `mvn javafx:run`
- All compilation warnings resolved
- All values use real OS readings (no random/fake data)

## [0.0.1] - 2026-04-24

### Added - Phase-1 Complete Release

#### Core Features
- **Control Plane Server** - Java 21 gRPC service for node management
  - Node registration with retry logic (10 attempts, 2s delay)
  - Heartbeat processing with real OS metrics
  - Round-robin task scheduling algorithm
  - In-memory node registry (ConcurrentHashMap)
  - Heartbeat monitor with 6s timeout → SUSPECTED_DEAD status
  - Dashboard HTTP API (/api/nodes) serving JSON
  - Prometheus metrics endpoint (:9090)

- **Node Agent** - Java 21 service running on each node
  - Real CPU usage via OperatingSystemMXBean.getCpuLoad()
  - Real memory usage via OperatingSystemMXBean.getTotalMemorySize()
  - Automatic registration with Control Plane
  - Heartbeat every 2 seconds
  - Prometheus metrics per node (:9090)
  - Graceful retry on connection failures

- **Predictor Service** - Python 3.11 gRPC stub
  - GetPrediction RPC endpoint
  - Fixed prediction values for Phase-1 (0.45, 0.12)
  - ML-ready architecture for Phase-3
  - Prometheus metrics endpoint (:9091)

- **Dashboard** - Web UI with nginx
  - Real-time charts using Chart.js
  - Three pages: Overview, Performance, Individual Nodes
  - Auto-refresh every 2 seconds
  - Live CPU and memory visualization
  - Connection status indicator
  - Responsive design with Inter font

#### Testing
- Comprehensive unit test suite
  - `NodeRecordTest` - Data structure validation, thread safety
  - `HeartbeatMonitorTest` - Timeout detection, recovery logic
  - `ControlPlaneServiceImplTest` - gRPC service testing with in-process server
  - `DashboardApiHandlerTest` - HTTP handler testing with mocks
- JaCoCo test coverage reporting (minimum 60%, target 80%+)
- Integration test script (`scripts/integration-test.py`)
  - Verifies 3 nodes register
  - Validates heartbeat flow
  - Tests round-robin scheduling
  - Checks predictor integration

#### Documentation
- **README.md** - Comprehensive project overview with
  - Hero section with badges
  - Feature highlights
  - ASCII architecture diagram
  - Quick start guide
  - Testing instructions
  - Deployment guides
- **DEVELOPMENT.md** - Developer guide with
  - Local development setup
  - Debugging tips
  - Common issues and solutions
  - Project structure
  - Git workflow
  - Code standards
- **ARCHITECTURE.md** - Architecture decisions and data flow
- This **CHANGELOG.md** file

#### DevOps & CI/CD
- Docker Compose orchestration
  - 5 services: control-plane, node1, node2, node3, predictor
  - Custom network `nextgen-net`
  - Health checks for control-plane
  - Resource isolation
- Maven build configuration
  - Protobuf code generation
  - Fat JAR creation with shade plugin
  - JaCoCo coverage reporting
  - Surefire test execution
- GitHub Actions ready structure

#### Code Quality
- Java 21 features (switch expressions, enhanced instanceof)
- Thread-safe implementation (volatile, AtomicInteger, ConcurrentHashMap)
- Zero fake data - all metrics from real OS readings
- Structured logging with SLF4J
- Proper error handling and graceful degradation
- CORS support for dashboard API
- JSON escaping in API responses

### Fixed
- Java version mismatch in pom.xml (17 → 21)
- Dashboard CSS/JS path issues in HTML files
- Consistent formatting and code style

### Technical Details
- **Lines of Code:** ~2,500 Java, ~500 Python, ~1,000 JavaScript/CSS
- **Test Coverage:** 80%+ overall
- **Build Time:** ~30 seconds (Maven + Docker)
- **Startup Time:** ~15 seconds (all services)
- **Memory Usage:** ~500MB total (all containers)

### Dependencies
- Java: gRPC 1.68, Protobuf 3.25.5, Prometheus simpleclient 0.16.0, JUnit 5.10.2, Mockito 5.11.0
- Python: grpcio, prometheus-client, protobuf
- Frontend: Chart.js 4.4.7, Inter font

[0.0.1]: https://github.com/YOUR_USERNAME/next-gen-control-plane/releases/tag/v0.0.1
