# 6.2 📸 Code Snippets

> **Next-Gen Control Plane — Annotated Source Code Reference**
>
> This document provides annotated code snippets from every layer of the system, showing **exactly how each technology is used** in production code. Study these to understand the codebase quickly.

---

## 📋 Technology Overview

The following table summarizes every major technology used in this project, where it appears in the codebase, and how it contributes to the system.

| # | Technology | Version | Where Used | What It Does |
|---|-----------|---------|------------|--------------|
| 1 | **Protocol Buffers** | 3.25.5 | `proto/control_plane.proto` | Defines the gRPC service contract and message schemas shared between Java and Python |
| 2 | **gRPC (Java)** | 1.68.0 | `ControlPlaneServiceImpl`, `NodeAgent` | High-performance RPC framework for node registration, heartbeats, and task submission |
| 3 | **gRPC (Python)** | latest | `predictor_service.py` | Python server implementing the ML Predictor service |
| 4 | **Java 21** | 21 | All Java modules | Switch expressions, `var`, virtual threads support, modern API usage |
| 5 | **JavaFX** | 21.0.2 | `desktop-ui` module | Desktop GUI with dark/light themes, live charts, and node management |
| 6 | **SLF4J** | 2.0.16 | All Java classes | Structured logging with `{}` placeholders — no `System.out.println()` |
| 7 | **Prometheus** | 0.16.0 | `ControlPlaneServiceImpl`, `NodeAgent`, `predictor_service.py` | Counter/Gauge/Histogram metrics exported on `:9090` and `:9091` |
| 8 | **Hibernate + SQLite** | 6.4.4 / 3.46 | `DatabaseManager`, V2 entities | Local persistence with WAL mode for cluster state (servers, nodes, memberships) |
| 9 | **ConcurrentHashMap** | JDK 21 | `ControlPlaneServer`, `HeartbeatMonitor` | Thread-safe in-memory node registry shared across gRPC threads |
| 10 | **Docker Compose** | v2.x | `docker-compose.yml` | Multi-container orchestration of all 5 services on a shared bridge network |
| 11 | **OperatingSystemMXBean** | JDK 21 | `NodeAgent` | Real OS-level CPU and memory readings — never random/fake data |
| 12 | **JUnit 5 + JaCoCo** | 5.10 / 0.8.12 | `src/test/` | Unit testing with 60%+ coverage enforcement |

---

## Snippet 1 — Proto Service Definition

**File:** `proto/control_plane.proto`

The `.proto` file is the **single source of truth** for all RPC contracts. Both Java and Python generate stubs from this file.

```protobuf
syntax = "proto3";
package nextgen.v1;

option java_package = "com.nextgen.proto";
option java_outer_classname = "ControlPlaneProto";

// ── Control Plane Service (Java, port 50051) ──
service ControlPlaneService {
  rpc RegisterNode  (NodeInfo)          returns (RegisterResponse);
  rpc SendHeartbeat (HeartbeatRequest)  returns (HeartbeatResponse);
  rpc SubmitTask    (TaskRequest)       returns (TaskResponse);
  rpc GetNodes      (Empty)            returns (NodeList);
}

// ── Predictor Service (Python, port 50052) ──
service PredictorService {
  rpc GetPrediction (PredictionRequest) returns (PredictionResponse);
}

// ── V2 Cluster Manager — bidirectional streaming ──
service ClusterManager {
  rpc RequestJoin(JoinRequest) returns (JoinResponse);
  rpc EstablishStream(stream NodeMessage) returns (stream ServerMessage);
  rpc SendCommand(CommandRequest) returns (CommandResponse);
}
```

> **How it works:** `protobuf-maven-plugin` auto-generates Java classes at compile time into `target/generated-sources/protobuf/`. Python stubs are generated via `grpc_tools.protoc`. The `stream` keyword on `EstablishStream` enables real-time bidirectional communication.

---

## Snippet 2 — Unified Entry Point

**File:** `java-control-plane/src/main/java/com/nextgen/Main.java`

The single JAR serves both roles — **server** or **agent** — based on the `ROLE` environment variable.

```java
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("ROLE", "server");
        LOG.info("=== Next-Gen Control Plane | Role: {} ===", role.toUpperCase());

        try {
            switch (role.toLowerCase()) {
                case "server" -> {
                    LOG.info("Starting ControlPlane server...");
                    ControlPlaneServer.start();
                }
                case "agent" -> {
                    LOG.info("Starting NodeAgent...");
                    NodeAgent.start();
                }
                default -> {
                    LOG.error("Unknown ROLE '{}'. Set ROLE=server or ROLE=agent.", role);
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            LOG.error("Fatal error during startup", e);
            System.exit(1);
        }
    }
}
```

> **How it works:** Java 21 switch expressions (`case "server" ->`) replace verbose `if-else` chains. The same Docker image runs both ControlPlane and NodeAgent — only the `ROLE` env var changes. Default is `"server"` so the image works standalone.

---

## Snippet 3 — ControlPlane Server Bootstrap

**File:** `java-control-plane/src/main/java/com/nextgen/controlplane/ControlPlaneServer.java`

This class wires together **gRPC, Prometheus, HTTP dashboard, and the heartbeat monitor** into a single startup sequence.

```java
public static void start() throws IOException, InterruptedException {
    // 1. Shared thread-safe registry
    ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();

    // 2. Heartbeat monitor daemon — detects dead nodes
    Thread monitorThread = new Thread(new HeartbeatMonitor(registry), "heartbeat-monitor");
    monitorThread.setDaemon(true);
    monitorThread.start();

    // 3. Prometheus JVM metrics on :9090
    DefaultExports.initialize();
    HTTPServer metricsServer = new HTTPServer.Builder().withPort(9090).build();

    // 4. Dashboard HTTP server on :8085 (static files + JSON API)
    HttpServer dashboardServer = HttpServer.create(new InetSocketAddress(8085), 0);
    dashboardServer.createContext("/", new StaticFileHandler(dashboardBase));
    dashboardServer.createContext("/api/nodes", new DashboardApiHandler(registry));
    dashboardServer.start();

    // 5. gRPC server on :50051
    Server grpcServer = ServerBuilder.forPort(50051)
            .addService(new ControlPlaneServiceImpl(registry))
            .build()
            .start();

    // 6. Graceful shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        grpcServer.shutdown();
        dashboardServer.stop(2);
        metricsServer.close();
    }));

    grpcServer.awaitTermination();
}
```

> **How it works:** The `ConcurrentHashMap` registry is the single source of truth shared by gRPC threads, the heartbeat monitor, and the dashboard API. The daemon thread runs the `HeartbeatMonitor` in the background. `addShutdownHook` ensures clean resource release on `Ctrl+C`.

---

## Snippet 4 — gRPC Service: Node Registration & Task Scheduling

**File:** `java-control-plane/src/main/java/com/nextgen/controlplane/ControlPlaneServiceImpl.java`

The core gRPC service implements **registration, heartbeats, round-robin scheduling, and predictor integration**.

```java
// ── RegisterNode ──
@Override
public void registerNode(NodeInfo request, StreamObserver<RegisterResponse> responseObserver) {
    String id = request.getNodeId();
    NodeRecord record = new NodeRecord(id, request.getIp(), request.getPort(), request.getHostname());
    registry.put(id, record);         // Thread-safe insert
    REGISTRATIONS.inc();              // Prometheus counter
    ACTIVE_NODES.set(registry.size());

    responseObserver.onNext(RegisterResponse.newBuilder()
            .setStatus("REGISTERED").setAssignedId(id).build());
    responseObserver.onCompleted();
}

// ── SubmitTask — Round-robin with Predictor insight ──
@Override
public void submitTask(TaskRequest request, StreamObserver<TaskResponse> responseObserver) {
    TASKS_SUBMITTED.inc();

    // Collect alive nodes only
    List<NodeRecord> aliveNodes = new ArrayList<>();
    for (NodeRecord node : registry.values()) {
        if ("ALIVE".equals(node.getStatus())) aliveNodes.add(node);
    }

    // Round-robin selection
    int idx = Math.abs(roundRobinIndex.getAndIncrement()) % aliveNodes.size();
    NodeRecord selected = aliveNodes.get(idx);

    // Best-effort predictor call (won't fail the task if predictor is down)
    try {
        PredictorServiceGrpc.PredictorServiceBlockingStub stub = getPredictorStub();
        if (stub != null) {
            PredictionResponse pred = stub.getPrediction(PredictionRequest.newBuilder()
                    .setNodeId(selected.getNodeId())
                    .setCpu(selected.getCpuUsage())
                    .setMemory(selected.getMemoryUsage()).build());
        }
    } catch (Exception e) { LOG.warn("Predictor unavailable: {}", e.getMessage()); }

    responseObserver.onNext(TaskResponse.newBuilder()
            .setAssignedNode(selected.getNodeId()).setResult(result).build());
    responseObserver.onCompleted();
}
```

> **How it works:** `StreamObserver<T>` is the gRPC callback pattern — call `onNext()` to send a response, then `onCompleted()` to finish. `AtomicInteger` ensures thread-safe round-robin. The predictor call is wrapped in try-catch so task scheduling never fails even when the ML service is offline.

---

## Snippet 5 — NodeAgent: Real OS Metrics & Heartbeat Loop

**File:** `java-control-plane/src/main/java/com/nextgen/agent/NodeAgent.java`

The agent collects **real CPU/memory readings** from `OperatingSystemMXBean` and sends them every 2 seconds.

```java
// Real OS metrics bean — never fake data
OperatingSystemMXBean osBean = (OperatingSystemMXBean)
        ManagementFactory.getOperatingSystemMXBean();

// Scheduled heartbeat loop (daemon thread)
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "heartbeat-sender");
    t.setDaemon(true);
    return t;
});

scheduler.scheduleAtFixedRate(() -> {
    try {
        double cpuLoad = osBean.getCpuLoad();         // 0.0–1.0
        long totalMem  = osBean.getTotalMemorySize();
        long freeMem   = osBean.getFreeMemorySize();

        float cpuPercent = (cpuLoad < 0) ? 0.0f : (float)(cpuLoad * 100.0);
        float memPercent = (totalMem > 0)
                ? (float)((totalMem - freeMem) * 100.0 / totalMem) : 0.0f;

        // Update Prometheus gauges
        CPU_USAGE.set(cpuPercent);
        MEMORY_USAGE.set(memPercent);

        HeartbeatRequest hb = HeartbeatRequest.newBuilder()
                .setNodeId(nodeId).setCpu(cpuPercent).setMemory(memPercent).build();
        HeartbeatResponse resp = stub.sendHeartbeat(hb);
        HEARTBEAT_COUNT.inc();
    } catch (Exception e) {
        LOG.error("Heartbeat failed: {}", e.getMessage());
    }
}, 0, 2, TimeUnit.SECONDS);
```

> **How it works:** `com.sun.management.OperatingSystemMXBean` provides real process-level CPU load (`getCpuLoad()`) and system memory (`getTotalMemorySize()`, `getFreeMemorySize()`). Values are never random — this is a hard project rule. `ScheduledExecutorService` runs the heartbeat at a fixed 2-second interval.

---

## Snippet 6 — Heartbeat Monitor: Dead Node Detection

**File:** `java-control-plane/src/main/java/com/nextgen/controlplane/HeartbeatMonitor.java`

A background daemon checks every 3 seconds if any node missed its heartbeat window (6 seconds).

```java
public class HeartbeatMonitor implements Runnable {
    private static final long TIMEOUT_MS = 6_000;       // Dead after 6s
    private static final long CHECK_INTERVAL_MS = 3_000; // Check every 3s

    private final ConcurrentHashMap<String, NodeRecord> registry;

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
                checkHeartbeats();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (NodeRecord node : registry.values()) {
            long elapsed = now - node.getLastHeartbeatMillis();
            if (elapsed > TIMEOUT_MS && !"SUSPECTED_DEAD".equals(node.getStatus())) {
                node.setStatus("SUSPECTED_DEAD");
                LOG.warn("⚠ Node '{}' marked SUSPECTED_DEAD ({}ms)", node.getNodeId(), elapsed);
            } else if (elapsed <= TIMEOUT_MS && "SUSPECTED_DEAD".equals(node.getStatus())) {
                node.setStatus("ALIVE");  // Node recovered
                LOG.info("✅ Node '{}' recovered", node.getNodeId());
            }
        }
    }
}
```

> **How it works:** The monitor reads `lastHeartbeatMillis` from each `NodeRecord` (volatile field) and compares against `System.currentTimeMillis()`. If the gap exceeds 6 seconds (3 missed heartbeats), the node is marked `SUSPECTED_DEAD`. Nodes can automatically recover when heartbeats resume.

---

## Snippet 7 — Thread-Safe Node Record

**File:** `java-control-plane/src/main/java/com/nextgen/controlplane/NodeRecord.java`

The data model uses `volatile` fields for cross-thread visibility between gRPC handler threads and the heartbeat monitor.

```java
public class NodeRecord {
    private final String nodeId;       // Immutable — set once at registration
    private final String ip;
    private final int port;
    private final String hostname;

    private volatile float cpuUsage;           // Updated by gRPC thread
    private volatile float memoryUsage;        // Read by HeartbeatMonitor
    private volatile long lastHeartbeatMillis;  // Read by HeartbeatMonitor
    private volatile String status;            // "ALIVE" or "SUSPECTED_DEAD"

    public NodeRecord(String nodeId, String ip, int port, String hostname) {
        this.nodeId = nodeId;
        this.ip = ip;
        this.port = port;
        this.hostname = hostname;
        this.lastHeartbeatMillis = System.currentTimeMillis();
        this.status = "ALIVE";
    }
}
```

> **How it works:** `volatile` ensures that when the gRPC thread writes `cpuUsage` or `lastHeartbeatMillis`, the HeartbeatMonitor thread immediately sees the updated value without needing `synchronized` blocks. Immutable fields (`nodeId`, `ip`) use `final` — they never change after construction.

---

## Snippet 8 — Dashboard REST API

**File:** `java-control-plane/src/main/java/com/nextgen/controlplane/DashboardApiHandler.java`

The API handler builds JSON manually (no Jackson dependency needed) and serves real node data.

```java
public class DashboardApiHandler implements HttpHandler {
    private final ConcurrentHashMap<String, NodeRecord> registry;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS headers for local development
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");

        String json = buildJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String buildJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":").append(System.currentTimeMillis()).append(",\"nodes\":[");
        boolean first = true;
        for (NodeRecord node : registry.values()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"nodeId\":\"").append(node.getNodeId()).append("\",");
            sb.append("\"cpuUsage\":").append(String.format("%.2f", node.getCpuUsage())).append(",");
            sb.append("\"memoryUsage\":").append(String.format("%.2f", node.getMemoryUsage())).append(",");
            sb.append("\"status\":\"").append(node.getStatus()).append("\"}");
        }
        sb.append("],\"summary\":{...}}");
        return sb.toString();
    }
}
```

> **How it works:** Uses JDK's built-in `com.sun.net.httpserver.HttpServer` — no Spring or external HTTP framework needed. CORS headers (`Access-Control-Allow-Origin: *`) allow the dashboard frontend to call this API from any origin. `Cache-Control: no-cache` ensures the browser always fetches fresh data.

---

## Snippet 9 — Python Predictor Service

**File:** `python-predictor/predictor_service.py`

The ML prediction service runs on port 50052 with Prometheus metrics.

```python
class PredictorServiceServicer(pb2_grpc.PredictorServiceServicer):
    """Phase-1 stub: returns hard-coded prediction values."""

    def GetPrediction(self, request, context):
        start = time.time()
        PREDICTION_REQUESTS.inc()

        LOG.info("🔮 GetPrediction: node_id=%s, cpu=%.1f%%, mem=%.1f%%",
                 request.node_id, request.cpu, request.memory)

        # Phase-1: fixed values, real ML will replace this in Phase-3
        response = pb2.PredictionResponse(
            predicted_load=0.45,
            failure_probability=0.12,
            recommendation="HEALTHY — Phase-1 stub prediction",
        )

        PREDICTION_LATENCY.observe(time.time() - start)
        return response


def serve():
    start_http_server(9091)  # Prometheus metrics
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    pb2_grpc.add_PredictorServiceServicer_to_server(PredictorServiceServicer(), server)
    server.add_insecure_port("[::]:50052")
    server.start()
    server.wait_for_termination()
```

> **How it works:** `pb2` and `pb2_grpc` are auto-generated from the same `.proto` file used by Java. `ThreadPoolExecutor(max_workers=4)` limits concurrent RPC handling. Prometheus `Counter` and `Histogram` track request counts and latency. The `[::]` bind address listens on all interfaces (IPv4 + IPv6).

---

## Snippet 10 — V2 Bidirectional Streaming

**File:** `java-control-plane/src/main/java/com/nextgen/desktop/v2/grpc/ClusterManagerServiceImpl.java`

The V2 architecture uses gRPC bidirectional streaming for real-time node-server communication.

```java
@Override
public StreamObserver<NodeMessage> establishStream(StreamObserver<ServerMessage> responseObserver) {
    LOG.info("New bidirectional stream established");

    return new StreamObserver<>() {
        @Override
        public void onNext(NodeMessage nodeMessage) {
            try {
                if (nodeMessage.hasHeartbeat()) {
                    handleHeartbeat(nodeMessage.getHeartbeat());
                } else if (nodeMessage.hasTaskResult()) {
                    handleTaskResult(nodeMessage.getTaskResult());
                } else if (nodeMessage.hasStatus()) {
                    handleStatusUpdate(nodeMessage.getStatus());
                }
            } catch (Exception e) {
                LOG.error("Error processing node message", e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            LOG.error("Stream error", throwable);
        }

        @Override
        public void onCompleted() {
            responseObserver.onCompleted();
        }
    };
}
```

> **How it works:** The method returns a `StreamObserver<NodeMessage>` that receives a continuous stream of messages from the node. The server can also push `ServerMessage` objects back via `responseObserver`. The `oneof payload` in the proto allows multiplexing heartbeats, task results, and status updates over a single persistent connection — far more efficient than separate unary RPCs.

---

## Snippet 11 — SQLite Database Manager

**File:** `java-control-plane/src/main/java/com/nextgen/desktop/v2/db/DatabaseManager.java`

V2 persistence uses Hibernate + SQLite with WAL mode for concurrent access.

```java
public class DatabaseManager {
    private static final String DB_DIR  = System.getProperty("user.home") + "/.nextgen-cp-v2";
    private static final String DB_FILE = DB_DIR + "/cluster.db";

    private void initializeDatabase() {
        // Ensure directory exists
        Files.createDirectories(Paths.get(DB_DIR));

        // Configure Hibernate for SQLite
        Map<String, Object> properties = new HashMap<>();
        properties.put("jakarta.persistence.jdbc.driver", "org.sqlite.JDBC");
        properties.put("jakarta.persistence.jdbc.url", "jdbc:sqlite:" + DB_FILE);
        properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
        properties.put("hibernate.hbm2ddl.auto", "update");  // Auto-create tables
        properties.put("hibernate.connection.url",
                "jdbc:sqlite:" + DB_FILE + "?journal_mode=WAL");

        emf = Persistence.createEntityManagerFactory("nextgen-cp-v2", properties);
    }

    public void shutdown() {
        if (emf != null && emf.isOpen()) emf.close();
        // Clean up WAL files to prevent locked file issues
        Files.deleteIfExists(Paths.get(DB_FILE + "-wal"));
        Files.deleteIfExists(Paths.get(DB_FILE + "-shm"));
    }
}
```

> **How it works:** The database is stored at `~/.nextgen-cp-v2/cluster.db` and auto-created on first launch. `hbm2ddl.auto=update` lets Hibernate create/alter tables automatically from `@Entity` annotations. WAL (Write-Ahead Logging) mode enables concurrent reads while a write is in progress. On shutdown, WAL files are cleaned up to avoid file-locking issues on Windows.

---

## Snippet 12 — Docker Compose Orchestration

**File:** `docker-compose.yml`

All 5 services are orchestrated on a shared bridge network with health checks.

```yaml
services:
  # ── ControlPlane (Java gRPC + Dashboard API) ──
  control-plane:
    build:
      context: .
      dockerfile: java-control-plane/Dockerfile
    environment:
      - ROLE=server
      - PREDICTOR_HOST=predictor          # DNS name on Docker network
    ports:
      - "50051:50051"                      # gRPC
      - "9090:9090"                        # Prometheus
    networks: [nextgen-net]
    depends_on: [predictor]
    healthcheck:
      test: ["CMD", "sh", "-c", "echo | timeout 2 bash -c 'cat < /dev/null > /dev/tcp/localhost/50051'"]
      interval: 5s
      retries: 10

  # ── Node Agents (3 instances, real OS metrics) ──
  node1:
    build: { context: ., dockerfile: java-control-plane/Dockerfile }
    environment:
      - ROLE=agent
      - NODE_ID=node1
      - CONTROL_PLANE_HOST=control-plane   # Resolves via Docker DNS
    networks: [nextgen-net]
    depends_on:
      control-plane: { condition: service_started }

  # ── Python Predictor ──
  predictor:
    build: { context: ., dockerfile: python-predictor/Dockerfile }
    ports: ["50052:50052", "9091:9091"]
    networks: [nextgen-net]

networks:
  nextgen-net:
    driver: bridge
```

> **How it works:** Docker Compose creates a `nextgen-net` bridge network where services discover each other by name (e.g., `CONTROL_PLANE_HOST=control-plane`). `depends_on` ensures the ControlPlane starts before NodeAgents. The health check probes TCP port 50051 to verify gRPC readiness. All three node agents use the same Dockerfile but different `NODE_ID` environment variables.

---

## Snippet 13 — Dashboard Frontend Polling

**File:** `dashboard/js/app.js`

The web dashboard polls the REST API every 2 seconds and updates Chart.js visualizations.

```javascript
const API_URL = '/api/nodes';
const POLL_INTERVAL = 2000;
const MAX_DATA_POINTS = 30;    // 60-second rolling window

async function fetchAndUpdate() {
    try {
        const resp = await fetch(API_URL);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const data = await resp.json();

        const nodes = data.nodes || [];
        nodes.forEach(node => {
            // Initialize history for new nodes
            if (!nodeHistory[node.nodeId]) {
                nodeHistory[node.nodeId] = { cpu: [], mem: [], labels: [] };
            }
            // Push real data, trim rolling window
            nodeHistory[node.nodeId].cpu.push(node.cpuUsage);
            if (nodeHistory[node.nodeId].cpu.length > MAX_DATA_POINTS) {
                nodeHistory[node.nodeId].cpu.shift();
            }
        });

        // Update Chart.js and tables
        if (cpuOverviewChart) updateOverviewCharts(nodes);
        if (document.getElementById('overview-nodes-body')) updateOverviewTable(nodes);

    } catch (err) {
        console.error('Fetch error:', err);
    }
}

// Start polling
fetchAndUpdate();
setInterval(fetchAndUpdate, POLL_INTERVAL);
```

> **How it works:** `fetch()` calls the `/api/nodes` endpoint served by `DashboardApiHandler`. Node history is stored in a dictionary keyed by `nodeId`, with `shift()` enforcing a 30-point rolling window (60 seconds at 2s intervals). Chart.js `update('none')` applies data changes without animation for smooth real-time rendering.

---

## Quick Reference — Snippet Index

| Snippet | File | Key Concept |
|---------|------|-------------|
| 1 | `control_plane.proto` | Proto3 service/message definitions, `stream` keyword |
| 2 | `Main.java` | Java 21 switch expressions, ROLE-based entry |
| 3 | `ControlPlaneServer.java` | Multi-service bootstrap (gRPC + HTTP + Prometheus) |
| 4 | `ControlPlaneServiceImpl.java` | gRPC StreamObserver pattern, round-robin scheduling |
| 5 | `NodeAgent.java` | Real OS metrics via `OperatingSystemMXBean` |
| 6 | `HeartbeatMonitor.java` | Daemon thread, dead node detection |
| 7 | `NodeRecord.java` | `volatile` fields for cross-thread visibility |
| 8 | `DashboardApiHandler.java` | Manual JSON building, CORS headers |
| 9 | `predictor_service.py` | Python gRPC server with Prometheus Histogram |
| 10 | `ClusterManagerServiceImpl.java` | V2 bidirectional streaming |
| 11 | `DatabaseManager.java` | Hibernate + SQLite with WAL mode |
| 12 | `docker-compose.yml` | Multi-container orchestration, Docker DNS |
| 13 | `app.js` | Frontend polling with Chart.js rolling window |

---

> **Last Updated:** May 2026 | **Back to:** [DEVELOPMENT.md](../DEVELOPMENT.md) | [README.md](../README.md)
