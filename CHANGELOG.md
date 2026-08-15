# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security - Real CVEs fixed in pinned dependencies

Prompted by GitHub's Dependabot alerts on this repo. Every version below was checked against actual
current pinned versions and verified via authoritative sources — `api.osv.dev` for affected-version
ranges, `repo1.maven.org`/`pypi.org`'s own package metadata for true-latest (not `search.maven.org`,
whose index proved stale, topping out ~15 months behind). Each bump was then verified against this
project's real test suite, not assumed compatible from release notes alone.

- **`grpc.version` 1.68.0 → 1.83.1, `protobuf.version` 3.25.5 → 3.25.8** — fixes CVE-2025-55163
  ("MadeYouReset" HTTP/2 DoS) in `grpc-netty-shaded`, present in every version before 1.75.0. Bumped to
  latest stable, not just the minimum fix, to pick up every subsequent patch too.
- **`jackson.version` 2.17.0 → 2.22.1** — fixes five real `jackson-databind` CVEs: CVE-2026-59888
  (`@JsonIgnore` on a Record property bypassed via `PropertyNamingStrategy`), CVE-2026-54515
  (case-insensitive deserialization bypasses `@JsonIgnoreProperties`), CVE-2026-54514
  (`InetSocketAddress` deserialization triggers eager DNS resolution — SSRF), and CVE-2026-54512 /
  CVE-2026-54513 (`PolymorphicTypeValidator` bypasses allowing arbitrary class instantiation).
- **`bouncycastle.version` 1.78.1 → 1.85** — fixes CVE-2025-8916 (`PKIXCertPathReviewer` excessive
  allocation DoS — directly relevant, since `CertificateAuthority` does real X.509 cert-path handling
  for node enrolment) and CVE-2026-5588 (`CompositeVerifier` accepts an empty signature sequence as
  valid). 1.85 is the highest version published for all three artifacts this project actually uses
  (`bcpkix-jdk18on`, `bcprov-jdk18on`, `bcutil-jdk18on`).
- **Python `protobuf` 5.28.3 → 5.29.6** (`python-predictor/requirements.txt`) — fixes CVE-2025-4565
  and CVE-2026-0994 (both recursion-depth DoS bugs in the protobuf Python bindings). Verified compatible
  with the pinned `grpcio-tools==1.68.0`'s own `protobuf<6.0dev,>=5.26.1` requirement.
- Checked and confirmed clean (no known CVEs against the pinned version): SnakeYAML 2.2, OSHI 6.6.5,
  both Prometheus clients (Java `simpleclient` 0.16.0, Python `prometheus-client` 0.21.0), SLF4J 2.0.16,
  and the Python `grpcio`/`grpcio-tools` 1.68.0, `numpy` 2.1.3, `xgboost` 3.4.0, `torch` 2.13.0 pins.

### Added - Distributed Docker-Compose execution and the `nx` CLI (Stages L-R)

Turns the cluster into a real distributed `docker compose`-style execution engine — a whole
multi-service project spread across whichever nodes are currently idle — plus a single-machine "cloud"
mode and the `nx` CLI tool that drives both. See
[README's Distributed Docker-Compose execution section](README.md#distributed-docker-compose-execution--real-opt-in-per-task-kind)
and [docs/ARCHITECTURE.md's Distributed container execution section](docs/ARCHITECTURE.md#distributed-container-execution)
for the full design.

- **New `TaskKind.DOCKER_COMPOSE_SERVICE`**, additive alongside the original `PRIME_COUNT_RANGE`.
- **Node-side execution** (`DockerComposeServiceExecutor`/`DockerComposeRunner`) — shells out to the
  real `docker` CLI, streams real stdout/stderr back over `TaskChannel`. `DockerCapabilityDetector` now
  confirms an actual daemon round-trip (`docker info`), not just CLI presence, before reporting a node
  as Docker-capable.
- **Build-from-source on a remote node** — `UploadBuildContext` (CLI → control plane, chunked,
  SHA-256-verified) plus delivery down the node's own `TaskChannel` (`BuildContextStore`/
  `NodeBuildContextStore`), so a node with no prior copy of the source can still build the image itself.
- **Cross-node service networking** via a control-plane relay (`TunnelPort`/`PortRelayManager`/
  `PortTunnelClient`) — services on different nodes reach each other through injected
  `<PEER>_HOST`/`<PEER>_PORT` environment variables, preserving the hub-and-spoke rule (no direct
  node-to-node connections). Not transparent DNS resolution — a named, honest limitation.
- **Scheduling**: node-level exclusivity (a node running one compose service is excluded from a second
  concurrent project, but other idle nodes remain eligible) and a project-status reduce
  (`{"services": [...]}` instead of a summed number) for this task kind.
- **Cloud/single-machine mode** (`LocalDockerExecutionServiceImpl`, opt-in via
  `LOCAL_DOCKER_EXEC_ENABLED`) — `nx cloud up` runs a compose project on one operator-designated host
  with zero cluster involvement, reusing the same `DockerComposeRunner`.
- **New `cli/` Maven module (`nextgen-cli`)** — the `nx` command-line tool: `enrol`, `up`, `down`, `ps`,
  `logs`, `nodes`, `cloud up`, modeled on `docker compose`'s own command set. Parses a documented subset
  of `docker-compose.yml` locally (the control plane never parses YAML).

### Fixed - Three real bugs found by actually running the cluster end to end

A live run of a real control plane plus two real `NodeAgent` processes, a real two-service compose
project submitted via `nx up`, surfaced three bugs no amount of unit/integration testing under mocks
had caught:

- **`nx` CLI could not talk to this project's own default deployment** — every command hard-coded
  mutual TLS, but `docker-compose.yml`'s own default is `TLS_ENABLED=false`. The CLI now defaults to
  plaintext (matching the server default) and takes `--tls` to opt into mTLS after `nx enrol`.
- **`StreamJobEvents` was proto-only** — added to the schema in Stage L with an honest "today nothing
  relays that traffic back out to a non-node caller" note, but no stage ever actually implemented the
  server-side handler, so `nx up`/`nx logs` failed with `UNIMPLEMENTED`. Implemented for real
  (`JobEventBroadcaster`), wired into the existing `TaskChannel` progress/result/log handling.
- **Cross-node relay used the wrong port** — `JobCoordinator` was injecting the control plane's own
  *externally-reserved* relay port into a service's `relay_ports` field, instead of that service's own
  *local* published port. The two are different numbers; conflating them meant a provider node opened a
  tunnel to the wrong local target and every relayed connection would silently fail. Fixed, with a
  regression test asserting the two ports are distinct.

Verified live afterward: a `web` service on one node reached a `database` service's real, separately
published port on a different node through the fixed relay path, with real log lines streaming back to
the CLI in real time. `RiskMonitor`/`ProactiveMigrator` also fired unprompted during this run — genuine
heartbeat RTT pressure on the (heavily loaded, single) demo machine triggered a real proactive
migration, followed by a real reactive retry after a transient Docker container-name collision (an
artifact of two "nodes" sharing one Docker daemon in a single-machine prototype, not something that can
happen on genuinely separate physical hosts).

### Removed - Dead code, the frozen web dashboard, and leftover scratch files

A cleanup pass removing things that had no live consumer and content that was never meant to be
committed project documentation, rather than continuing to carry them as unused weight.

- **`com.nextgen.desktop.v2`** (`java-control-plane/src/main/java/com/nextgen/desktop/v2/` and its
  test package) — SQLite persistence via Hibernate, a join-approval workflow, connection tokens, and
  `ClusterManagerServiceImpl`. Compiled and unit-tested but never constructed at runtime; `Main` only
  ever dispatched to `ControlPlaneServer` or `NodeAgent`. Previously retained deliberately as
  documented dead code — now deleted outright since nothing in the running system used it.
- **`com.nextgen.desktop`** (the non-`v2` root package: `NodeConfig`, `ServerConfig`,
  `ProcessService`, the `model`/`exception` subpackages) — the same story: no imports, no reflection
  usage, no tests, referenced from nowhere in the running system.
- **The Hibernate/SQLite/Jakarta Persistence dependencies** (`java-control-plane/pom.xml` and the
  root `pom.xml`'s dependency management) and `META-INF/persistence.xml`, which existed solely for
  the package above.
- **The web dashboard** (`dashboard/` — static HTML/JS frontend, nginx config, its own Dockerfile).
  Superseded by the desktop app as the maintained interface; the control plane's own `/api/nodes`
  JSON endpoint (`DashboardApiHandler`) is untouched and still serves real data for anyone who wants
  to point a frontend at it — only the bundled static frontend itself is gone.
- **Leftover AI-prompt/spec scratch files**: `addition.md`, `modification.md` (repo root) and
  `docs/codingpromt.md` — raw instruction-prompt text from earlier development phases, not
  documentation, referenced from nowhere.
- **`docs/TESTING.md`** — a testing guide entirely about `desktop.v2` (every example used its
  SQLite/Hibernate/entity classes); removed alongside the code it described, since none of it
  applied to anything else in the project.
- **`docs/CODE_SNIPPETS.md`** — a stale, report-style annotated code reference predating most of the
  project's real functionality (no mention of tasks, jobs, risk scoring, ML, or Raft), three of whose
  thirteen snippets were already about the `v2`/dashboard code removed above.
- Stray JVM crash-dump logs (`hs_err_pid*.log`) that had accumulated inside
  `desktop-ui/src/main/java/com/nextgen/desktop/ui/view/components/` — gitignored, never tracked, but
  cluttering the actual source directory on disk.
- `README.md`/`docs/ARCHITECTURE.md`/`DEVELOPMENT.md`/`CONTRIBUTING.md`/`docker-compose.yml` updated
  to stop referencing any of the above as though it still existed.

### Added - Phase-2 rebuild: the join flow actually works over the internet now

The onboarding screens (`RoleSelectionView` → `ServerSetupView` / `NodeJoinView`) were the last piece
of the UI still built entirely around a LAN-only model, flagged as a named gap at the end of Phase 1.

- **Server address is now the primary way to join, not a hidden "Advanced" field.**
  `NodeJoinView`'s hero input is a plain `host[:port]` field — the same thing that already worked
  when buried in "Advanced: Direct IP Connection", now promoted, honestly probed for reachability
  before anything is reported as connected, and paired with a real failure message instead of a
  generic one. The old "Advanced" section's direct-connect button never actually checked reachability
  at all — it fired the connect callback immediately — which is fixed as part of promoting it.
- **The LAN-only "Server ID" quick-connect code is now explicitly secondary.** Both
  `ServerSetupView` and `NodeJoinView` show it collapsed behind a toggle labelled "same network
  only", with on-screen text explaining why it won't work for a node anywhere else:
  `ServerIdCodec` can only ever encode a private, site-local IPv4 address.
- **`ServerSetupView` now shows real, current configuration instead of a LAN IP presented as the
  whole answer.** The port shown is `GRPC_PORT` as actually configured (previously a hardcoded
  default that could silently disagree with what the server bound), and encryption state
  (`TLS_ENABLED`) is displayed honestly — green when mTLS is required, an explicit amber warning
  when it isn't. A new editable "public address" field lets the operator record the hostname a
  node from outside the LAN should actually use; nothing can auto-detect this from inside a NAT, so
  the screen asks rather than guessing or omitting it.
- **The desktop app's own node-mode registration is now capable of mTLS enrolment.** Previously only
  the standalone CLI `NodeAgent` could enrol — `DesktopApp`'s node-join path called the old plaintext
  `registerNode` RPC unconditionally, with no way to reach the `Enroll` RPC at all. This meant the
  Settings → Certificates panel (built in Phase 3) could never show anything but "Not enrolled" for
  anyone actually using the product. `NodeJoinView` now has an optional enrolment-token field; when
  filled in, the same `NodeAgent.ensureEnrolled(...)` sequence the CLI agent uses (now `public`,
  reused rather than duplicated) runs before the connection is made, and `GrpcConnectionManager`
  gained a `connectSecurely(...)` path that builds a real mutual-TLS channel from the resulting
  certificate. Leaving the token blank keeps the previous plaintext behaviour unchanged.
- **A registration failure no longer silently shows the dashboard anyway.** The previous code logged
  the exception and proceeded to `showMainDashboard()` regardless of whether registration succeeded.
  Both a failed connection and a failed registration now show a specific error and return the user to
  the join screen rather than presenting a node that was never actually added to the cluster.

### Added - A real error taxonomy, not one generic "control plane error"

"Something went wrong" was not an error message a user could act on. Three failure shapes are now
classified distinctly, wherever a connection is attempted:

- `ErrorCategory` (`NOT_FOUND`, `NETWORK`, `OVERLOAD`, `AUTHENTICATION`, `SERVER_ERROR`, `UNKNOWN`) —
  the fixed small set every failure is sorted into, shared by both layers that can detect one.
- `ConnectionDiagnostics` classifies a raw socket failure — the case before any gRPC channel exists —
  distinguishing an unresolvable hostname (typo) from connection-refused (nothing listening) from a
  timeout (likely a firewall silently dropping packets) from a TLS handshake failure, instead of
  treating every `IOException` identically.
- `ControlPlaneUnavailableException` now carries a `category()` alongside its message, and gained the
  **overload** case that was missing entirely: a `RESOURCE_EXHAUSTED` response from the Phase-3 rate
  limiter (enrolment being throttled) is now reported as "too many attempts, try again in Ns" — reading
  the server's `retry-after-millis` trailer when present — rather than falling into the same generic
  bucket as an unrelated server-side crash.

### Changed - Window sizing

The minimum window size dropped from a hardcoded 1280×800 to 960×640. The larger floor was never
load-bearing — every layout in the app is built from grow-priority HBox/VBox, `ScrollPane`, and a
wrapping `FlowPane` for the node grid, specifically so the window can be usefully smaller than a full
display, not just larger. The window was already resizable (nothing had ever called
`setResizable(false)`); the previous floor just prevented "resizable" from meaning much on a smaller
screen.

### Changed - Architecture pivot: real nodes, not simulated ones

The product is now explicitly the desktop application, in both roles, running on real machines
anywhere on the internet — not Docker containers standing in for nodes on a shared local network.

- **`docker-compose.yml` no longer starts fake nodes.** `node1`/`node2`/`node3` are removed. It now
  brings up exactly the server side of the system: `control-plane`, `predictor`, `prometheus`. A real
  node is an install of the desktop app in Node mode, on whatever machine and OS it's actually
  running on, pointed at the server's address.
- **The web dashboard is frozen, not deleted.** No further feature work goes into `dashboard/`; its
  source is untouched and it still functions if built and served by hand. The desktop app is the one
  actively developed interface for both roles. Its compose service was removed accordingly.
- **`deploy/prometheus.yml` no longer lists per-node scrape targets.** They were never going to work
  once nodes stopped being containers on the compose network: Prometheus scraping is pull-based, and
  a real node behind home or office NAT is, by design, unreachable for an inbound scrape — the same
  property that lets it join without opening a port also means Prometheus can't reach it directly.
  Per-node history in the desktop UI was never affected by this; it already flowed over gRPC through
  the control plane, which is the one thing in this architecture guaranteed to be reachable.
- **Removed a Windows-only `java.io.tmpdir` override from all three POMs.** It pointed at
  `${user.home}/AppData/Local/Temp`, a path that doesn't exist on macOS or Linux, and — checked
  against this session's own build log — it was never actually effective in the first place (Maven
  project properties aren't pushed into `System.getProperty()` for plugins running in the build JVM).
  It was dead configuration that was also a real cross-platform hazard; removed rather than kept.
- Cross-platform audit of the application code: confirmed no OS-specific branching exists in
  `desktop-ui` or the live `controlplane`/`agent`/`security` packages; confirmed `PkiPaths` and
  `AgentCredentials` build every path through `Paths.get(...)` rather than string concatenation;
  confirmed the JavaFX dependencies carry no hardcoded platform classifier, so Maven resolves the
  correct native artifact for whatever OS the build actually runs on.

### Fixed - NodeAgent could not actually complete mTLS enrolment

Found during this pivot's own verification pass, not by inspection alone: the README's WAN example
set `NEXTGEN_ENROLLMENT_TOKEN` and expected the standalone agent to enrol itself automatically. It
couldn't. Two separate bugs, both in `NodeAgent`, both invisible to `MutualTlsEndToEndTest` because
that test builds its gRPC channels directly rather than calling `NodeAgent`'s own methods:

- **`NodeAgent.start()` never called the `Enroll` RPC at all.** `buildChannel` read a CA certificate
  to verify the server, but nothing generated a CSR, presented the enrolment token, or stored an
  issued certificate. `NEXTGEN_ENROLLMENT_TOKEN` was read by no code path — set it and it did nothing.
- **Worse: `buildChannel` used the enrolment-only (server-auth) TLS context even for the ongoing
  operational connection.** Even a node with a certificate already sitting on disk from a previous
  successful enrolment would never have presented it — every heartbeat would be sent with no client
  certificate at all, and rejected by `MtlsPolicyInterceptor` the moment the server enforced policy,
  since `SendHeartbeat` is not on the anonymous allowlist.
- Added `NodeAgent.ensureEnrolled(...)`: loads any existing certificate, checks it against a renewal
  window (default 7 days before expiry), and — only if needed — builds a short-lived enrolment-only
  channel, sends a CSR with the token attached as a metadata header, stores the issued certificate,
  and discards that channel (per the documented one-cert-auth-per-connection constraint). Fails fast
  with a specific, actionable message rather than falling back to plaintext or hanging: missing CA
  certificate, missing token, or a rejected token are all distinct, named failures — never a silent
  security downgrade.
- `buildChannel` now correctly uses `TlsConfig.mutualClientContext` with the node's own issued
  certificate for the operational connection, and refuses outright (rather than degrading to
  plaintext) if `TLS_ENABLED=true` but no certificate is present.
- New `NodeAgentEnrollmentTest`, which — unlike the existing mTLS suite — calls `NodeAgent`'s actual
  production methods against a real server: enrolment followed by a real heartbeat over the resulting
  channel, skip-on-valid-certificate, and fail-fast paths for a missing CA cert, a missing token, and
  a rejected token.

### Known gap, not yet fixed

`ServerSetupView`'s "Server ID" quick-connect encodes only a site-local IPv4 address
(`ServerIdCodec.detectLanIp()` deliberately filters for private ranges) and cannot represent a
hostname. It's a LAN-only convenience that was the *primary* onboarding flow; for a server reachable
only by a public hostname it produces a code that looks valid but doesn't work. The WAN-capable path
(hostname + port, already wired end-to-end via `CONTROL_PLANE_HOST` / `GrpcConnectionManager` / the
"Advanced" direct-connect field) is functionally complete but is not yet the *primary* path in the
UI. Redesigning that screen is scoped to the next UI pass, not fixed here, since it's a screen
redesign rather than a backend change.

### Added - Phase-3: mutual TLS, WAN connectivity, real charts

#### Mutual TLS with token-bootstrapped enrolment
- New `com.nextgen.security` package: `CertificateAuthority`, `CertificateDenylist`, `PkiPaths`,
  `TlsConfig`, `PeerIdentity(Interceptor)`, `MtlsPolicyInterceptor`, `EnrollmentTokenStore`,
  `TokenBucket`, `RateLimitInterceptor`; plus `NodeEnrollmentServiceImpl` and `AgentCredentials`.
- **A real CA.** EC P-256 / `SHA256withECDSA`, self-bootstrapping, with a serial counter and an
  issuance ledger. This replaces `TlsCertificateGenerator`, which did not create an X.509 certificate
  at all — it base64'd a raw public key, wrapped it in `BEGIN CERTIFICATE` markers, and concatenated
  **the private key** into the same string before sending the whole thing to the peer.
- **The node's private key never leaves the node.** Enrolment is CSR-based: the agent generates its
  own key pair and sends only a PKCS#10 request. The server verifies proof-of-possession and then
  **discards the CSR's subject**, using the node id the consumed token was bound to — otherwise any
  enrolling node could name itself anything it liked.
- **Single-use 256-bit enrolment tokens**, stored only as SHA-256, consumed atomically via
  `ConcurrentHashMap.remove` so concurrent attempts yield exactly one winner. The previous connection
  token was 8 characters from a 32-symbol alphabet — 40 bits, brute-forceable in hours.
- **Per-method policy enforcement.** `ClientAuth.OPTIONAL` at the transport plus an interceptor:
  enrolment is reachable anonymously, everything else requires a valid, unexpired, unrevoked
  certificate. The interceptor is required regardless of transport settings — without it, node A
  could heartbeat as node B over its own valid connection.
- **Revocation is checked per RPC, not per handshake.** A CRL would leave a compromised node working
  on its existing long-lived channel until it happened to reconnect.
- **Rate limiting on enrolment only**, two-tier token bucket (per source IP, then global). Per-IP is
  checked first so an abusive source cannot drain the global budget and lock out legitimate nodes.
  IPv6 buckets on the /64 prefix, or one host would own 2^64 buckets. `X-Forwarded-For` is
  deliberately not trusted.
- `TLS_ENABLED` defaults to false; interceptors then run in **audit mode**, counting what they would
  have rejected in `controlplane_mtls_would_deny_total{reason}` so readiness is observable before
  enforcement is switched on.
- Key material is restricted to the owner where the filesystem allows (POSIX mode set atomically at
  creation; owner-only ACL on Windows), with `controlplane_pki_permissions_enforced` exposing the
  cases where it could not be.

#### WAN connectivity
- gRPC keepalive tuned for an internet path on **both** ends, as a matched pair — a client pinging
  more often than the server permits is answered with `ENHANCE_YOUR_CALM` and disconnected, so
  one-sided tuning makes things worse.
- `node_heartbeat_rtt_seconds` as a **Histogram** measured agent-side with `System.nanoTime()`, plus
  `node_clock_skew_seconds` (NTP-style estimator) and `node_reconnects_total`. A gauge of the last
  RTT would hide the tail, which is the only interesting part of a latency distribution.
- Documented explicitly that absolute one-way latency across unsynchronised clocks is not measurable,
  and is therefore not reported.

#### Charts and metrics plumbing
- Added a **`prometheus` service** to `docker-compose.yml` with `deploy/prometheus.yml`, scraping the
  control plane, all three agents and the predictor. Published on host `9464` because `9090` is
  already the control plane's own exporter.
- Desktop Monitoring screen rewritten around live per-node CPU and memory charts. **A node that stops
  reporting produces a break in its line, never a segment bridging the outage** — `MetricsHistory`
  splits each node's samples into contiguous runs and the chart renders each run separately.
- CPU and memory are separate charts rather than one dual-axis chart: two y-scales invite comparison
  between quantities that were never comparable, and the crossing point is an artefact of the axis
  choice.

### Fixed - Phase-1 foundation audit

Correctness fixes in the control plane and node agent. Each item below was a reproducible defect in
the shipped code, and each now has a named regression test.

- **Lost-update race between registration and heartbeat.** `registerNode` did an unconditional
  `registry.put()` of a brand-new `NodeRecord`, so a concurrent heartbeat could write into the object
  that `put()` had just orphaned. The heartbeat was silently discarded and the node was later declared
  `SUSPECTED_DEAD` despite an unbroken heartbeat stream. `NodeRecord` is now immutable and all
  mutation goes through `ConcurrentHashMap.compute`/`computeIfPresent` in the new `NodeRegistry`,
  making read-decide-write atomic per node.
- **Re-registration wiped live telemetry.** Reconnecting reset a healthy node's CPU and memory to a
  phantom `0%`. Registration now merges: network identity (ip/port/hostname) updates, readings carry
  forward, and the response reports `resumed_existing`.
- **Round-robin was neither round nor robin.** The candidate list was rebuilt from
  `ConcurrentHashMap.values()` — unspecified iteration order that changes on resize — and indexed with
  `Math.abs(counter.getAndIncrement()) % size` against a list whose size changed as nodes died,
  starving some nodes and double-loading others. `Math.abs(Integer.MIN_VALUE)` is also still negative,
  crashing the RPC after 2^31 submissions. Replaced by `RoundRobinScheduler`, which rotates over node
  identity in a sorted snapshot and has no counter to overflow.
- **`controlplane_active_nodes` never decreased.** It was only ever `set()` in `registerNode`, and to
  the total registry size rather than the alive count. Replaced by `NodeRegistryCollector`, which
  derives counts from the registry at scrape time; added `controlplane_nodes{status}` and
  `controlplane_registered_nodes`.
- **Liveness sweep could clobber a heartbeat that had already arrived.** The monitor iterated
  `values()` and mutated the records it found, so it could read a stale timestamp and overwrite an
  `ALIVE` status set moments earlier — excluding a healthy node from scheduling for a full check
  interval. The sweep now iterates keys and mutates atomically per key.
- **Nodes never recovered from a control-plane restart.** With an empty registry every heartbeat
  returned `UNKNOWN_NODE` forever and the agent logged it at INFO alongside the normal case. The
  response now carries `reregistration_required`, and the agent re-registers automatically.
- **Unavailable OS readings were reported as real values.** `OperatingSystemMXBean.getCpuLoad()`
  returns `-1` when no reading is available; the agent clamped that to `0.0f` and sent it as fact.
  Readings now carry `cpu_available`/`memory_available` on the wire; the control plane preserves the
  last known value and flags it stale; the dashboard emits `null` plus `cpuStale`/`memoryStale`
  rather than a number. Stale values are excluded from cluster averages, and an empty cluster reports
  `null` averages instead of `0.00`.
- **Dashboard JSON was invalid outside English locales.** `String.format("%.2f", …)` used the default
  locale, emitting `"cpuUsage":45,50`. All numeric formatting now uses `Locale.ROOT`, with NaN and
  infinity guarded.
- **Agent gave up after 10 fixed-delay retries and called `System.exit(1)`**, requiring a manual
  restart after any transient blip. Replaced by `BackoffPolicy` — capped exponential backoff (1s → 30s)
  with jitter — retrying indefinitely.

### Fixed - Phase-2 desktop UI honesty

- **An RPC failure was indistinguishable from an empty cluster.** `ControlPlaneClient.getNodes()`
  caught `StatusRuntimeException` and returned `List.of()`, so a dead control plane rendered as
  "0 nodes, 100% healthy". All client methods now throw `ControlPlaneUnavailableException`, carrying
  a short user-facing reason with no stack trace in it.
- **Every node was stamped `HEALTHY`.** `NodeMonitoringService` hardcoded the status because
  `NodeInfo` had no status field; the "Warning" counter was therefore structurally always zero and a
  dead node displayed as healthy. Status now maps from the real `NodeStatus` enum.
- **Last-heartbeat time came from the local clock** (`LocalDateTime.now()`), so every node looked
  like it had just reported. It now uses the control plane's `last_heartbeat_epoch_millis`.
- **The "LIVE" pill was hardcoded** and lit regardless of connectivity, as was the sidebar's green
  role badge. The pill is now bound to the real connection state and only pulses while connected.
- **Cluster health showed a green 100% when there was nothing to measure**
  (`total > 0 ? healthy * 100.0 / total : 100`). An empty or unreachable cluster now shows "n/a" and
  an unfilled ring.
- **Task progress was fabricated.** `TaskExecutionService` animated 0→100% over a fixed 3 seconds
  *before* issuing the RPC. Progress now reflects the real submit-and-resolve lifecycle, and a task
  the control plane could not place is reported as FAILED rather than COMPLETED.
- **"✓ Connected" was printed without connecting.** `NodeJoinView` decoded the Server ID (pure string
  arithmetic), slept 1200ms "for visual feedback", and declared success — any syntactically valid ID
  appeared to connect. It now opens a real TCP connection to the control plane's port first.
- **"Launch Server" never started a server.** `DesktopApp` reached `ControlPlaneServer` via
  `Class.forName` while `desktop-ui` had no dependency on it, so the call always threw
  `ClassNotFoundException`, logged a warning, and showed the dashboard anyway. The module dependency
  now exists, startup is awaited by polling channel readiness, and failure is surfaced in the UI.
- **`isControlPlaneConnected()` reported true for a channel that had never reached a server**, since
  it only checked `!isShutdown()`. It now inspects the gRPC `ConnectivityState`.
- **`connectTo()` did not reconnect** — it stored the new host and port and left the caller to find
  out later. It now rebuilds the channels immediately.
- **The light theme was unusable.** Views hardcoded dark hex values through inline `setStyle`, which
  wins over stylesheet rules in JavaFX, leaving white-on-white text across cards and panels in light
  mode. Structure now lives in a colour-free `base.css` written against tokens that `dark.css` and
  `light.css` each define; `ThemeTokenParityTest` fails the build if the two token sets diverge.
- **Gauge tweens fought each other.** `NodeCard.animateGauge` started a new 21-keyframe `Timeline`
  every 2s per node per metric without stopping the previous one, and wrapped an already-FX-thread
  callback in a redundant `Platform.runLater`.
- **A second, divergent copy of the agent's metric sampling** lived in `DesktopApp.startHeartbeats()`
  — including the same clamp-unavailable-CPU-to-zero bug, and SLF4J calls using `{:.1f}` placeholders
  that SLF4J does not support. It now delegates to the shared `SystemMetricsReader`.

### Added
- A persistent **connection banner** on every screen (`ConnectionBanner` +
  `ConnectionStateManager`), reporting connected / reconnecting / disconnected, and an "Updated Ns
  ago" counter that visibly ages so a frozen feed cannot pass for a live one. A single failure shows
  *reconnecting*; three consecutive failures escalate to *disconnected* and label on-screen data as
  stale.
- Grouped **Settings**: Connection (host, ports, live status), Monitoring (refresh interval),
  Appearance (theme), Certificates.
- `rpc DeregisterNode` with drain support; a drained node keeps its record and telemetry but stops
  receiving work.
- `NodeStatus` enum on the wire (`NodeInfo.status`). Previously `NodeInfo` had no status field at all,
  so every gRPC consumer had to assume everything it received was healthy.
- `NodeCapabilities` (cores, memory, disk, OS, arch, JVM) and `CertificateInfo` reported at
  registration; heartbeat round-trip fields (`client_send_epoch_millis`, `server_receive/send`,
  `sequence`, `agent_uptime_millis`).
- `EnvConfig` — all ports, hosts, intervals and timeouts are now environment-configurable. See the
  Configuration table in the README.
- Metrics: `controlplane_reregistrations_total`, `controlplane_unknown_heartbeats_total`,
  `controlplane_tasks_assigned_total{node_id}`, `controlplane_tasks_unplaceable_total`,
  `controlplane_heartbeat_processing_seconds`, `controlplane_heartbeat_interarrival_seconds`,
  `controlplane_node_status_transitions_total{from,to}`, `node_reconnects_total`,
  `node_reregistrations_total`, `node_cpu_reading_unavailable_total`,
  `node_memory_reading_unavailable_total`, `node_heartbeat_failures_total`.
- Predictor calls now carry a 500ms deadline so a slow predictor cannot stall task placement, and are
  skipped entirely when the node's telemetry is stale.

### Changed
- **JaCoCo gate raised from 10% to 60% instruction coverage, and now enforced in both modules.**
  Generated protobuf classes are excluded from the denominator; the old bundle-wide 10% included
  them, so machine-written code dominated the measurement and the number meant nothing. Achieved:
  `java-control-plane` 63.9% instruction / 52.5% branch, `desktop-ui` 61.0% / 45.3%, across 379 tests
  (up from 132).
- Node agent's Prometheus exporter defaults to `9091` instead of `9090`, which collided with the
  control plane's exporter when both roles ran on one host.
- Removed `java-control-plane/src/main/proto/node_agent.proto`: it was never compiled (both modules
  build from `proto/control_plane.proto`) and nothing referenced any type it declared.
- `desktop-ui` now depends on `control-plane` and no longer regenerates the proto itself. The two
  modules were compiling the same `.proto` independently, which would have put two copies of every
  generated class on one classpath once the dependency existed.
- The control plane's shaded jar is attached under the `all` classifier rather than replacing the
  main artifact, so the jar `desktop-ui` consumes is a thin library rather than a fat jar containing
  a second copy of gRPC, protobuf and Hibernate. The runnable artifact is now
  `control-plane-<version>-all.jar` (the Dockerfile was updated to match).
- JaCoCo added to `desktop-ui`, which previously had no coverage measurement at all. JavaFX view
  classes are excluded — they need a running toolkit and a display, and counting them would measure
  the wrong thing.
- JaCoCo upgraded 0.8.12 → 0.8.15. 0.8.12 predates JDK 26 and cannot read class file major version
  70; its agent threw on every JDK and third-party class it tried to instrument, flooding the build
  log and intermittently failing test-class loading outright with "Unable to create test class".
  The upgrade removes all of it (hundreds of errors → zero).
- Desktop UI rebuilt around a design system. Chart series use a colour order validated against this
  application's own surfaces for lightness band, chroma, colour-blind separation and contrast
  (`Palette`); a node's colour follows the node, so a changing node list never repaints the
  survivors. Node cards use labelled bars against a common baseline rather than circular gauges,
  which cannot be compared across cards. Screens are built from shared `StatTile` / `TimeSeriesChart`
  components and styled entirely through tokens.
- `dashboard/nginx.conf` proxied `/api/` to `control-plane:8080`, but the server binds `8085` — so
  every API call from the containerised dashboard hit a closed port and the page permanently read
  "Disconnected". The Dockerfile's `EXPOSE` was wrong in the same way, and compose never published
  the port at all.
- The compose healthcheck shelled out to `bash` for a `/dev/tcp` probe inside a JRE-only image that
  has no bash, so it could never pass. It now uses the metrics endpoint.
- The CA now lives on a named volume. Without one, restarting the control plane mints a new CA and
  every previously-enrolled node is instantly untrusted.

### Planned
- WebSocket support for dashboard
- Alert system for node failures
- Multi-region deployment support
- Automated node recovery
- Task queue persistence

## [3.0.0] - 2026-08-12

### Added - Real Raft consensus: the control plane can now run as a fault-tolerant 3-replica cluster

A hand-rolled Raft implementation (`com.nextgen.controlplane.raft`) — leader election with randomized
timeouts, log replication with the mandatory Figure-8 commit-safety check, a durable write-ahead log,
and a no-op entry on election per §5.4.2 — replicates node registration and the full task/job
lifecycle across replicas. This is not a demo: `RaftSafetyInvariantTest` runs a seeded randomized
fault-injection loop asserting single-leader-per-term, the log-matching property, and identical
applied state across replicas at every shared index; `ReplicatedControlPlaneIntegrationTest` proves an
actual killed leader's in-flight task survives, replicates to the new leader, and completes correctly
once the fake node reconnects.

- New package `com.nextgen.controlplane.raft`: `RaftNode` (the algorithm), `RaftLog` (durable WAL —
  `state.meta` + `log.wal`, torn-write recovery via CRC32), `RaftStateMachine` (deterministic apply
  over `NodeRegistry`/`TaskRegistry`/`JobRegistry`), `GrpcRaftTransport`/`RaftConsensusServiceImpl`
  (real gRPC peer transport on `RAFT_PORT`, a separate port from the client-facing one and outside
  `MtlsPolicyInterceptor`), `RaftLeaderRedirectInterceptor` (`UNAVAILABLE` + a leader-hint trailer for
  a non-leader call), `ApplyClock` (every replica stamps the leader's proposal time, not its own wall
  clock, while applying — otherwise "identical state machines" would be false from the first command).
- `ControlPlaneWriter` seam (`DirectControlPlaneWriter` / `RaftControlPlaneWriter`): every registry
  mutation now goes through one interface, so `RAFT_ENABLED=false` (the default) is provably a
  zero-behavior-change path — every pre-existing test passes completely unchanged.
- Client/agent redirect-following: `ControlPlaneEndpoints` (shared by `desktop-ui` and `NodeAgent`),
  redirect-following in `GrpcConnectionManager`/`ControlPlaneClient` within one total 4s call budget,
  and a new `ControlPlaneConnection` shared by `NodeAgent`'s registration/heartbeat/task-channel paths
  — a redirect hint is followed immediately, never burning a backoff delay slot the way a genuine
  transport failure does.
- Fixed a real bug this stage's own headline test would otherwise have caught: `TaskChannelClient`
  captured its outbound stream once at dispatch time and reused it after the task finished executing.
  A rare edge case before Raft; under Raft failover it meant every leader change mid-task lost that
  task's result. It now reads the current stream fresh at send time instead.
- PKI issuance (`NodeEnrollmentServiceImpl`) is gated to the current leader via the same redirect
  interceptor, since `CertificateAuthority.nextSerial()` is a JVM-local lock that three replicas
  sharing a PKI volume would otherwise race on. A new `ROLE=pki-init` one-shot mode bootstraps the
  CA/server certificate once, before any replica starts, closing that same race at a different layer.
- `HeartbeatMonitor`/`RiskMonitor`/the dashboard API are leader-gated; a freshly-elected leader waits
  out a grace period before its first heartbeat sweep (its inherited `NodeRecord`s have frozen
  timestamps a follower never advanced); a new `QueuedTaskReconciler` redispatches (or honestly fails)
  any task orphaned `QUEUED` by a leader dying between accept and dispatch.
- Opt-in 3-replica topology: `docker-compose.raft.yml` (a separate file — `docker compose up` on the
  plain `docker-compose.yml` still starts exactly one control plane, unchanged) plus
  `deploy/prometheus.raft.yml`.
- Named scope cut, stated plainly rather than silently left inconsistent: the enrollment TOKEN store
  itself is not Raft-replicated — it stays leader-local, in-memory; a token minted by a since-superseded
  leader is not honoured by a new one, so `ControlPlaneServer` re-mints configured tokens on every
  become-leader edge rather than stranding an unenrolled node indefinitely. Full token replication
  (mint/consume as a linearizable Raft command) is real, named future work.

### Added - Real XGBoost failure-risk classifier

`train_risk_model.py --model-type xgboost` (now the default) replaces the previous hand-rolled NumPy
logistic regression with real gradient-boosted trees (the raw `xgboost.Booster` API — no scikit-learn
dependency added). `features.py` expanded from 8 to 14 features (rolling mean/max, an RTT trend slope,
staleness). Model persistence gained a `modelType` field, so an existing logistic-regression model file
on disk keeps working unchanged. Still opt-in via `ML_RISK_SCORER_ENABLED=true`, and still honestly
reports `model_trained=false` until an operator actually runs training against real accumulated data.

### Added - Real LSTM load forecasting

New `load_forecast_model.py` / `train_load_forecast_model.py` / `load_forecast_store.py`: a
`torch.nn.LSTM` forecasting 5-minute-horizon CPU/memory from the raw telemetry sequence
`RiskSnapshotLogger` already collects (opt-in, `RISK_SNAPSHOT_LOGGING_ENABLED=true`) — a genuinely
different capability from the XGBoost classifier above, which only ever sees collapsed trend scalars.
`MLRiskScorer` folds a crossed-threshold forecast into its risk score as one more bounded, composable
signal, never dominating the classifier's own assessment.

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
