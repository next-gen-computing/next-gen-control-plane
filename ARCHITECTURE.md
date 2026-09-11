# Next-Gen Control Plane — Architecture

This describes what the system actually does when you run it. Where a component exists in the source
tree but is not started at runtime, that is stated explicitly rather than described as though it
were live.

## Runtime shape

`com.nextgen.Main` reads `ROLE` and starts exactly one of two things:

| `ROLE` | Starts | Listens on |
|---|---|---|
| `server` (default) | `ControlPlaneServer` | gRPC `50051`, metrics `9090`, dashboard/API `8085` |
| `agent` | `NodeAgent` | metrics `9091` only — it never accepts connections |

```
                        ┌──────────────────────────────┐
   Desktop app,         │                              │
   Server mode ────────▶│      CONTROL PLANE           │
   (or ROLE=server,     │                              │
    headless)           │  NodeRegistry                │──────▶ Predictor
                        │  RoundRobinScheduler         │        (Python, :50052)
   Prometheus ─────────▶│  HeartbeatMonitor (6s)       │
   (scrape :9090)       │  CertificateAuthority        │
                        │  NodeEnrollment              │
                        └───────────▲──────────────────┘
                                    │ gRPC 50051 — nodes dial OUT, never in
                    ┌───────────────┼───────────────────────┐
                    │               │                       │
              ┌─────┴────┐    ┌─────┴────┐            ┌─────┴────┐
              │ Desktop  │    │ Desktop  │            │ Desktop  │
              │ app,     │    │ app,     │    ...     │ app,     │
              │ Node mode│    │ Node mode│            │ Node mode│
              │ Windows  │    │ macOS    │            │ Linux    │
              │ (no      │    │ (no      │            │ (no      │
              │ inbound  │    │ inbound  │            │ inbound  │
              │ port)    │    │ port)    │            │ port)    │
              └──────────┘    └──────────┘            └──────────┘
```

Both roles are the **same desktop application**; which side of this diagram you're on is a choice made
at launch, not a different build. A node can be any machine on any network, running any of the three
desktop platforms — nothing about the protocol or the registry cares where a node physically is.

The control plane still serves a small `/api/nodes` JSON endpoint on the dashboard port for anyone
who wants to build their own frontend against it, but the bundled static web dashboard that used to
ship in this repo has been removed entirely — the desktop app is the one maintained interface for
both roles.

---

## WAN connectivity model

### Who dials whom

**Nodes always dial out. The control plane never dials a node.** That single rule is what makes the
system work across the open internet without asking anything of the node's network.

A node behind home or office NAT, or on a laptop moving between networks, opens an outbound TCP
connection to the control plane. Outbound connections are what NAT is built to handle: the router
creates a mapping automatically and returns traffic flows back through it. Nothing needs to be
configured on the node's side — no port forwarding, no static address, no firewall exception, no
inbound rule of any kind. The node can be on a network you do not administer and do not know the
shape of.

Only the **control plane** needs a stable, reachable address. That can be a cloud VM, or a home
server with a single port forwarded. Exactly one port has to be reachable: the gRPC port, `50051` by
default and settable with `GRPC_PORT`.

Nodes find it through `CONTROL_PLANE_HOST` / `CONTROL_PLANE_PORT`. Any hostname, public DNS name or
IP works; nothing in the code assumes a LAN address or a private range.

### Detecting a dead connection

A TCP connection that dies because a NAT mapping expired, a link dropped, or a laptop went to sleep
does not notify either end. Without help, the node keeps writing into a socket that no longer goes
anywhere and believes it is healthy — while the control plane, hearing nothing, has already marked
it `SUSPECTED_DEAD`.

gRPC keepalive closes that gap. Both ends are configured, and **they have to be configured as a
pair**: a client that pings more often than the server's `permitKeepAliveTime` allows is answered
with `GOAWAY` / `ENHANCE_YOUR_CALM` and has its connection torn down — so aggressive client tuning
alone actively makes things worse.

| Setting | Default | Applies to |
|---|---|---|
| `GRPC_KEEPALIVE_TIME_MS` | 30000 | both — how often to ping an idle connection |
| `GRPC_KEEPALIVE_TIMEOUT_MS` | 10000 | both — how long to wait for the ping reply |
| `GRPC_PERMIT_KEEPALIVE_TIME_MS` | 20000 | server — the floor it will tolerate from clients |
| `GRPC_MAX_CONNECTION_IDLE_MS` | 300000 | server |

`keepAliveWithoutCalls` is enabled on the agent because agents are idle between heartbeats; without
it, keepalive would never fire in precisely the situation it exists for.

### Recovering

Every agent-side failure goes through `BackoffPolicy`: capped exponential backoff starting at 1s,
doubling to a 30s ceiling, with 20% jitter. Jitter matters at fleet scale — without it, every agent
that lost its connection at the same instant retries at the same instant and stampedes a recovering
control plane.

Retries are **unlimited**. An agent whose control plane is down should rejoin when it returns, not
exit and wait for a human. There is no failure mode that requires a manual restart.

Two specific recoveries are handled:

- **Transient network loss** — the heartbeat fails, backoff grows, the next successful heartbeat
  resets it and increments `node_reconnects_total`.
- **Control-plane restart** — the registry is empty, so the node is unknown. The server answers with
  `reregistration_required`, and the agent re-registers rather than heartbeating into a registry that
  will never accept it. (Previously this returned `UNKNOWN_NODE` forever and the agent logged it at
  INFO alongside the normal case, so the node was gone until someone noticed.)

### Measuring it, rather than asserting it

Connection quality is reported as numbers:

| Metric | Where | What it is |
|---|---|---|
| `node_heartbeat_rtt_seconds` | agent | Histogram of round-trip time, measured with `System.nanoTime()` |
| `node_reconnects_total` | agent | Sessions re-established after failure |
| `node_clock_skew_seconds` | agent | NTP-style offset estimate against the control plane |
| `controlplane_heartbeat_interarrival_seconds` | server | Observed gap between a node's heartbeats |
| `controlplane_heartbeat_processing_seconds` | server | Server-side processing time |

RTT is a **histogram, not a gauge**: a gauge of the most recent value hides the tail, and the tail is
the only interesting part of a latency distribution.

> **What is deliberately not measured.** Absolute *one-way* latency between two machines with
> unsynchronised clocks is not measurable. Subtracting the client's send timestamp from the server's
> receive timestamp and presenting the result as "network latency" would be a fabricated number
> dressed as a measurement — the same class of error as reporting an unreadable CPU as 0.0%. Only
> agent-local RTT and the skew estimate are real.

---

## mTLS trust model

### The chain

```
   token (once, out of band)          certificate (ongoing)
   ─────────────────────────▶  CA  ─────────────────────────▶  mutual TLS
```

1. **Bootstrap — a token.** An operator mints a single-use enrolment token for a node id
   (`ENROLLMENT_TOKENS=node1,node2`). It is 256 bits from `SecureRandom`. Only its SHA-256 is stored;
   the plaintext exists once, in the startup log, and is never persisted.
2. **Enrolment — a CSR.** The node generates its **own** EC P-256 key pair and sends only a PKCS#10
   certificate signing request. *The private key never leaves the node.* The token travels in the
   gRPC metadata header `x-nextgen-enrollment-token`, deliberately not in the request message — a
   bearer secret in a proto field eventually gets printed by a `toString()` in a debug log.
3. **Issuance.** The control plane verifies proof-of-possession (the CSR must be self-signed by the
   key it advertises), consumes the token atomically, and issues a 30-day client certificate.
   **The CSR's subject is discarded**; the common name is the node id the *token* was bound to.
   Trusting a client-supplied subject is the impersonation hole — a node that asks to be called
   `admin` gets a certificate for whatever identity its token was issued against.
4. **Operation.** The node rebuilds its channel with the issued key material and every subsequent RPC
   runs over mutual TLS.

### Why enrolment is reachable without a certificate

The transport uses `ClientAuth.OPTIONAL` and a `ServerInterceptor` enforces policy per method:
`NodeEnrollment/Enroll` is reachable anonymously, everything else demands a valid, unexpired,
unrevoked client certificate.

A second port for enrolment was considered and rejected. The interceptor is needed regardless —
without it, node A could send `HeartbeatRequest{node_id: "node-B"}` over its own perfectly valid
connection and poison node B's state. Authentication is not authorisation. A second socket would add
another lifecycle and another firewall rule and buy nothing. Setting `ENROLLMENT_ENABLED=false` after
provisioning gives the same "close the door" property.

Trust-on-first-use was also rejected: it moves the trust decision to exactly the moment an active
man-in-the-middle can steal the enrolment token. The CA certificate is public data — shipping it out
of band (`NEXTGEN_CA_CERT`) costs a 1 KB file and is strictly safer.

### The constraint that bites

**TLS client authentication happens once per connection, during the handshake.** gRPC/Netty exposes
no TLS 1.3 post-handshake client auth, so an enrolment channel **cannot be upgraded in place**. After
`Enroll` returns, the agent must shut the enrolment channel down and build a new one carrying the
issued certificate. Skipping that step fails as a confusing `UNAUTHENTICATED` on the first heartbeat
rather than as an obvious error.

### Revocation

Revocation is a **serial-number denylist consulted on every RPC**, not a CRL.

A CRL is checked at handshake time. Agents hold long-lived channels, so revoking a compromised
node's certificate would do nothing until it happened to reconnect — which might be never. A per-RPC
check kills the *next RPC* on an already-open connection. `revocationTakesEffectOnAnOpenConnection`
in the test suite is the proof.

Re-enrolling a node automatically revokes its previous serial, preserving "at most one live
certificate per node" and bounding growth of the ledger.

### Key material on disk

`${NEXTGEN_PKI_DIR}` (default `~/.nextgen/pki`): `ca.crt`, `ca.key`, `server.crt`, `server.key`,
`serial.txt`, `index.txt`, `revoked.txt`. Never logged, never committed, gitignored.

Permissions are restricted to the owner on a best-effort basis: POSIX permissions where available
(created atomically with the right mode, since create-then-chmod leaves a window where the private
key is world-readable), an owner-only ACL on Windows, and a warning plus
`controlplane_pki_permissions_enforced=0` where the filesystem can express neither. Refusing to start
in that case would make development on some filesystems impossible; making the degradation
observable is the better trade.

**The CA must be on a volume.** Without one, restarting the control plane mints a brand-new CA and
every previously-enrolled node is instantly untrusted.

### Rolling it out

`TLS_ENABLED` defaults to `false`. When off, the interceptors still run but in **permissive audit
mode**: they count what they would have rejected in `controlplane_mtls_would_deny_total{reason}` and
allow it through. That makes it possible to see whether a running cluster is ready for enforcement
before turning it on.

> **TLS provider.** Defaults to the JDK stack. The BoringSSL native bundled with `grpc-netty-shaded`
> loads fine but aborts the handshake with `TLSV1_ALERT_INTERNAL_ERROR` under this server
> configuration (`ClientAuth.OPTIONAL` + EC P-256 + a CA trust manager); the identical setup works on
> the JDK provider, which has had ALPN — the only thing gRPC needs — since Java 9. Set
> `TLS_SSL_PROVIDER=OPENSSL` to opt in if you have verified it on your platform.

### Renewal

Two mechanisms cover renewal, at two different points in a node's life:

`NodeAgent.ensureEnrolled` runs once, at process startup: it loads any stored certificate, and if it's
within `CERT_RENEW_WINDOW_MINUTES` of expiry (default 7 days), re-enrols before the heartbeat loop
starts. This path re-enrols through the same token-based `Enroll` RPC as first-time enrolment — an
operator restarting an agent whose certificate has already lapsed needs a fresh token available at
restart time, same as first enrolment.

`NodeAgent.CertificateRenewalLoop` runs continuously alongside the heartbeat loop (same
self-rescheduling shape, see `HeartbeatLoop`'s own Javadoc for why `schedule()` rather than
`scheduleAtFixedRate()`), checking every `CERT_RENEWAL_CHECK_INTERVAL_MS` (default hourly) whether the
certificate is now within the renewal window — catching the case `ensureEnrolled` structurally cannot:
a node that was fine at startup but has since been running long enough for its certificate to approach
expiry. This path uses `NodeEnrollment.RenewCertificate` — the mTLS-authenticated, no-token-needed
renewal RPC the proto defines — over the same operational `ControlPlaneConnection` the heartbeat loop
and task channel already share, since renewal is authenticated by the certificate being replaced, not
by a token.

Two correctness details worth naming explicitly, since both were real bugs caught by
`CertificateRenewalLoopTest` before shipping, not designed in from the start:
- **Statement order inside `renewOnce()` matters.** The stub is obtained from `ControlPlaneConnection`
  *before* `AgentCredentials.createCsr()` is called, not after. `createCsr()` immediately overwrites
  the in-memory key pair; if the channel were built (or reused, if not already warm) *after* that call,
  its TLS identity would pair the OLD certificate with the NEW key — a mismatch that fails the
  handshake outright, not a subtle bug. In real `NodeAgent.start()` usage this was structurally masked
  (`registerWithBackoff` always warms the connection's channel first, with a still-consistent pair)
  right up until the loop's *first* tick on a cold connection, which is exactly the scenario the test
  exercises directly.
- **The connection's cached channel must be invalidated after a successful renewal**
  (`ControlPlaneConnection.invalidateCurrentChannel()`). Issuing a new certificate revokes the old one
  server-side (`CertificateAuthority.issueClientCertificate`'s existing behavior — see "Revocation"
  below), but TLS client auth happens once per connection at the handshake; an already-open channel
  keeps presenting the now-revoked certificate until it's torn down and rebuilt. Without discarding it,
  every RPC after a renewal on that connection — heartbeats included — would start failing as
  `certificate_revoked`.

---

## Consensus & replication

**Off by default.** Everything in this section only exists when `RAFT_ENABLED=true`. A single
control-plane process behaves exactly as described everywhere else in this document — this section
describes the opt-in path where three replicas run instead of one, coordinated by a hand-rolled Raft
implementation (`com.nextgen.controlplane.raft`), so a killed replica no longer takes the cluster with
it.

### The one rule

A Raft command records a decision that has already been made; it never records the inputs to a
decision. Everything that reads leader-local state — heartbeat freshness, `aliveSnapshot()`, the
task-channel registry, the round-robin cursor, capacity/risk scores — is resolved on the leader
**before** proposing. The replicated command carries only the outcome. `RaftStateMachine.apply()` is
then a pure, deterministic replay over the same `NodeRegistry`/`TaskRegistry`/`JobRegistry` a
single-replica deployment uses unmodified — those three classes have no idea Raft exists.

### What's replicated vs. what stays leader-local

| Replicated | Leader-local only |
|---|---|
| Node `register`/`deregister` | `recordHeartbeat` (self-heals within one ~2s interval) |
| Every `TaskRegistry`/`JobRegistry` mutation (create, dispatch, mark running/completed/failed/migrating, retry, complete job) | `sweepExpired`, `updateRisk` (both leader-computed, then their *conclusions* would be what a replicated command carries — the sweep itself never runs on a follower) |
| — | `NodeHistory`, the `RoundRobinScheduler` cursor (no correctness benefit — the placement decision is already in the replicated dispatch command) |
| — | `NodeTaskChannelRegistry` — inherently a live socket handle, not data; a node's open stream exists on exactly one replica at a time |

Node registration is the one genuinely non-self-healing membership fact: a lost deregistration
tombstone would let a still-certificate-holding node silently resurrect itself. Heartbeats are the
opposite case — replaying a heartbeat's *output* on a follower without its time-dependent *input*
would just distribute a stale conclusion, so `HeartbeatMonitor`/`RiskMonitor` are leader-gated instead
(see below) rather than replicated at all.

### Durable log, chosen for debuggability over density

Two files under `${RAFT_DIR}/${RAFT_NODE_ID}/`, matching this project's own preference for
plain-text-inspectable state (the JSONL training logs are the same idea):

- **`state.meta`** — `currentTerm`/`votedFor`, rewritten atomically. Both are **fsynced before any RPC
  leaves the process** — a node that grants a vote, crashes, and restarts must never grant a second,
  conflicting vote in the same term (Raft's election safety property). This is exactly what
  `RaftDurabilityTest` exists to catch.
- **`log.wal`** — append-only, tab-separated `index / term / base64(command) / CRC32`, one entry per
  line. Recovery on open replays the file and **truncates at the first line that fails its CRC, fails
  to parse, or breaks index continuity** — a crash mid-write leaves a partial final line, and this
  discards it rather than trusting it.

`commitIndex`/`lastApplied` are deliberately **not** persisted: the state machine is entirely
in-memory and always replays from index 1 on restart, which is also why this log never compacts or
snapshots — free to skip, since it costs nothing in correctness, only eventual disk growth and restart
time. Named future work: a Prometheus gauge exposing the log's current entry count so that growth is
observable rather than silently invisible — `RaftLog`'s own Javadoc already names this, but it is not
wired up yet.

The single most-often-omitted correctness rule in a hand-rolled Raft implementation is committing an
entry from counting a majority alone, without also requiring `log.termAt(N) == currentTerm` (the Raft
paper's Figure 8 scenario) — omitting it causes *silent* data loss, the worst failure mode for a
control plane. `RaftNode.maybeAdvanceCommitIndex` enforces it; a no-op entry appended immediately on
becoming leader (§5.4.2) is what lets a new leader's `commitIndex` advance past the previous term's
tail promptly instead of stalling until the next real write.

### Leader redirect

A non-leader replica answers a gated RPC with `Status.UNAVAILABLE` (gRPC's standard "try elsewhere"
signal, not `FAILED_PRECONDITION`) carrying two trailers: `x-nextgen-raft-leader`
(`id=host:port`) and `x-nextgen-raft-term`. **Every RPC is gated, including reads** — a follower's
`NodeRegistry` receives zero heartbeats, so a follower answering `GetNodes` would render every node
falsely stale, the same honesty rule [Data integrity](#data-integrity) states for OS metrics applied
to replication freshness instead. The one exception is `GetClusterStatus`, answerable by any replica —
the extension point for follower-served stale reads later, not built yet.

`RaftLeaderRedirectInterceptor` also gates `NodeEnrollmentServiceImpl` (`Enroll`/`RenewCertificate`):
`CertificateAuthority.nextSerial()` is a JVM-local `synchronized` lock, and three replicas sharing one
PKI volume would race on `serial.txt`/`index.txt` without routing every issuance through one replica
at a time. A `ROLE=pki-init` one-shot mode (see `docker-compose.raft.yml`) bootstraps the CA and server
certificate once, before any replica starts, closing the *other* race this same shared volume would
otherwise create — three JVMs independently racing `CertificateAuthority`'s own check-then-create
bootstrap against the same files on first boot.

A client (`desktop-ui`, `NodeAgent`) follows a redirect hint immediately and without penalty:
`ControlPlaneEndpoints.onLeaderHint` moves the next attempt to the hinted address, and
`ControlPlaneClient`/`ControlPlaneConnection` track this as a successful discovery, never a failure —
only a genuine transport error consumes a retry/backoff slot. A hint is always provisional: if dialling
it fails outright, the client falls back to rotating its originally configured candidate list.

### Leader-gated collaborators

`HeartbeatMonitor`, `RiskMonitor`/`ProactiveMigrator`, `mintConfiguredTokens`, and the dashboard API
all check `RaftNode.isLeader()` (via a `BooleanSupplier`/direct check threaded through
`ControlPlaneServer.start()`) before doing anything. A freshly-promoted leader also observes a
**grace period**: its inherited `NodeRecord`s have frozen `lastHeartbeatMillis` from whenever the
previous leader last swept (a follower never advances them), so its first sweep would instantly mark
every node dead without a window for real heartbeats to arrive first.

On becoming leader, `QueuedTaskReconciler` also scans for any task stuck `QUEUED` — the one gap that
accept-then-dispatch being two separate replicated commands leaves: a leader can commit `SubmitTask`
and then die before proposing the follow-up `DispatchTask`. It redispatches what it can and honestly
fails what it can't (no alive node), rather than leaving the task orphaned forever.

### Scope cuts, named rather than silently left inconsistent

- **The enrollment TOKEN store *is* Raft-replicated** (`RaftEnrollmentTokenReplicator`, implementing
  `TokenReplicationSink`) — a token minted by a leader that's then killed before the token is used is
  still recognized by the newly-elected leader. Minting proposes `MintEnrollmentTokenCommand` and
  blocks until it commits and applies, so the token's hash is durable *before* its plaintext is ever
  handed to an operator; consumption proposes `ConsumeEnrollmentTokenCommand` fire-and-forget,
  immediately after the already-atomic local accept/reject decision, since replicating that decision is
  a best-effort close of the reuse-after-failover window rather than something the caller needs to wait
  on. `ControlPlaneServer` still re-mints configured tokens (`ENROLLMENT_TOKENS`) on every become-leader
  edge as a defense-in-depth fallback for any token that predates this replication path.
- **Static membership.** `RAFT_PEERS` is a fixed, comma-separated `id=host:port` list read once at
  startup. Adding or removing a replica means restarting the cluster with a new list — no joint
  consensus / dynamic reconfiguration.
- **No pre-vote, no `AppendEntries` batching.** Standard production-Raft optimizations (avoiding a
  disruptive election from a partitioned-then-rejoining node; multiple entries per RPC round trip).
  The correctness-critical rules above (commit-index term check, no-op-on-election, leader step-down on
  lost quorum contact) matter far more for a first cut and are not skipped; these are.
- **No follower-served reads beyond `GetClusterStatus`, no full `ReadIndex` confirmation.** Every other
  RPC routes through the leader; a leader that can't reach a majority within one election timeout steps
  down rather than serving arbitrarily stale reads forever, which bounds staleness cheaply without the
  machinery a real `ReadIndex` implementation would need.
- **Shared PKI volume ties all three replicas to one filesystem.** Correctness-safe (issuance is
  leader-gated) but not independent-host CA material — distributing serial allocation and the issuance
  ledger themselves across genuinely separate hosts is later work.

### Testing strategy

Three layers, deliberately: an in-memory-transport harness (`RaftTestCluster`, injectable
drops/delays, a seeded `Random` for reproducible split-votes) proves the algorithm itself —
`RaftSafetyInvariantTest`'s randomized fault-injection loop is the single highest-value test in the
whole stage, asserting at-most-one-leader-per-term, log matching, and identical applied state hold
after every injected partition/crash/restart. A small real-gRPC test (`RaftConsensusServiceImplTest`)
proves only the proto/wire plumbing, since the in-memory harness can't catch a serialization mistake.
`ReplicatedControlPlaneIntegrationTest` is the end-to-end proof: three real
`ControlPlaneServiceImpl`+`RaftNode` instances, a real task dispatched and replicated, the leader
killed mid-task, and the surviving replicas' new leader still completing that task correctly once the
node reconnects — the actual "Follower Crash and Workload Migration" scenario, reproducible on a
reader's own machine via `mvn test -Dtest=ReplicatedControlPlaneIntegrationTest`.

---

## Distributed container execution

Turns the platform into a real distributed `docker compose`-style execution engine — a whole
multi-service project spread across whichever nodes are currently idle, not just single computational
tasks. Everything here is additive and opt-in (a new `TaskKind` value, new gated services, new env
flags); nothing about the existing task/job pipeline changes.

**The constraint every design decision here routes around**: this project's hub-and-spoke rule (nodes
only ever dial OUT to the control plane; they never accept an inbound connection, from the control
plane or from each other) is treated as non-negotiable, exactly as it is everywhere else in this
document. Two genuinely hard requirements — a remote node building an image from source, and two
services on different nodes reaching each other — both had to be solved without weakening that rule, so
both route through the control plane as a relay/rendezvous point instead of any direct node-to-node or
CLI-to-node path.

### Build-context shipping

A `build:` context lives on the operator's machine, where the `nx` CLI runs — never reachable directly
by a node. The path is always **operator → control plane → node**, over connections that already exist:

1. The CLI tars the local build context and streams it up via the `UploadBuildContext` client-streaming
   RPC. `BuildContextStore` (`com.nextgen.controlplane.task`) assembles the chunks, hashes what it
   actually wrote (SHA-256), and stages the tarball on local disk under
   `${NEXTGEN_DATA_DIR}/build-contexts/`, evicted by TTL (`BUILD_CONTEXT_TTL_MINUTES`, default 24h) — a
   multi-hundred-MB blob has no business going through the Raft write-ahead log, so this is deliberately
   NOT replicated; a leader failover mid-upload means the operator re-uploads.
2. The CLI compares the response's SHA-256 against what it computed locally before uploading — a
   mismatch is caught immediately, not at build time on some node three hops later.
3. When `TaskDispatcher` is about to push a `TaskDispatch` referencing a `build.context_id`, it first
   streams the stored tarball down that node's already-open `TaskChannel` as a sequence of
   `ServerTaskCommand{build_context_chunk}` messages, then sends the `TaskDispatch` itself. Node-side,
   `TaskChannelClient` buffers the chunks (`NodeBuildContextStore`) keyed by `context_id`; ordering over
   the one stream guarantees the context is fully buffered before the referencing dispatch is even
   handed to the executor.
4. `DockerComposeServiceExecutor` re-verifies the SHA-256 against the dispatch's own payload JSON before
   unpacking (shelling out to `tar`, matching the CLI-not-SDK precedent — see below) and running
   `docker build`. A hash mismatch fails the task honestly (`"FAILED — build context integrity check
   failed"`), never a silent build of possibly-corrupt bytes.

### Cross-node service networking — what it is and is not

Services on different nodes reach each other through **environment-variable injection relayed by the
control plane**, never transparent DNS resolution:

- A node whose service other services may need to reach opens `rpc TunnelPort` — a bidirectional
  streaming RPC, once per port needing relay, outbound like every other stream in this system.
  `PortRelayManager` (server-side) allocates a real TCP listener from a configured range
  (`RELAY_PORT_RANGE_START`/`_END`) and bridges each accepted consumer connection to that node's stream,
  multiplexed by `tunnel_id` — one relay stream serves arbitrarily many concurrent consumers.
- `JobCoordinator` resolves, per declared `peers` entry (derived by the CLI from the compose file's
  `depends_on`), which relay port backs the named peer, and injects `<PEER>_HOST`/`<PEER>_PORT` into the
  consuming service's environment before either service is dispatched — this is the two-phase design
  (`PortRelayManager.reservePort` at schedule time, `attachStream` once the provider's real stream
  connects later) that resolves the ordering problem: the consumer needs the port before the provider's
  container has even started. `<PEER>_PORT` is always the externally-reserved relay port, which only
  ever means anything on the control plane's own machine — the provider's OWN spec separately gets a
  `relay_ports` entry naming ITS OWN locally-published port (from its own `ports:` entry), which is what
  `PortTunnelClient` actually bridges to. These are two different numbers by design; a real bug caught
  during a live end-to-end run conflated them, which silently broke every relayed connection (see
  `CHANGELOG.md`'s `[Unreleased]` entry).
- **`RELAY_ADVERTISED_HOST`** (operator-set, default `localhost`) is the address injected as
  `<PEER>_HOST` — the value a consumer's own container actually dials. On genuinely separate physical
  nodes this is just the control plane's normal reachable address (the same one nodes already use via
  `CONTROL_PLANE_HOST`). The one topology-specific gotcha, found while verifying this live: prototyping
  several "nodes" as separate processes sharing ONE Docker Desktop host needs
  `RELAY_ADVERTISED_HOST=host.docker.internal`, not `localhost` — a container's own bridge network
  cannot see `localhost` as meaning its host machine; `host.docker.internal` is Docker Desktop's own
  well-known name for that. This is purely a single-host Docker Desktop artifact, not a limitation of
  the relay design itself.
- **What this deliberately does not provide**: a hard-coded `http://database:5432` will not resolve
  unmodified the way same-daemon Compose networking does — the application must read the injected
  env vars. A per-node local DNS/forwarding daemon that would make it transparent is named, real future
  work, not silently assumed away. Relayed bytes are covered by the same mTLS channel node↔control-plane
  traffic already uses, but there is no additional per-tunnel authentication distinguishing which
  project a relay connection belongs to beyond its `tunnel_id` — an accepted simplification given this
  project's single-trusted-operator scope, named here rather than glossed over.

### Node-side execution and scheduling

`DockerComposeServiceExecutor`/`DockerComposeRunner` (`com.nextgen.agent.task`) shell out to the real
`docker` CLI — no Docker SDK dependency, matching this project's consistent "use the tool the operator
already has installed" precedent (no dependency exists anywhere in this repo that a real CLI could
replace). `DockerCapabilityDetector` gates node eligibility honestly: `docker_available=true` requires
BOTH `docker --version`/`docker compose version` to succeed AND a real daemon round-trip (`docker info`)
— the CLI alone proves nothing about whether the daemon is actually reachable, confirmed empirically on
a machine where the CLI succeeds but the daemon is down.

`JobCoordinator`'s scheduling filter for `TASK_KIND_DOCKER_COMPOSE_SERVICE` is node-level exclusivity,
not a cluster-wide mutex: a node already running one compose service (from any job) is excluded from a
second, concurrently-submitted project's candidate list, but any other currently-idle Docker-capable
node remains fully eligible. Two projects submitted back-to-back land on disjoint node subsets rather
than serializing through one node — the actual mechanism that turns the cluster into a distributed
build/run farm instead of a single-worker queue.

### Cloud / single-machine mode

`LocalDockerExecutionServiceImpl`, a separate gRPC service (`rpc RunCompose`) bolted onto the same
`ROLE=server` process, gated by `LOCAL_DOCKER_EXEC_ENABLED=true` AND a real Docker daemon confirmed
reachable at startup — disabled or unavailable means the RPC is `UNIMPLEMENTED`, never a silent no-op.
Deliberately never wrapped by `RaftLeaderRedirectInterceptor`: it is tied to *this one host's* Docker
daemon specifically, not to "whichever replica is currently leader" — routing through the leader-redirect
interceptor would silently send a caller to a different replica that might not even have Docker
installed. `nx cloud up` talks to it directly, bypassing `RegisterNode`/`TaskChannel`/`NodeRegistry`/
`TaskRegistry`/`JobRegistry`/Raft entirely — reuses the exact same `DockerComposeRunner` the distributed
node-side executor uses (via its general `runCommand` form, running a whole `docker compose up` as one
attached process rather than one container at a time).

### Explicitly out of scope

- Transparent same-name DNS resolution inside containers for cross-node peers (named above).
- Incremental/resumable build-context transfer — every dispatch re-sends the full tarball; a dropped
  upload must be retried from scratch.
- ~~Historical log replay~~ — **shipped.** `JobEventBroadcaster` keeps a bounded per-job history buffer
  (`MAX_HISTORY_PER_JOB = 500` events, oldest trimmed first) and replays it to a new `StreamJobEvents`
  subscriber before live fan-out continues, so `nx logs <job-id>` (without `--follow`) now shows real
  recent history instead of nothing. Deliberately not unbounded persistence or pagination — a fixed
  recent-history window per job, not a message queue.
- Dynamic node provisioning/auto-scaling in cloud mode — `LocalDockerExecution` targets one
  operator-designated, already-running host; it never provisions new cloud VMs.
- Arbitrary compose-file support — only a documented subset (`image`, `build.context`/`dockerfile`,
  `command`, `environment`, `ports`, `depends_on`) is parsed; `volumes:`/`networks:` beyond a node-local
  named volume are out of scope for the same file-distribution reasons that motivate build-context
  shipping in the first place.

---

## Alerting

`AlertNotifier` (`com.nextgen.controlplane.alert`) is the seam both `HeartbeatMonitor` (reactive:
`ALIVE → SUSPECTED_DEAD`) and `RiskMonitor` (predictive: the rising edge of `atRisk`) call into — the
same two detection paths described above, now with a real, external notification instead of only a
dashboard update nobody may be looking at.

Three concrete channels implement it, any combination of which may be configured at once:
`WebhookAlertNotifier` (a generic HTTP POST — Slack/PagerDuty/Discord/a custom receiver all consume the
same JSON), `EmailAlertNotifier` (real SMTP via Angus Mail, the Jakarta Mail reference implementation —
the first email-sending capability anywhere in this project), and `DesktopNotificationAlertNotifier` (a
native OS balloon/toast via `java.awt.SystemTray`, part of the JDK itself — zero new dependency, and
only meaningful when the control-plane process runs on a machine with a display someone is watching,
which is a real, common deployment for this project specifically). `CompositeAlertNotifier` fans a
single event out to whichever channels are configured, isolating each in its own try/catch so a defect
in one can never block a different, working channel — `HeartbeatMonitor`/`RiskMonitor` themselves still
take exactly one `AlertNotifier` and stay unaware more than one channel might be behind it.

Every channel is independently opt-in and best-effort: a slow or unreachable channel (an unreachable
webhook, an unreachable SMTP host, a headless machine with no system tray) is logged and never allowed
to block or fail the detection sweep that triggered it — the same discipline this document applies to
every other cross-cutting concern (a logging failure must not take down detection, a metrics failure
must not take down a request). See README's Configuration reference for the environment variables that
enable each channel.

---

## Data integrity

The rule the whole system is built around: **a value that was not measured is never presented as
though it were.**

- `OperatingSystemMXBean.getCpuLoad()` returns `-1` when no reading is available. That becomes
  `cpu_available=false` on the wire, not `0.0`.
- The control plane retains the last known value and flags it `cpu_stale`, so history is not lost,
  but no consumer presents it as current.
- The dashboard JSON emits `null` plus `cpuStale`; the desktop UI renders `n/a`.
- Stale readings are excluded from cluster averages. A cluster with nothing measurable reports `null`
  / `n/a`, not `0.00`.
- Charts break the line where a node stopped reporting. A line drawn straight across an outage
  asserts the node was fine throughout.
- The desktop UI's cluster-wide task view (`ClusterTasksMonitoringService`, backed by `ListTasks`
  reading `TaskRegistry` directly) is deliberately a separate code path from `TaskExecutionService`'s
  own list, which only ever tracks tasks *this device personally submitted*. Conflating the two would
  either under-report a busy cluster (as if only locally-submitted work existed) or silently claim
  visibility into work this device never actually initiated — the same "don't fabricate scope" rule
  applied to per-container CPU/memory (`docker stats`, merged into the existing container listing with
  the same honest-zero-on-collection-failure discipline as every other field there).

---

## Concurrency

`NodeRecord` is immutable. All registry mutation goes through `ConcurrentHashMap.compute` /
`computeIfPresent`, which hold the per-key bin lock, making read-decide-write atomic per node.

> **Rule for maintainers:** a remapping function passed to `compute` must not log, touch metrics,
> perform I/O, or — above all — perform any other operation on the same map. `ConcurrentHashMap`
> forbids re-entrant access from a remapping function; doing it deadlocks or corrupts the bin. Every
> metric increment and log line in `NodeRegistry` happens *after* `compute` returns.

Task placement rotates over node **identity**, not list position: the cursor holds the id last
selected and the next pick is the first id strictly greater in a sorted snapshot. Position-based
rotation over `ConcurrentHashMap.values()` addressed different nodes over time (iteration order is
unspecified and changes on resize) and starved nodes whenever the candidate count changed.

---

## Removed: the V2 desktop backend and the web dashboard

Two components documented in earlier revisions of this file — `com.nextgen.desktop.v2` (SQLite
persistence via Hibernate, a join-approval workflow, connection tokens, `ClusterManagerServiceImpl`;
never constructed at runtime, `Main` only ever dispatched to `ControlPlaneServer`/`NodeAgent`) and
the standalone web dashboard (`dashboard/`, a static HTML/JS frontend plus nginx config) — have been
deleted outright rather than kept as unused code. Neither had a live consumer: the desktop app is the
one maintained interface for both roles, and nothing in the running system ever depended on either.
See `CHANGELOG.md`'s `[Unreleased]` → `### Removed` entry for exactly what was deleted. `DesktopAppV2`,
referenced by the CHANGELOG's `[2.0.0]` entry, never existed in the repository as a separate class
from what's described here.
