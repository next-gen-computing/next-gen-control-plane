# Project Interview Guide: Next-Gen Control Plane

This guide describes the repository as it exists today and gives a truthful way to present it in Java backend and Java full-stack interviews. Keep the distinction between **current implementation** and **planned Spring Boot enhancement** clear.

## 1. Project Summary

**Next-Gen Control Plane** is a multi-module Java 21 distributed-compute system. A control-plane server registers worker nodes, monitors their heartbeats and telemetry, schedules work, and collects results. Workers connect outbound to the server over gRPC; enrollment uses one-time tokens and the cluster uses mutual TLS for authenticated communication.

The system also includes a JavaFX desktop application, an `nx` command-line client for distributed Docker Compose jobs, and a Python gRPC predictor for optional risk classification and load forecasting.

### A 30-second introduction

> I built a distributed control-plane project in Java 21. It connects worker nodes to a coordinator over gRPC with mutual TLS, tracks node health and telemetry, and schedules work across healthy nodes. The coordinator supports capacity-aware job splitting, retries, and proactive task migration when a node is predicted to be at risk. The repository also has a JavaFX desktop client, an `nx` CLI for distributed Docker Compose workloads, and an optional Python prediction service. The Java services are currently plain Java/Maven rather than Spring Boot, so I’m extending the project with a Spring Boot API as a separate layer instead of claiming it was already built on Spring.

## 2. Verified Technology Inventory

| Area | Technology used | How it is used |
|---|---|---|
| Language/runtime | Java 21 | Control plane, node agent, desktop app, and CLI; Maven compiles with release 21. |
| Build | Maven multi-module | Root reactor with `java-control-plane`, `desktop-ui`, and `cli` modules. |
| RPC and schema | gRPC Java, Protocol Buffers | Node registration, heartbeat, task channels, predictor RPC, and Raft transport. Proto sources generate Java and gRPC stubs. |
| Desktop UI | JavaFX 21, WebView, HTML, CSS, JavaScript | JavaFX hosts the local web UI; Java code serves local HTTP endpoints and streams updates to the frontend. |
| JSON | Jackson Databind | JSON DTOs, task payloads, API output, and model/service communication. |
| CLI configuration parsing | SnakeYAML 2.2 | Reads the documented subset of Docker Compose YAML in the `nx` CLI. |
| Security/PKI | gRPC TLS, JCA/JCE, Bouncy Castle 1.85 | Enrollment, CSR handling, X.509 certificate issuance, mTLS, certificate revocation and policy checks. Bouncy Castle is used as a library, not registered as a JCE provider. |
| Metrics/monitoring | Prometheus simpleclient 0.16.0, OSHI 6.6.5 | Exposes Prometheus metrics; OSHI supplies cross-platform host information such as power signals. |
| Logging and alerts | SLF4J 2.0.16 with SLF4J Simple; Angus Mail 2.0.5 | Logging and SMTP email alerts. Webhook/desktop alerts are also implemented in project code. |
| Containers | Docker CLI, Docker Compose | Executes Compose services on eligible workers. The Java code invokes the installed Docker CLI; it does not use a Docker Java SDK. |
| Predictor | Python 3.11 target; gRPC, protobuf, Prometheus client, NumPy, XGBoost, PyTorch, pandas | Optional model serving, risk classification, load forecasting, trace import and model training. The requirements file records exact pins. |
| Java tests | JUnit 5, Mockito, Awaitility, gRPC testing/in-process | Unit, asynchronous, and in-process RPC tests. JaCoCo measures coverage. |
| Python tests | pytest | Predictor and data-import/model tests. |
| Deployment/observability | Docker Compose, Prometheus configuration | Local deployment and metrics scraping configuration. |

### Current framework status

- **Spring Boot is not currently in this repository's Maven dependency graph.** There are no Spring Boot starters or Spring application annotations in the source.
- The control-plane server is a Java gRPC server, not a Spring MVC application. Some local HTTP endpoints use the JDK `com.sun.net.httpserver` API.
- There is no React/Angular frontend, Spring Data JPA, Hibernate, or relational database configured. The desktop frontend is HTML/CSS/JavaScript inside JavaFX WebView. The registries are primarily in memory, with file-backed persistence for selected state/log data and Raft state.
- Avoid describing this as a Spring Boot, REST-first, or database-backed application in an interview until those parts have actually been implemented.

## 3. Java Collection Framework Types Used

These are the collection interfaces, implementations, and collection utilities found in the Java source. The examples explain the design choice; do not imply every type is used in every module.

| Type | Category | Project use / interview explanation |
|---|---|---|
| `List` | Interface | Ordered sequences for nodes, tasks, peers, results, DTO fields and service declarations. |
| `ArrayList` | List implementation | Mutable lists while building snapshots, task batches, results and parsed service collections. Good for iteration and append-heavy construction. |
| `Map` | Interface | Key/value lookups for node IDs, job/task data, configuration and JSON mappings. |
| `HashMap` | Map implementation | Ordinary local maps where iteration order is not part of the contract; used for Raft's guarded index/future state and other temporary lookups. |
| `LinkedHashMap` | Map implementation | Preserves insertion order, useful for deterministic JSON/output and history snapshots. |
| `ConcurrentHashMap` | Concurrent map | Shared registries/caches such as nodes, jobs, channels and tokens. `compute`/`computeIfPresent` makes per-key read-modify-write atomic; it does not make arbitrary multi-key operations transactional. |
| `Set` | Interface | Membership and uniqueness constraints, such as certificate/peer identifiers. |
| `HashSet` | Set implementation | Fast membership and duplicate elimination where iteration order is not required. |
| `EnumMap` | Map specialized for enum keys | Compact and clear grouping/indexing by enum values, such as node status. |
| `EnumSet` | Set specialized for enum values | Efficient set of PKI/security enum options. |
| `CopyOnWriteArrayList` | Thread-safe list | Listener/event subscriber collections where reads/iteration are frequent and updates are relatively rare; each write copies the backing array. |
| `Optional<T>` | Optional value container | Represents a possibly absent result without returning `null` at selected API boundaries. It is not a collection. |
| `Properties` | Legacy string key/value container | SMTP configuration properties. It is not a general-purpose `Map` replacement. |
| `Collections` | Utility class | Unmodifiable/empty/sorting-related collection helpers. It is not itself a collection implementation. |
| `Comparator` | Ordering strategy | Explicit ordering for deterministic node/job/task snapshots and selection. |
| `Iterator` | Traversal interface | Explicit traversal/removal in selected processing paths. |
| `Enumeration` | Legacy traversal interface | Used when reading HTTP headers from the JDK HTTP server API; not a Collection Framework collection. |

### Related concurrency utilities (not collections)

The code also uses `ExecutorService`, scheduled executors, `CompletableFuture`, `CountDownLatch`, `AtomicBoolean`, `AtomicInteger`, `AtomicLong`, `AtomicReference`, and `ThreadLocalRandom`. These are concurrency primitives/utilities, not collections. They support background monitoring, ordered Raft replication/apply work, asynchronous requests, and thread-safe counters/state.

### A good collections answer

> For shared state, I use `ConcurrentHashMap` for keyed registries. In the node registry, mutations go through `compute` or `computeIfPresent`, which makes an update for one node atomic and prevents a stale heartbeat sweep from overwriting a newer heartbeat. For listener lists, `CopyOnWriteArrayList` is suitable because iteration is frequent and subscription changes are uncommon. For deterministic output, I use `LinkedHashMap` or sorted list snapshots. I choose based on thread-safety and ordering requirements rather than using one collection everywhere.

## 4. Architecture and Features to Explain

1. **Control plane and workers:** the server owns node/job/task registries and scheduling; agents dial outbound to it, including across NAT. Nodes do not need an inbound listener.
2. **Enrollment and mTLS:** a single-use enrollment token authorizes certificate issuance. The worker creates its own key pair and sends a CSR; subsequent RPCs use a CA-signed client certificate.
3. **Liveness and recovery:** heartbeat monitoring detects stale nodes; workers reconnect with capped exponential backoff and jitter. Explain what happens to registered/running work rather than just saying “it is fault tolerant.”
4. **Task/job scheduling:** round-robin is the baseline placement strategy. Capacity-aware scoring can weight job splits using worker capacity/headroom. Failed work can be retried; proactive migration handles a predicted-risk transition. Be clear that job splitting/reduction is currently task-kind-specific where applicable.
5. **Raft:** an optional, statically configured multi-replica mode provides leader election and log replication. Discuss quorum, committed entries and deterministic apply; do not claim dynamic membership if it is not implemented.
6. **Docker Compose execution:** the CLI parses a supported Compose subset and submits services as distributed work. Worker nodes run Docker through their local CLI when Docker capability is available. Explain the documented cross-node networking limitations honestly.
7. **Prediction:** the Python gRPC service hosts optional XGBoost risk classification and an LSTM load-forecasting path. Distinguish trained/available models from opt-in or not-yet-trained capabilities.
8. **Frontend:** JavaFX/WebView loads the desktop UI; the Java side exposes local HTTP/JSON/SSE endpoints. The gRPC client stays in the JVM, not in browser JavaScript.

### A 90-second backend explanation

> The core problem is coordinating work across unreliable machines without requiring workers to accept inbound connections. Each worker initiates a gRPC connection to the control plane. At enrollment, a single-use token is consumed and the worker receives a CA-signed certificate; ongoing RPCs use mTLS. The server records workers and heartbeats in a concurrent registry. Registry updates use per-key `ConcurrentHashMap.compute` operations so registration, heartbeat, and liveness sweeps do not race on the same node. Jobs are split into tasks and scheduled across eligible workers; baseline round-robin is complemented by capacity-aware scoring. If a worker fails or becomes risky, the system supports retry or migration paths. Optional Raft replication improves control-plane availability, and a Python predictor adds model-based signals without making the core dependent on ML availability. I would next expose operator-facing REST endpoints and configuration/health through Spring Boot while keeping the existing gRPC protocol for agents.

### A 90-second Java full-stack explanation

> The product is a distributed control plane with both a desktop operator experience and backend services. The JavaFX app hosts a WebView frontend built with HTML, CSS and JavaScript. A local Java HTTP layer exposes JSON endpoints and server-sent events to that frontend, while the JVM talks gRPC to the control plane. The backend manages node identity, heartbeats, scheduling, task results and security; the Python predictor is a separate gRPC service. This is not currently a Spring MVC plus React application. To strengthen it for a conventional Java full-stack role, I would add a Spring Boot API and a React or Angular client as a separate module, define versioned REST contracts, validate requests, secure operator APIs, and add integration tests without replacing the worker gRPC protocol.

## 5. Spring Boot Upgrade/Enhancement Path

**Do not target “Spring Boot 6” today.** As checked against the official Spring project page on 2026-10-10, the current listed release is Spring Boot **4.1.1**. Its system requirements state Java 17 minimum and compatibility through Java 26; this repository's Java 21 baseline satisfies the minimum. Confirm the newest patch release and its dependencies when starting the implementation.

For career value, add Spring Boot in an incremental, real feature rather than mechanically converting the entire application:

1. Create a `spring-api` Maven module using the current supported Spring Boot 4.1.x line and Java 21. Keep the current gRPC control plane and agent protocol intact.
2. Add REST endpoints for operator workflows, for example `GET /api/v1/nodes`, `GET /api/v1/jobs/{id}`, and `POST /api/v1/jobs`. Define request/response DTOs and validation; map domain errors to stable HTTP status codes.
3. Put controller, application-service, and adapter boundaries around the API. Reuse existing core behavior through a clean module dependency or an interface; avoid duplicating scheduling logic or starting a second conflicting server/registry.
4. Add Spring Boot Actuator health/metrics endpoints and Spring Security for operator authentication/authorization. Preserve the existing mTLS worker identity model rather than replacing it with browser login credentials.
5. Add persistence only if it solves a real need: PostgreSQL + Spring Data JPA + Flyway could persist operators/audit/job metadata. Define consistency and ownership carefully because the current project also has in-memory registries and optional Raft log replication. Do not blindly store/replicate the same state twice.
6. Add focused tests with `@WebMvcTest` or `@SpringBootTest`, Testcontainers for PostgreSQL if persistence is added, and API contract tests. Update Docker Compose and docs only after the new module runs end to end.
7. Verify the whole Maven reactor and demonstrate one API workflow calling existing job logic and returning the actual distributed job state/results.

For a conventional frontend role, a separate React/TypeScript UI can consume these REST endpoints. Keep it separate from the JavaFX client unless replacing the desktop product is an explicit goal.

## 6. Interview Topics to Prepare

Be ready to explain these with one code-level example and one trade-off each:

- Java 21, records/immutability, interfaces, exception handling, streams and `Optional` where actually used.
- Collections: why `ConcurrentHashMap`, `compute`, `CopyOnWriteArrayList`, ordering and collection complexity.
- Concurrency: executors, scheduled tasks, atomics, synchronization boundaries, shutdown and race prevention.
- gRPC vs REST: efficient typed internal worker protocol vs operator/browser-friendly HTTP APIs.
- mTLS: enrollment, private-key custody, certificate lifecycle, identity validation and revocation.
- Scheduling: eligibility, fairness, capacity scoring, retry/migration behavior and limitations.
- Raft: leader election, quorum, log replication, commit safety and current membership limits.
- Observability: Prometheus metrics, heartbeat/RTT, health checks and meaningful failure signals.
- Testing: unit vs integration tests, in-process gRPC tests, asynchronous tests, Docker-dependent coverage and what was actually run.
- Design decisions: outbound-only workers, file-backed/in-memory state, Python model isolation, Compose subset and known networking limitations.

When discussing a result, state what you personally built, the exact test/demo you ran, and its measured outcome. Do not claim production scale, full Compose compatibility, a relational database, or Spring Boot until demonstrated.

## 7. Source of Truth and References

- Root Maven versions and dependency management: [`pom.xml`](pom.xml)
- Core gRPC/security/scheduling module: [`java-control-plane/pom.xml`](java-control-plane/pom.xml)
- Desktop module: [`desktop-ui/pom.xml`](desktop-ui/pom.xml)
- CLI dependencies: [`cli/pom.xml`](cli/pom.xml)
- Python dependencies: [`python-predictor/requirements.txt`](python-predictor/requirements.txt)
- Architecture details: [`ARCHITECTURE.md`](ARCHITECTURE.md)
- Project overview and behavior: [`README.md`](README.md)
- Official Spring Boot release page: https://spring.io/projects/spring-boot
- Official Spring Boot system requirements: https://docs.spring.io/spring-boot/system-requirements.html
