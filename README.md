<div align="center">

# ⚡ Next-Gen Control Plane

**Production-grade distributed control plane with real-time predictive scheduling under failure conditions**

[![Build](https://img.shields.io/badge/build-1.0--SNAPSHOT-blue.svg)](pom.xml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![Python](https://img.shields.io/badge/Python-3.11-blue.svg)](https://python.org)
[![gRPC](https://img.shields.io/badge/gRPC-1.83-green.svg)](https://grpc.io)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://docs.docker.com/compose/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-purple.svg)](https://openjfx.io)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<p align="center">
  <b>Desktop app is the product</b> • Real physical nodes, anywhere • mTLS over the open internet • Dark/Light Themes • Multi-Module Maven
</p>

[Quick Start](#quick-start) • [Features](#features) • [Architecture](#architecture) • [Desktop App](#-desktop-application) • [Hotspot Cluster](#-physical-cluster-on-a-shared-wi-fi--mobile-hotspot) • [Run a Project](#-executing-a-project-on-the-cluster-the-nx-cli) • [Documentation](#documentation)

</div>

---

## 🎯 What this actually is

A real distributed-compute cluster. **The nodes are physical machines — yours, a friend's, a rented
box anywhere on the internet — not simulated containers.** Each one runs the same desktop app you run
on the coordinating server; the only difference is which role you pick when it starts. There is no
separate "node" product and no requirement that a node be on the same network as the server.

- **One app, two roles.** `desktop-ui` is the entire product surface. Launch it, choose Server or
  Node. A node dials out to the server over the open internet — it needs no inbound port, no port
  forwarding, no static IP, and no special network configuration of its own.
- **Cross-platform by construction.** JavaFX runs natively on Windows, macOS and Linux from the same
  codebase; there is no OS-specific code path in the application logic. (Packaging real installers
  per OS is tracked as deployment work — see the Deployment section.)
- **Real OS Metrics** — actual CPU & memory from `com.sun.management.OperatingSystemMXBean`, never a
  placeholder, on every node regardless of platform.
- **mTLS from the start.** A node proves itself once with a single-use enrolment token and receives a
  CA-signed client certificate; every RPC after that is mutually authenticated. This is what makes
  "reachable from the open internet" safe rather than reckless.
- **Fault Tolerance** — automatic node failure detection & recovery (6s timeout), reflected honestly
  in the UI, not silently retried into a stale-looking success.
- **Round-Robin Load Balancing** — fair task distribution across healthy nodes, rotating over node
  identity so churn in the cluster can't starve a node or double-load another.
- **Prometheus Metrics** on the control plane and predictor. (Per-node metrics reach the UI over gRPC
  through the control plane, not via a Prometheus scrape — see ARCHITECTURE.md for why a
  pull-based scraper structurally can't reach a NAT'd worldwide node directly.)
- **Docker for the server only** — one command self-hosts the coordinating server; nodes are never
  Docker containers.

### Technical Highlights

- **Real Data Only, Explicitly Marked** — Every metric comes from an actual OS reading. When the
  platform cannot supply a reading, the value is reported as **unavailable** all the way from the
  agent to the UI (`cpu_available`/`memory_available` on the wire, `cpuStale`/`memoryStale` in the
  registry and dashboard JSON). No substituted default is ever presented as a measurement.
- **Thread-Safe by Construction** — `NodeRecord` is immutable and all registry mutation is funnelled
  through `ConcurrentHashMap.compute`, so read-decide-write is atomic per node.
- **Dual Entry Points** — Desktop GUI (JavaFX) or CLI (ROLE env var)
- **Configuration over Hardcoding** — every port, host, interval and timeout resolves through
  `EnvConfig`; see [Configuration](#-configuration).
- **Production Ready** — Health checks, graceful shutdown, structured logging
- **Java 21** — switch expressions, `--release 21` compilation

---

## 🏗️ Architecture

The same desktop app is both ends. A "node" and the "server" are two roles of one codebase — what
differs is which one you pick on launch, not which machine you're allowed to run it on.

```
   Windows laptop         macOS desktop          Linux box            ...anywhere else
   (Node mode)            (Node mode)            (Node mode)          (Node mode)
        │                      │                      │                      │
        │ gRPC + mTLS, always OUTBOUND — no inbound port needed on any node   │
        └──────────────────────┴──────────┬───────────┴──────────────────────┘
                                           ▼
                    ┌──────────────────────────────────────────┐
                    │   CONTROL PLANE  (desktop app, Server mode│
                    │   — or headless via ROLE=server)          │
                    │                                            │
                    │  NodeRegistry        (immutable, atomic    │
                    │                        per-node compute)   │
                    │  RoundRobinScheduler  (rotates over node    │
                    │                        identity)            │
                    │  HeartbeatMonitor     (6s → SUSPECTED_DEAD) │
                    │  CertificateAuthority (mTLS enrolment)      │
                    │  Prometheus metrics    (:9090)              │
                    └──────────────────┬───────────────────────┘
                                       │ gRPC
                                       ▼
                          ┌───────────────────────────┐
                          │  Predictor (Python 3.11)   │
                          │  Prometheus (:9091)         │
                          └───────────────────────────┘
```

This is the only publicly-reachable point in the whole system: the control plane. Everything else —
every node, on every OS, anywhere — only ever dials out to it. See `ARCHITECTURE.md` for the
full connectivity and mTLS trust model.

> The old web dashboard and the Docker-simulated `node1`/`node2`/`node3` containers from earlier
> revisions of this project are gone entirely — not just retired from the default deployment. Nodes
> were never meant to be containers pretending to be far-apart machines; they're supposed to actually
> be far apart. The control plane still serves a small `/api/nodes` JSON endpoint
> (`DASHBOARD_PORT`, see [Configuration](#-configuration)) for anyone who wants to build their own
> frontend against it, but no bundled static frontend ships anymore.

## 🧠 Algorithms & Predictive Intelligence

Every algorithm below is real, tested code — not a description of an aspiration. Each entry says what
problem it solves, exactly where it lives, and whether it's on **by default** or something an operator
has to **opt into**. See **[ALGORITHMS.md](ALGORITHMS.md)** for the full deep-dive version of this same
material — exact formulas/pseudocode, the alternative each design rejected and why, and the real
measured numbers (model accuracy, live-migration latency) behind each one.

### Round-robin scheduling — always on
`RoundRobinScheduler` (`java-control-plane/.../controlplane/RoundRobinScheduler.java`). Baseline fair
task placement: rotates over node **identity** in a sorted snapshot rather than list position or an
incrementing counter, so churn in the cluster (a node joining or dying) can't starve one node or
double-load another. Used for a task's first placement when no other scorer applies, and for
`ProactiveMigrator`'s replacement-node selection.

### Heuristic capacity-aware job splitting — always on for jobs
`HeuristicNodeCapacityScorer` (`.../controlplane/capacity/`). When a job is split into sub-tasks, each
selected node's share of the range is weighted by `cpu_cores`, `memory_gb`, live CPU/memory headroom,
and current risk score — not divided equally. A loaded node automatically gets a smaller slice of the
*next* job, because the score reads *live* headroom at split time, no extra fairness logic needed.
`JobOutcomeLogger` records real (node properties, allocated share) → (duration, outcome) examples for a
future learned version of this scorer; none exists yet — this is the seam, not the model.

### Rule-based risk scoring — always on (the default risk scorer)
`RuleBasedRiskScorer` (`.../controlplane/risk/`). Combines three interpretable signals into a capped
weighted score: low/draining battery, a strictly-increasing recent RTT trend, and memory pressure
that's both rising and already near a ceiling. This is the always-available fallback every other
risk-scoring path degrades to — see XGBoost below.

### XGBoost failure-risk classification — opt-in
Real gradient-boosted trees (the raw `xgboost.Booster` API), trained by
`python-predictor/train_risk_model.py --model-type xgboost` (the default model type) on real outcome
data — `risk_outcomes.jsonl`/`risk_snapshots.jsonl`, written as nodes actually die or get swept. Served
by `predictor_service.py`, consumed Java-side by `MLRiskScorer implements RiskScorer` — a thin gRPC
client that **always falls back to `RuleBasedRiskScorer` whenever the model isn't actually trained**,
never fabricating a score. Opt-in: set `ML_RISK_SCORER_ENABLED=true` **and** actually run training at
least once — every response honestly reports `model_trained=false` until then.

**Auto-retrain (`python-predictor/auto_retrain.py`) — opt-in, off by default.** Training is normally an
operator-run step (`train_risk_model.py`) precisely because an unattended retrain on live data could
silently change what the risk model believes, with nobody reviewing it. Setting
`AUTO_RETRAIN_ENABLED=true` starts a background thread inside `predictor_service.py` that periodically
(`AUTO_RETRAIN_CHECK_INTERVAL_SECONDS`, default hourly) checks whether enough new real examples have
accumulated (`AUTO_RETRAIN_MIN_NEW_EXAMPLES`, default 20) in `risk_outcomes.jsonl`/`risk_snapshots.jsonl`,
trains a **candidate** model, and only replaces the live one if the candidate's validation accuracy
doesn't regress beyond `AUTO_RETRAIN_MAX_REGRESSION` (default 2 points) below the current live model's —
otherwise the candidate is saved to `model/risk_model_candidate_rejected.json` for review and the live
model is left untouched. The live model can get better as real data accumulates; it cannot silently get
worse. See `python-predictor/tests/test_auto_retrain.py` for the promotion/rejection guarantees this is
actually tested against.

### LSTM load forecasting — opt-in
`torch.nn.LSTM` (`python-predictor/load_forecast_model.py`), forecasting CPU/memory 5 minutes ahead
from the raw per-timestep telemetry sequence — a genuinely different input from the XGBoost classifier
above, which only ever sees collapsed trend scalars. Requires `RISK_SNAPSHOT_LOGGING_ENABLED=true` to
collect training data and a manual run of `train_load_forecast_model.py`. `MLRiskScorer` folds a
crossed-threshold forecast into its score as one bounded, additive signal — it composes with the
XGBoost score, never replaces or dominates it.

### Raft consensus + leader election — opt-in
A hand-rolled implementation (`com.nextgen.controlplane.raft`) turning the control plane from one
process into a fault-tolerant 3-replica cluster: randomized-timeout leader election, log replication
with the mandatory Figure-8 commit-safety check, a durable write-ahead log, and gRPC-based
leader-redirect for both node RPCs and certificate issuance. Off by default (`RAFT_ENABLED=false`) —
see [ARCHITECTURE.md's **Consensus & replication**](ARCHITECTURE.md#consensus--replication)
for what's replicated vs. leader-local and why, and `docker-compose.raft.yml` to actually run a
3-replica cluster.

### Proactive migration — always on
`RiskMonitor` (`.../controlplane/`) and `ProactiveMigrator` (`.../controlplane/task/`). On the
false→true edge of a node's `atRisk`
flag (never on every subsequent sweep while it stays risky), in-flight tasks are moved off it onto a
healthy replacement **before** it actually dies — the predictive counterpart to `HeartbeatMonitor`'s
reactive dead-node detection. `TaskRegistry`'s fencing (a report is only applied if the reporting
node's id still matches the task's current assignment) is what makes this safe: a late result from the
old node after migration is dropped, never accepted as authoritative.

### Distributed Docker-Compose execution — real, opt-in per task kind
The cluster can run a whole multi-service project — not just single computational tasks — spread
across whichever nodes are currently idle, submitted the same way you'd type `docker compose up`
yourself. New `TaskKind.DOCKER_COMPOSE_SERVICE` (alongside the original `PRIME_COUNT_RANGE`), a real
node-side execution engine, and the `nx` CLI tool that drives it:

- **Node-side execution** (`com.nextgen.agent.task.DockerComposeServiceExecutor`/`DockerComposeRunner`)
  — shells out to the real `docker` CLI (no SDK dependency, matching this project's existing "use the
  tool the operator already has" precedent) to run one resolved service per task, streaming real
  stdout/stderr back over the existing `TaskChannel`. `DockerCapabilityDetector` gates this honestly: a
  node only reports `docker_available=true` once it's confirmed BOTH the CLI is installed AND the
  daemon is actually reachable (`docker info`) — not just that the binary exists.
- **Build-from-source, not just pre-built images** — a service can `build:` from a local Dockerfile;
  the CLI tars and uploads the context (`UploadBuildContext`), the control plane stages it, and streams
  it down to whichever node ends up building it, SHA-256-verified end to end before the build ever
  starts. See [Distributed container execution](ARCHITECTURE.md#distributed-container-execution)
  for the full operator → control plane → node path.
- **Cross-node service networking, honestly scoped** — services on different nodes reach each other via
  injected `<PEER>_HOST`/`<PEER>_PORT` environment variables relayed through the control plane
  (`PortRelayManager`/`PortTunnelClient`), preserving the hub-and-spoke rule (nodes never accept direct
  inbound connections, from the control plane or each other). This is **not** transparent same-daemon
  DNS resolution the way vanilla Docker Compose provides — a hard-coded `http://database:5432` will not
  resolve unmodified. See the architecture doc for what this does and does not provide, including the
  `RELAY_ADVERTISED_HOST` operator setting a container actually dials (real machines on a LAN just use
  the control plane's normal address here — the one gotcha is prototyping multiple "nodes" on a single
  Docker Desktop host, where it must be `host.docker.internal`, not `localhost`, for a container to
  reach the host's own relay listener — see below).
- **Scheduling: node-level exclusivity, not a cluster-wide lock** (`JobCoordinator`) — a node already
  running one compose service is excluded from a *second, concurrently-submitted* project, but any
  *other* idle Docker-capable node remains eligible — two projects submitted back-to-back naturally land
  on disjoint node subsets, converting the whole cluster into a real distributed build/run farm rather
  than serializing everything through one node at a time.
- **Resource limits** — a service's `deploy.resources.limits.cpus`/`.memory` and
  `.reservations.memory` (Compose's own schema, parsed by `ComposeFileParser`) become real
  `--cpus`/`--memory`/`--memory-reservation` flags on the node's `docker run` — verified with a
  real container and `docker inspect`, not just that the right JSON was built. CPU *reservation* has
  no honest single-host `docker run` equivalent (it's a Swarm-only concept), so it's deliberately not
  parsed rather than silently dropped somewhere less visible.
- **Restart policies** — `restart: "no"|"always"|"on-failure"|"unless-stopped"` (plus the
  `"on-failure:N"` shorthand and `deploy.restart_policy.max_attempts`) drive a real Java-side retry
  loop in `DockerComposeServiceExecutor`, not Docker's native `--restart` (which would fight `--rm`
  and this project's `waitFor()`-based lifecycle model). Every policy is bounded by `maxAttempts`
  (default 5) — including `always`/`unless-stopped`, which restart forever for a real Docker daemon
  container but would be a genuine resource leak for this project's one-shot task execution model.
- **Health checks** — a `healthcheck:` block becomes real native `--health-cmd`/`--health-interval`/
  `--health-timeout`/`--health-retries`/`--health-start-period` flags — Docker's own health-check
  engine runs the check, not a hand-rolled one. A lightweight side-poller reads the result back via
  `docker inspect` and, on a transition to `unhealthy`, kills the container so the restart loop above
  reattempts it exactly as it would a real crash — verified end to end with a real always-failing
  health check on an otherwise-never-exiting container. The same result is also regex-extracted from
  `docker ps`'s own `Status` string (free, no extra `docker inspect` call) for the dashboard's
  `DockerContainerInfo.health_status` field.
- **Secrets** — encrypted at rest (AES-256-GCM, a server-local key with the exact same owner-only file
  permission discipline this project's PKI key material already uses), set via `nx secret set <name>
  <value-or-@file>`, decrypted and shipped to a node only at dispatch time over its own already-open
  `TaskChannel` (mirroring the Stage N build-context delivery pattern), and mounted as a real file at
  `/run/secrets/<name>` — **never** a container environment variable, since `docker inspect`/`ps`
  reveal env vars in plaintext but not a bind-mounted file's contents. Verified with a real container
  reading its own mounted secret, and a real `docker inspect` confirming it never appears in
  `Config.Env`.
- **Load-balanced replicas** — `replicas: N` (or `deploy.replicas`) runs N real copies of a service,
  each on a distinct node (the existing one-Docker-task-per-node exclusivity filter already guarantees
  this, unchanged). `PortRelayManager` — originally built for single-backend relaying — now supports
  multiple backends per relay port: every replica independently opens its own `TunnelPort` stream and
  registers as an additional backend, and each new consumer connection is round-robined across
  whichever backends are currently attached, chosen once at accept time and fixed for that
  connection's lifetime. A backend disconnecting removes just that one backend (and only its own
  in-flight tunnels) — the listener and every other replica keep serving; the underlying relay port
  itself is only released once the last backend detaches. Verified with real sockets: two attached
  backends, several real connections, confirming both actually receive traffic, and a mid-stream
  detach that doesn't drop the survivor.
- **Confirmed cancellation** — `nx down`/`CancelTask` pushes the same `TaskCancel` command
  `ProactiveMigrator` already used internally, but now **waits for real confirmation** the task
  actually stopped (polling `TaskRegistry` for a terminal state) before returning success, rather than
  firing-and-forgetting. This needed no new node-side machinery — `DockerComposeServiceExecutor`'s
  existing `stoppedByRequest` handling already reports a real terminal result once a container
  genuinely stops; `TaskDispatcher.cancelAndAwaitConfirmation` is the shared, tested primitive both
  the CLI-facing RPC and the rolling update below build on.
- **Rolling updates + rollback** — `nx update <job-id> <compose-file>` replaces a running project's
  replicas with a new spec **one at a time**: confirmed-cancel the old replica, dispatch the new one,
  wait for it to actually be ready (real container health when a `healthcheck:` is declared, `RUNNING`
  state otherwise) before touching the next — verified with a real invariant check that no later
  replica is ever cancelled while an earlier one is still mid-swap. `nx rollback <job-id>`
  reconstructs the previous spec directly from the superseded job's own stored task payloads (no
  separate copy kept) and re-applies it through the identical one-at-a-time path. Bounded parallelism
  of 1 (never more than one replica down at once) is an explicit, named scope cut versus a
  configurable `maxSurge`/`maxUnavailable`, and this whole mechanism is scoped to the direct
  (non-Raft) path in this pass — attempting it under Raft replication fails with a clear, honest
  error rather than silently behaving differently.
- **Cloud/single-machine mode** (`LocalDockerExecutionServiceImpl`, opt-in via
  `LOCAL_DOCKER_EXEC_ENABLED=true`) — for a single machine with no cluster at all: `nx cloud up` talks
  directly to this separate RPC (never gated by Raft leader-redirect — it's tied to *this host's* Docker
  daemon, not "whichever replica is leader"), reusing the exact same `DockerComposeRunner` the
  distributed path uses. Zero `RegisterNode`/`TaskChannel`/registry traffic.
- **The `nx` CLI** (`cli/`, module `nextgen-cli`) — `nx enrol`, `nx up`/`down`/`ps`/`logs`, `nx update`/
  `rollback`, `nx secret set`, `nx nodes`, `nx cloud up`, modeled directly on `docker compose`'s own
  command set. Talks plaintext by default,
  matching this project's own `TLS_ENABLED=false` default deployment (`docker-compose.yml`) — pass
  `--tls` (after a prior `nx enrol`) to opt into mutual TLS instead. Parses a documented subset of
  `docker-compose.yml` (`image`, `build.context`/`dockerfile`, `command`, `environment`, `ports`,
  `depends_on`) locally — the control plane never parses YAML itself.

**Live-verified**: the whole pipeline above — node registration, capability-aware scheduling with
node-level exclusivity, real containers on separate nodes, cross-node relay networking, live log
streaming, and proactive migration firing on genuine resource pressure — was run end to end on real
processes, not just under test. To reproduce on one machine (each in its own terminal):

```bash
# 1. The control plane
ROLE=server TLS_ENABLED=false java -jar java-control-plane/target/control-plane-*-all.jar

# 2. Two or more nodes, distinct NODE_ID/NODE_METRICS_PORT per node
ROLE=agent TLS_ENABLED=false NODE_ID=node-alpha NODE_METRICS_PORT=9191 \
  CONTROL_PLANE_HOST=localhost CONTROL_PLANE_PORT=50051 \
  java -jar java-control-plane/target/control-plane-*-all.jar

ROLE=agent TLS_ENABLED=false NODE_ID=node-beta NODE_METRICS_PORT=9192 \
  CONTROL_PLANE_HOST=localhost CONTROL_PLANE_PORT=50051 \
  java -jar java-control-plane/target/control-plane-*-all.jar

# 3. Submit a real multi-service job and follow it live
java -jar cli/target/nextgen-cli-*.jar up my-compose.yml --project demo --control-plane localhost:50051
```

Prototyping several "nodes" on one Docker Desktop host has exactly one topology-specific gotcha: set
`RELAY_ADVERTISED_HOST=host.docker.internal` on the control plane (not the default `localhost`) so a
container can reach the relay listener running on its own host machine — see
[Distributed container execution](ARCHITECTURE.md#distributed-container-execution). Genuinely
separate physical nodes need no such override.

## 📄 Paper ↔ Implementation

An earlier draft IEEE conference paper describing this project claimed several capabilities that did
not exist in the code at the time it was written. Every *mechanism* below is now real, tested, and
cross-referenced above; the paper's specific *benchmark numbers* are a separate question, addressed
honestly underneath the table.

| Paper claim | Real? | Where |
|---|---|---|
| Raft consensus with leader election | ✅ Now real | `com.nextgen.controlplane.raft`, [Consensus & replication](ARCHITECTURE.md#consensus--replication) |
| LSTM load forecasting (5-minute horizon) | ✅ Now real | `python-predictor/load_forecast_model.py` (opt-in) |
| XGBoost failure-risk classification | ✅ Now real | `python-predictor/train_risk_model.py --model-type xgboost` (opt-in) |
| Proactive / predictive workload migration | ✅ Real | `RiskMonitor` / `ProactiveMigrator` |
| Capability-aware job splitting | ✅ Real | `HeuristicNodeCapacityScorer` (heuristic, not yet learned — see above) |
| "Follower Crash and Workload Migration" recovery | ✅ Reproducible | `ReplicatedControlPlaneIntegrationTest` — run it: `mvn test -Dtest=ReplicatedControlPlaneIntegrationTest -pl java-control-plane` |
| Section VI benchmark numbers (ROC-AUC 0.91, specific MTTR/RMSE tables, "30 trials," "25 partition experiments") | ❌ Not reproducible from this repo | No chaos-engineering harness or dataset producing those exact figures exists here — see below |

**On the benchmark numbers specifically:** the paper's Results section reads as templated/placeholder
content — it doesn't correspond to any measurement harness in this repository, and the draft still
contained unedited IEEE template instructions at the end, confirming it predates the real
implementation. Rather than either repeating those numbers as fact or deleting the comparison
entirely, here's how to generate **real** ones yourself:

- **Test suite health** — the actual, current pass counts and coverage percentages (see
  [Testing](#-testing) below) are real and reproducible right now: `mvn clean install` / `mvn test`.
- **Classifier accuracy** — once real risk data has accumulated, `train_risk_model.py`'s own
  `trainAccuracy`/`validationAccuracy` output (printed to stdout, also written into `risk_model.json`'s
  metadata) is a real number from your own cluster's data, not a claimed one.
- **Raft timing** — `RaftSafetyInvariantTest` and the rest of the Phase A suite report real, measured
  election/replication timings on whatever machine runs them; there is no synthetic "30 trials" figure
  standing in for them.

## 🎯 Comparison scope — what this claims against Kubernetes/BOINC, and what it doesn't

This project's actual claim is a specific *mechanism*, not a platform: trend-based risk scoring with
fencing that proactively migrates work off a node **before** it dies, measured this way in a real,
live benchmark on this codebase —

| | Kubernetes | BOINC | This project |
|---|---|---|---|
| Failure model | Reactive — `NotReady` after `node-monitor-grace-period` (~40s) + pod eviction `tolerationSeconds` (default 300s) ≈ **~340s minimum** before rescheduling begins, and only once the node has actually gone silent | Reactive — default work-unit `delay_bound` **~10 days** before reassignment | **Proactive** — real live-measured detection-to-mitigation in **~14ms** once a monitored resource crosses its configured ceiling, while the node is still fully alive and heartbeating throughout |
| What triggers action | The node going silent | The node going silent | A predicted trend (rising memory pressure, degrading RTT, etc.) — the node never has to actually fail for mitigation to happen |

Neither Kubernetes nor BOINC has an accuracy number to compare a classifier against here — they have no
predictive component at all, so this isn't "our model is more accurate than theirs." It's "we act during
a degradation window they are structurally blind to, because they only observe failure after the fact."

**That claim does not require, and this project does not attempt, feature-for-feature parity with
Kubernetes** — a decade-plus, thousands-of-contributor project — **or with BOINC**, a two-decades-old
volunteer-computing work-unit distributor solving a categorically different problem (it has no
containers, no services, no secrets, no health checks; feature-parity questions mostly don't even apply
to it). Overclaiming ("this replaces Kubernetes") is a real risk a reviewer will correctly penalize;
honest scoping to "a predictive scheduling layer for distributed containerized workloads, evaluated
against reactive baselines" is not.

What *is* real here, and matters for that claim to be taken seriously rather than dismissed as a toy:
baseline container-orchestration hygiene, so the predictive-scheduling differentiation isn't undermined
by an obviously-missing basic. All of the following are real, tested, described in detail above —
resource limits (`--cpus`/`--memory`), restart policies, health checks (Docker's own engine, not a
hand-rolled one), encrypted-at-rest secrets delivered as file mounts, load-balanced multi-replica
services, confirmed task cancellation, and one-replica-at-a-time rolling updates with rollback.

**What stays explicitly out of scope, named rather than silently absent, because each is a separate,
large undertaking that doesn't bear on the predictive-scheduling claim:**

- **Full Kubernetes API compatibility, CRDs/operators/admission webhooks** — this project's claim is a
  scheduling mechanism, not a reimplementation of the Kubernetes API surface.
- **RBAC / multi-tenancy / namespaces** — a single-trusted-operator model, reusing the existing mTLS
  certificate boundary as the only access boundary. A deliberate, user-confirmed scope decision, not an
  oversight.
- **Ingress / L7 HTTP routing + TLS termination** — the cross-node relay (`PortRelayManager`) is raw
  TCP (L4) by design; an HTTP-aware reverse proxy with virtual-host routing is a distinct, larger
  feature not attempted here.
- **Cloud-provider integration, and autoscaling of the physical node pool itself** — this project
  targets operator-owned physical machines (laptops, spare boxes, a friend's PC on another continent),
  not cloud VMs it provisions on your behalf.
- **Helm-style templating/packaging** — `ComposeFileParser` parses a literal, already-resolved compose
  file; a templating layer on top is a separable, smaller follow-on if ever wanted.
- **Configurable rollout strategy** (`maxSurge`/`maxUnavailable`, canary, blue/green) — rolling updates
  are always exactly one replica down at a time; a smarter strategy is future work, not silently
  approximated as something it isn't.

## Quick Start

### 1. Start the server

Docker is the convenient path for the **server side only** — it never simulates nodes:

```bash
docker compose up --build
```

| Service | Role | Ports |
|---------|------|-------|
| `control-plane` | gRPC server + scheduler + enrolment | 50051, 8085, 9090 |
| `predictor` | Python prediction stub | 50052, 9091 |
| `prometheus` | Scrapes control-plane + predictor | 9464 (host) |

Or run it without Docker at all — see [Local CLI](#local-cli) below.

### 2. Join real nodes

Install the desktop app (see [Desktop Application](#-desktop-application)) on however many machines
you want contributing compute — your own laptop, a friend's PC on another continent, a spare box at
home. Launch it, choose **Node**, and point it at the server's address. That's the entire onboarding
step; see [Running across the internet](#-running-across-the-internet) for the mTLS enrolment token
flow when the server isn't on your LAN.

### 🖥️ Desktop Application

The desktop app in the `desktop-ui` Maven module **is the product** — the one interface for both
server and node roles, on Windows, macOS and Linux from the same codebase. It connects to the
ControlPlane and Predictor services via gRPC and displays real-time cluster data with a dark/light
theme.

#### Build & Launch

> **One-time setup:** `desktop-ui` needs to *run* on JDK 21 specifically, regardless of
> whatever JDK you otherwise use (`javafx-web` needs the JDK 21-only `jdk.jsobject` module —
> see `DEVELOPMENT.md` for the full explanation). This is handled automatically via Maven
> Toolchains, with **no change to your system's default `java`/`JAVA_HOME`** — you just need
> to tell Maven where a JDK 21 install lives, once: copy `desktop-ui/toolchains.xml.sample` to
> `~/.m2/toolchains.xml` and edit the path. After that, the commands below just work.

```bash
cd desktop-ui
mvn clean compile
mvn javafx:run
```

That one command is the entire launch — there is no separate `docker compose up` to run first. Choose
**Server** on the first screen, and Server Setup asks how to run the control plane, right there:

- **Native** (default) — an embedded, in-process control plane. No Docker involved at all.
- **Docker** — runs this project's own `docker-compose.yml` for you (`docker compose up -d --build`),
  then connects to it exactly the same way. Only offered when a real Docker daemon is actually
  reachable — checked live, never assumed; the button is disabled with an honest reason otherwise.

Either way you end up talking to the same control plane on `localhost:50051` — everything downstream
(dashboard, node management, tasks, jobs) works identically regardless of which mode started it.

**Remembers your setup.** The Server/Node choice above only happens once per device. On success it's
saved to a small local file (`~/.nextgen/desktop/profile.json`) — the *next* launch skips
role-selection entirely and reconnects automatically (a "Welcome back" screen shows the attempt live).
There's no cloud account and nothing is sent anywhere; it's purely local device state, the same idea as
Docker Desktop remembering your last configuration. A node's saved profile reconnects using the mTLS
certificate it already enrolled with (`~/.nextgen/agent`), not a re-typed token. To set this device up
differently — a different role, a different server — open **Settings → Saved setup → Forget this
device**, or click **Set up manually** on the reconnect screen if a saved server is temporarily
unreachable.

**Task/job history.** Every task and job submitted from this device is also kept locally
(`~/.nextgen/desktop/history.json`) and shown on the **History** screen (server role), labelled with
which cluster it ran against — so restarting the app, or reconnecting to a different server later,
never loses the record of what you already ran.

#### Fat JAR

```bash
cd desktop-ui
mvn clean package -DskipTests
java -jar target/desktop-ui-1.0-SNAPSHOT.jar
```

#### Desktop App Features

- **Connection state on every screen** — a persistent banner reports connected / reconnecting /
  disconnected plus how long ago the data was last refreshed. A single dropped poll shows
  *reconnecting*; three consecutive failures escalate to *disconnected* and the app says on-screen
  data is no longer live. It never silently keeps showing stale data as if it were current.
- **Live Dashboard** — cluster summary cards and per-node gauges, sourced from gRPC.
- **Node Management** — connect to a ControlPlane at any address, view nodes in a sortable table.
- **Task Execution** — submit tasks; progress reflects the real RPC lifecycle, and a task the control
  plane could not place is reported as failed.
- **History** — every task/job this device has ever submitted, which cluster it ran against, and its
  outcome — persisted locally, so it survives closing the app (see "Remembers your setup" above).
- **Live Monitoring** — rolling CPU/memory chart and application logs.
- **Settings** — grouped into Connection, Monitoring, Appearance, Saved setup and Certificates.
- **Dark/Light Themes** — structure lives in a colour-free `base.css` written against design tokens;
  `dark.css` and `light.css` each supply the token values, and a test fails the build if the two
  token sets diverge, so neither theme can be left behind.
- **Unavailable means unavailable** — a reading the node could not take renders as `n/a`, never as a
  number. A cluster with nothing measurable reports `n/a` rather than `0.0%`, and an empty cluster
  reports no health figure rather than a green 100%.
- **Predictor Placeholder** — shows `N/A` until PredictorService is running, and also when the node's
  telemetry is stale (a prediction from a known-stale reading would look more certain than it is).

### Local CLI

> **Most people want the [Desktop Application](#-desktop-application) instead.** This section starts
> the control plane and a node as bare headless Java processes, with no GUI at all — useful for
> servers, containers, or scripting, but it's a second, separate way to run this project, not a step
> you need on top of the desktop app. If you just want everything in one window with nothing to
> configure, skip straight to `cd desktop-ui && mvn clean compile && mvn javafx:run` above and ignore
> this section entirely.

```powershell
# Build
cd java-control-plane
mvn clean package -DskipTests

# Start server — note the -all jar: that's the shaded jar with dependencies bundled in.
# The plain control-plane-1.0-SNAPSHOT.jar has no Main-Class and no bundled dependencies, so running
# it (with -cp or otherwise) fails with NoClassDefFoundError on the first class it needs, e.g. slf4j.
$env:ROLE="server"; $env:PREDICTOR_HOST="localhost"
java -jar target/control-plane-1.0-SNAPSHOT-all.jar

# Start node (separate terminal)
$env:ROLE="agent"; $env:NODE_ID="node1"; $env:CONTROL_PLANE_HOST="localhost"
java -jar target/control-plane-1.0-SNAPSHOT-all.jar
```

This path has no bundled dashboard UI — the old static HTML/CSS/JS dashboard is gone for good (see the
note near the top of this README), superseded entirely by the desktop app's embedded WebView. What's
left on `DASHBOARD_PORT` (default `8085`) is only the real `/api/nodes` JSON endpoint, for anyone
scripting against live node data or building their own frontend — there is nothing to open in a
browser at `http://localhost:8085/` itself, and hitting it will 404 by design, not by mistake.

## Monitoring

The desktop app's own Monitoring screen (server mode or node mode — both connect to the control
plane the same way) is the primary place to watch the cluster: live per-node CPU/memory charts,
cluster health, and honest gaps where a node stopped reporting. That's real gRPC data end to end, not
a scrape.

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

`scripts/e2e-test.py` runs the fuller scenario against a live compose stack: nodes join, tasks
distribute, a node is killed and asserted `SUSPECTED_DEAD` within the timeout window, work reroutes
away from it, it rejoins without a duplicate entry, and Prometheus is confirmed to have scraped every
component.

> **Status of the Python scripts.** Both are committed but were **not executed** during the work that
> produced them — the Docker daemon was unavailable, and the pinned `grpcio==1.68.0` publishes no
> wheels for the Python 3.14 on that machine. Their assertions are therefore unverified. The same
> guarantees *are* verified by the JVM suite over a real gRPC transport: see
> `KillAndReconnectIntegrationTest` (registration, death within the window, rerouting, clean rejoin,
> control-plane restart recovery, a 20-node run) and `MutualTlsEndToEndTest` (enrolment, rejection
> without a certificate, rejection of a foreign CA, revocation on an open channel).

## Project Structure

```
next-gen-control-plane/
├── pom.xml                          # Root parent POM (multi-module)
├── proto/
│   └── control_plane.proto          # Shared gRPC contract
├── java-control-plane/              # Backend: gRPC services, DB, business logic
│   ├── pom.xml                      # Child POM (inherits from root)
│   ├── Dockerfile
│   └── src/main/java/com/nextgen/
│       ├── Main.java                # CLI entry point (ROLE-based)
│       ├── controlplane/            # ControlPlane Server, task/job registries, Raft consensus
│       └── agent/                   # NodeAgent
├── desktop-ui/                      # THE product: JavaFX desktop app, both roles
│   ├── pom.xml                      # Child POM (JavaFX, gRPC client)
│   └── src/main/java/com/nextgen/desktop/ui/
│       ├── DesktopApp.java          # JavaFX Application entry point
│       ├── client/                  # gRPC clients (ControlPlane, Predictor)
│       ├── model/                   # Observable data models
│       ├── service/                 # NodeMonitoring, TaskExecution, Theme
│       └── view/                    # Screens (Dashboard, Nodes, Tasks, Monitoring, Settings)
├── python-predictor/                # Python ML service (XGBoost risk classifier, LSTM forecaster)
├── deploy/                          # Prometheus scrape config for self-hosting the server
├── scripts/                         # Utilities & testing
├── ARCHITECTURE.md                  # Detailed architecture decisions, consensus/replication, trust model
├── docker-compose.yml               # Server-side deployment only (no simulated nodes)
├── docker-compose.raft.yml          # Opt-in 3-replica Raft topology
├── DEVELOPMENT.md
├── CHANGELOG.md
├── CONTRIBUTING.md
└── README.md
```

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Control Plane & Agents | Java 21, gRPC 1.68, Protobuf 3.25.5, Prometheus 0.16.0 |
| Phase-2 Desktop UI | JavaFX 21.0.2, gRPC client, Jackson 2.17.0 |
| Predictor | Python 3.11, grpcio, prometheus-client |
| Communication | Protocol Buffers 3 (proto3) |
| OS Metrics | `com.sun.management.OperatingSystemMXBean` (real readings) |
| Deployment | Docker Compose |
| Logging | SLF4J Simple (Java), `logging` module (Python) |
| Testing | JUnit 5.10.2, Mockito 5.11.0, JaCoCo 0.8.12 |

## Phase Roadmap

- **Phase-1** ✅ — 3-node cluster, real heartbeats, round-robin, predictor stub.
  Audited and corrected: immutable `NodeRecord` + `NodeRegistry` with atomic per-node updates;
  identity-based `RoundRobinScheduler`; `SUSPECTED_DEAD` nodes genuinely excluded from the rotation;
  clean re-integration on reconnect with no duplicate or zombie entries; agent-side capped
  exponential backoff and automatic re-registration after a control-plane restart; unavailable OS
  readings propagated as unavailable instead of `0.0`.
- **Phase-2** ✅ — Desktop UI (JavaFX).
  Audited and corrected: connection state surfaced on every screen; RPC failure no longer
  indistinguishable from an empty cluster; node status read from the wire instead of hardcoded
  `HEALTHY`; fabricated task progress removed; "✓ Connected" now requires an actual connection;
  "Launch Server" actually starts a server; token-based design system with dark/light parity enforced
  by a test; grouped Settings; animation corrected (tweens no longer overlap, motion only signals
  live state).
- **Phase-3** ✅ — mTLS authentication, WAN connectivity, real-time metrics charts.
  A real CA (EC P-256) issuing 30-day client certificates from CSRs, so a node's private key never
  leaves it; single-use 256-bit enrolment tokens bootstrapping trust; per-method certificate policy
  enforced at the gRPC layer; per-RPC revocation; rate-limited enrolment. Keepalive tuned for
  internet paths on both ends, capped exponential backoff, and measured RTT rather than claimed.
  A Prometheus service scraping every component, and live per-node charts that break the line where
  a node stopped reporting.
- **Phase-4** ✅ — Raft consensus, leader election, fault tolerance.
  A hand-rolled Raft implementation turning the control plane into an opt-in fault-tolerant 3-replica
  cluster — real leader election, log replication, a durable write-ahead log, and gRPC-based
  leader-redirect for both node RPCs and certificate issuance. Off by default
  (`RAFT_ENABLED=false`); see [Algorithms & Predictive Intelligence](#-algorithms--predictive-intelligence)
  above and `ARCHITECTURE.md`'s Consensus & replication section.
- **Phase-5** ✅ — ML-based predictive scheduling.
  Real gradient-boosted trees (XGBoost) for failure-risk classification and an LSTM for load
  forecasting, both opt-in and both honestly reporting `model_trained=false` until an operator
  actually trains them on real accumulated data. See
  [Algorithms & Predictive Intelligence](#-algorithms--predictive-intelligence) above.

---

## 🧪 Testing

### Running Tests

```bash
# Build and test all modules from root
cd next-gen-control-plane
mvn clean install

# Test only desktop-ui
cd desktop-ui
mvn clean test

# View coverage report
start desktop-ui/target/site/jacoco/index.html  # Windows
```

### Coverage

Measured on hand-written code, excluding the generated `com.nextgen.proto.*` classes (which would
otherwise inflate the denominator) and the JavaFX view classes (which need a running toolkit and a
display):

| Module | Instruction | Branch | Tests |
|---|---|---|---|
| `java-control-plane` | 63.9% | 52.5% | 293 |
| `desktop-ui` | 61.0% | 45.3% | 86 |
| **Total** | | | **379** |

Both modules **enforce** a 60% instruction minimum via the JaCoCo `check` goal; the build fails below
it. The previous gate was a bundle-wide 10% that *included* the generated protobuf classes — with
machine-written code dominating the denominator, that number measured nothing.

---

## ⚙️ Configuration

Everything is driven by environment variables through `EnvConfig`; there are no hardcoded hosts,
ports, or paths. A malformed value logs a warning and falls back to the documented default rather
than failing startup.

### Control plane (`ROLE=server`)

| Variable | Default | Purpose |
|---|---|---|
| `GRPC_PORT` | `50051` | gRPC listen port |
| `METRICS_PORT` | `9090` | Prometheus exporter port |
| `DASHBOARD_PORT` | `8085` | `/api/nodes` JSON endpoint only — no static frontend is served here; see the [Local CLI](#local-cli) note |
| `HEARTBEAT_TIMEOUT_MS` | `6000` | No heartbeat within this window → `SUSPECTED_DEAD` |
| `HEARTBEAT_CHECK_INTERVAL_MS` | `3000` | How often the liveness sweep runs |
| `PREDICTOR_HOST` / `PREDICTOR_PORT` | `predictor` / `50052` | Predictor service address |

### Node agent (`ROLE=agent`)

| Variable | Default | Purpose |
|---|---|---|
| `NODE_ID` | `unknown` | Identity this node registers under |
| `CONTROL_PLANE_HOST` | `control-plane` | Address the agent dials out to |
| `CONTROL_PLANE_PORT` | `50051` | Control-plane gRPC port |
| `NODE_METRICS_PORT` | `9091` | Prometheus exporter port. Defaults away from the control plane's `9090` so both roles can run on one host. |
| `HEARTBEAT_INTERVAL_MS` | `2000` | Heartbeat cadence |
| `AGENT_VERSION` | `1.0.0` | Reported at registration |
| `RECONNECT_INITIAL_DELAY_MS` | `1000` | First reconnect delay |
| `RECONNECT_MAX_DELAY_MS` | `30000` | Backoff ceiling |
| `RECONNECT_MULTIPLIER` | `2.0` | Backoff growth factor |
| `RECONNECT_JITTER` | `0.2` | Jitter band, so a fleet does not retry in lockstep |
| `NEXTGEN_AGENT_PKI_DIR` | `~/.nextgen/agent` | Where this node's key and certificate live |
| `NEXTGEN_CA_CERT` | *(unset)* | Path to the CA certificate used to verify the control plane |
| `NEXTGEN_ENROLLMENT_TOKEN` | *(unset)* | Single-use token for first enrolment |

### Security (both roles)

| Variable | Default | Purpose |
|---|---|---|
| `TLS_ENABLED` | `false` | Enforce mutual TLS. When off, policy runs in audit mode and only counts what it would reject. |
| `TLS_SAN_HOSTS` | `localhost,127.0.0.1` | Names the server certificate is valid for. **Must include the public hostname** clients dial. |
| `TLS_SSL_PROVIDER` | `JDK` | `JDK` or `OPENSSL`. See the provider note in `ARCHITECTURE.md`. |
| `ENROLLMENT_ENABLED` | `true` | Set `false` after provisioning to close the only anonymous entry point. |
| `ENROLLMENT_TOKENS` | *(unset)* | Comma-separated node ids to mint tokens for at startup |
| `ENROLLMENT_TOKEN_TTL_MINUTES` | `60` | How long a minted token stays usable |
| `NEXTGEN_PKI_DIR` | `~/.nextgen/pki` | CA and server key material. **Put this on a volume** — a new CA invalidates every enrolled node. |
| `ENROLL_RATE_PER_IP_BURST` | `5` | Enrolment attempts allowed per source in a burst |
| `ENROLL_RATE_GLOBAL_BURST` | `50` | Cluster-wide enrolment burst |
| `CERT_RENEW_WINDOW_MINUTES` | `10080` (7 days) | Agent-side: if the stored certificate is within this window of expiry, the node renews it — checked once at process startup (before connecting) **and** periodically thereafter by a background loop, so a long-lived node renews on its own without a restart. |
| `CERT_RENEWAL_CHECK_INTERVAL_MS` | `3600000` (1 hour) | How often the background renewal check runs after startup. |

### Connectivity (both roles)

| Variable | Default | Purpose |
|---|---|---|
| `GRPC_KEEPALIVE_TIME_MS` | `30000` | How often to ping an idle connection |
| `GRPC_KEEPALIVE_TIMEOUT_MS` | `10000` | How long to wait for the ping reply |
| `GRPC_PERMIT_KEEPALIVE_TIME_MS` | `20000` | Server-side floor. Clients pinging faster are disconnected — tune both together. |

> Every variable above can also be supplied as a JVM system property of the same name, which takes
> precedence. That exists so a single setting can be overridden per-process without a second
> configuration mechanism.

---

## 📶 Physical Cluster on a Shared Wi-Fi / Mobile Hotspot

This is the fastest way to see real physical machines forming a cluster: connect every laptop/phone to
the **same** Wi-Fi network or mobile hotspot, pick one machine as the server, and join the rest as
nodes — all through the desktop app's own onboarding screens, no config files and no tokens needed on
a trusted private network like this.

**Prerequisites**: JDK 21 on every machine that will *build* the app (a machine only *running* the
packaged jar needs a JRE 21); all machines joined to the same hotspot/Wi-Fi network.

**1. On the machine you want as the server:**

```bash
cd desktop-ui
mvn clean compile
mvn javafx:run
```

On first launch, choose **"Server — Host a Cluster"**, then on Server Setup pick **Native**
(simplest — no Docker required) or **Docker** (only enabled if a real Docker daemon is detected on
that machine). Once it starts, the same screen shows a **"LAN address"** field — this is the server's
real network IP, auto-detected from the machine's active Wi-Fi/hotspot interface (something like
`192.168.43.7:50051` on a typical phone hotspot). Write it down; every node needs it in step 3.

**2. Open the one port a node actually needs, on the server machine only.** Nodes only ever dial
*out* — the hub-and-spoke rule means no node needs any inbound rule at all. On the server, only gRPC
port **50051** is required (PowerShell, run as Administrator on the server machine):

```powershell
New-NetFirewallRule -DisplayName "NextGen Control Plane" -Direction Inbound -Protocol TCP -LocalPort 50051 -Action Allow
```

Optional, only if you want to reach them from another machine's browser — neither is needed for nodes
to join or run jobs: dashboard API on `8085`, Prometheus metrics on `9090`. If you'll also run
distributed Docker-Compose projects whose services need to reach each other across nodes (see the next
section), additionally allow **40000–40999** (TCP inbound) — the control plane's cross-node relay port
range; still only on the server, for the same reason.

**3. On every other machine (the ones contributing compute):**

```bash
cd desktop-ui
mvn clean compile
mvn javafx:run
```

Choose **"Node — Join a Server"**. In **"Server address (host[:port])"**, type the LAN address from
step 1 (e.g. `192.168.43.7:50051`). Leave **"Enrolment token"** blank — this project defaults to a
plaintext connection (`TLS_ENABLED=false`), which is the right choice on a private network you already
trust; a token is only needed once you deliberately turn on mTLS (see
[Running across the internet](#-running-across-the-internet) below for when that actually matters).
Click **Connect** — the node registers immediately, no restart required.

**4. Confirm it worked.** Back on the server machine's dashboard, open **Nodes** — every connected
machine should appear within a couple of seconds (heartbeat interval is 2s), each reporting real
CPU/memory/battery telemetry, not placeholder values.

> **No auto-discovery exists** — confirmed directly in the source: there is no mDNS/broadcast
> mechanism anywhere in this codebase, so the server's LAN address must be typed in manually on each
> node. The Server Setup screen detecting and displaying its own real LAN IP for you is what makes
> this bearable — you never need to go hunting for it via `ipconfig`. If the hotspot's host device
> reassigns IP addresses on reboot (common on phones), the server's LAN address can change between
> sessions; re-check the Server Setup/Settings screen and re-enter it on nodes if it did. A node that
> drops off the hotspot is detected as unreachable within 6 seconds by the reactive heartbeat timeout,
> sooner if the predictive risk model already flagged it (e.g. low battery) before it actually
> disconnected.

---

## ▶️ Executing a Project on the Cluster (the `nx` CLI)

Once a cluster exists — either the hotspot cluster above or a single machine talking to itself over
`localhost` — running a real multi-service project across it is a CLI action, the same way
`docker compose up` itself is a terminal command even when Docker Desktop's GUI is open. The desktop
app's **Containers / Images / Volumes / Networks** pages (server role) show you what's actually
running, live, and let you start/stop/restart/remove individual containers, but *launching* a new
project is done with the `nx` CLI, a separate Maven module (`cli/`) in this same repo that talks to
the exact same control plane your desktop app is running.

**1. Build the CLI once** (needs the rest of the reactor available locally, hence `-am`):

```bash
mvn -pl cli -am install -DskipTests
```

This produces a self-contained fat jar at `cli/target/nextgen-cli-*.jar` — nothing but a JRE is
needed to run it afterward.

**2. Sanity-check connectivity** before submitting anything (replace the address with your server's —
`localhost:50051` if you're on one machine, or the hotspot LAN address from the previous section):

```bash
java -jar cli/target/nextgen-cli-*.jar nodes --control-plane 192.168.43.7:50051
```

This should list every currently connected node. If it doesn't, fix connectivity first — the firewall
rule from the previous section is the usual culprit.

**3. Run the example project shipped in this repo**, `examples/hello-cluster/` — a real, working
two-service project, not a placeholder: `web` runs the public `nginx:alpine` image unmodified;
`worker` has its own `Dockerfile` (`examples/hello-cluster/worker/Dockerfile`) and is only built with
`--build`, specifically to demonstrate a node building from source it never had on local disk:

```bash
java -jar cli/target/nextgen-cli-*.jar up examples/hello-cluster/docker-compose.yml \
  --project hello --build --control-plane 192.168.43.7:50051
```

What actually happens, in order:

1. The CLI parses the compose file locally (it understands a documented subset — `image`,
   `build.context`/`build.dockerfile`, `command`, `environment`, `ports`, `depends_on` — not the full
   Compose spec).
2. Because `worker` has a `build:` block, the CLI tars `examples/hello-cluster/worker/`, hashes it
   (SHA-256), and streams it to the control plane over a chunked upload RPC.
3. The control plane verifies the hash against what it actually received, stages it, and picks one
   idle, Docker-capable node per service via the capability-aware scheduler (see
   `HeuristicNodeCapacityScorer` in [Algorithms & Predictive Intelligence](#-algorithms--predictive-intelligence)
   for the exact weighting formula).
4. It streams the staged tarball down the target node's *already-open* outbound channel — the node
   never accepts a new inbound connection — where it's unpacked, the hash re-verified, and a real
   `docker build` runs before the container starts.
5. `web` starts directly from the public image on whichever other idle node was picked.
6. `nx up` prints `Job '<job-id>': ACCEPTED...` immediately — that job id (e.g. `hello-a1b2c3d4`,
   `<project>-<8 hex chars>`) is what you pass to `ps`/`logs`/`down` below. By default it then follows
   live: each output line is prefixed with just the service name, e.g. `worker | hello from ...` —
   pass `--no-follow` to submit and return immediately instead (useful for a long-running service you
   don't want to keep the terminal attached to).

**4. Manage the running project** (separate terminal, using the job id `nx up` printed):

```bash
java -jar cli/target/nextgen-cli-*.jar ps <job-id>   --control-plane 192.168.43.7:50051   # status per service/node
java -jar cli/target/nextgen-cli-*.jar logs <job-id> --control-plane 192.168.43.7:50051   # live output
java -jar cli/target/nextgen-cli-*.jar down <job-id> --control-plane 192.168.43.7:50051   # stop it
```

**5. Watch it from the desktop app.** Open the server's **Containers** page — both containers show up
there in real time (name, image, status, ports, uptime), reflecting exactly what `docker ps` would
report if you ran it directly on whichever physical node each service landed on. You can Start, Stop,
Restart, or Remove either one right from that page; **Images / Volumes / Networks** are list-only
views of the same real, per-node Docker state.

> **Single machine, no cluster at all?** `nx cloud up` bypasses node registration and scheduling
> entirely and runs a compose file directly on one machine's own Docker daemon — the literal
> equivalent of plain `docker compose up --build`:
> ```bash
> java -jar cli/target/nextgen-cli-*.jar cloud up --target 192.168.43.7:50051 --build examples/hello-cluster/docker-compose.yml
> ```
> This only works if that server was started with `LOCAL_DOCKER_EXEC_ENABLED=true` and a real Docker
> daemon is reachable on it — otherwise the call fails cleanly with `UNIMPLEMENTED` rather than
> silently doing nothing.

> **What's not built yet, stated plainly**: `nx logs` only follows live output — there's no
> server-side history to replay if you attach after a container's already produced output.
> Image/volume/network write actions (pull, rm, tag, create) aren't implemented, only listing.
> Interactive `exec` into a running container isn't implemented either. None of these are silently
> half-working — each fails or is simply absent, never faked.

---

## 🌐 Running across the internet

Nodes always dial **out**, so a node behind home or office NAT needs no port forwarding, no static
address and no inbound firewall rule. Only the control plane needs a reachable address, and only one
port on it.

```bash
# On the public host
export TLS_ENABLED=true
export TLS_SAN_HOSTS=control.example.com,203.0.113.10
export ENROLLMENT_TOKENS=node1,node2,node3
docker compose up -d control-plane prometheus
# The startup log prints one single-use token per node id.

# On each node, anywhere
export ROLE=agent NODE_ID=node1
export CONTROL_PLANE_HOST=control.example.com
export NEXTGEN_CA_CERT=/etc/nextgen/ca.crt      # copied from the control plane
export NEXTGEN_ENROLLMENT_TOKEN=<token for node1>
java -jar control-plane-1.0-SNAPSHOT-all.jar
```

Once every node has enrolled, set `ENROLLMENT_ENABLED=false` and restart the control plane to close
the only anonymous endpoint. See `ARCHITECTURE.md` for the full connectivity and trust model.

> **Certificate renewal runs on a timer, not just at startup.** A node checks whether its certificate
> needs renewing once at process start, and then periodically thereafter (`CERT_RENEWAL_CHECK_INTERVAL_MS`,
> hourly by default) via a background loop — `NodeAgent.CertificateRenewalLoop` — so a node left running
> continuously for longer than the certificate lifetime (30 days by default) renews itself without a
> restart. The renewal RPC is authenticated by the node's own current certificate over mTLS, no token
> involved; on success the old certificate is revoked server-side and the connection's cached channel is
> discarded so the next RPC picks up the new one. A failed renewal attempt (e.g. the control plane is
> briefly unreachable) leaves the still-valid existing certificate untouched and retries with backoff —
> see `CertificateRenewalLoopTest` for the exact guarantees.

---

## 📚 Documentation

- **[DEVELOPMENT.md](DEVELOPMENT.md)** — Developer setup, building, debugging
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — Detailed architecture decisions
- **[ALGORITHMS.md](ALGORITHMS.md)** — Every algorithm this project runs: which, how, why, what problem
  it solves, and real measured evidence for each
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