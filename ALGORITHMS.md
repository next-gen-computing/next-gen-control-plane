# Algorithms — Which, How, Why, What

This document is the single, complete technical reference for every algorithm this project actually
runs. Nothing below is aspirational: every entry names the real file it lives in, the real test that
proves it, and — where one exists — a real measured number instead of a claimed one. Where a number
hasn't been formally benchmarked, that's stated plainly instead of estimated.

Each entry follows the same structure:
- **What** — the concrete problem it solves.
- **Where** — the exact class/file.
- **How** — the real mechanism: formula, pseudocode, or step sequence.
- **Why** — the alternative that was rejected and the reason.
- **Status** — on by default, or an explicit opt-in env var.
- **Evidence** — the test(s) that verify it, and any real measured number.

See [README.md](README.md#-algorithms--predictive-intelligence) for the shorter, summary version of
this same material, and [ARCHITECTURE.md](ARCHITECTURE.md) for the surrounding system design (trust
model, connectivity, consensus replication boundaries) that these algorithms run inside of.

---

## 1. Round-robin task placement

**What.** When no other scorer applies, pick which node gets a task's first placement — fairly, without
starving or double-loading any node as the cluster's membership changes underneath it.

**Where.** `RoundRobinScheduler` — `java-control-plane/src/main/java/com/nextgen/controlplane/RoundRobinScheduler.java`.

**How.** The naive approach is `Math.abs(counter.getAndIncrement()) % aliveNodes.size()`. This project
does not use it, for three concrete, previously-real bugs:

1. The candidate list came from `ConcurrentHashMap.values()`, whose iteration order is unspecified and
   changes on resize — so index *i* was not stably the same node call to call.
2. The modulus base changes as nodes die. Counter values `0,1,2,3,4` against candidate-list sizes
   `3,3,2,2,3` produce indices `0,1,0,1,2` — node index 2 is starved while 0 and 1 are double-loaded.
3. `Math.abs(Integer.MIN_VALUE)` is still negative in Java, so after 2³¹ submissions the modulus goes
   negative and `List.get` throws, failing the RPC outright.

The real algorithm instead rotates over **node identity**, not list position:

```
cursor := "" (AtomicReference<String>)

select(candidates: List<NodeRecord> sorted ascending by node id):
    loop:
        previous := cursor.get()
        pick := first node in candidates whose id > previous
                (binary search; wrap to candidates[0] if none is greater)
        if cursor.compareAndSet(previous, pick.id):
            return pick
        # else: another thread advanced the cursor first — retry
```

"Next after X" is meaningful across membership changes: a node joining or leaving neither restarts the
cycle nor skips an entry, and there is no integer counter to overflow. The `compareAndSet` retry loop
(not a plain `set`) is what stops two concurrent `SubmitTask` calls from both reading the same cursor
value and handing the same node two tasks in a row.

**Why.** An incrementing counter is the obvious first design and is exactly what this class replaced —
kept here as the canonical example of why "obviously correct" concurrent code needs a real adversarial
test, not just a read-through.

**Status.** Always on — the fallback under `HeuristicNodeCapacityScorer` (below) and the selector
`ProactiveMigrator` uses for replacement-node selection.

**Evidence.** `RoundRobinSchedulerTest` — including a concurrent-submission test asserting no node is
ever selected twice before every other candidate has had a turn.

---

## 2. Heuristic capacity-aware job splitting

**What.** When a job is split into N sub-tasks across N nodes, each node's share of the work should
reflect what that node can actually do *right now* — not an equal split that ignores a node running on
battery, already half-loaded, or predicted likely to fail soon.

**Where.** `HeuristicNodeCapacityScorer` — `java-control-plane/src/main/java/com/nextgen/controlplane/capacity/HeuristicNodeCapacityScorer.java`.

**How.** Every candidate node gets a single positive weight:

```
declaredCapacity = cpuCores · CPU_CORE_WEIGHT + memoryGb · MEMORY_GB_WEIGHT
weight = max(MIN_WEIGHT_FLOOR,
             declaredCapacity
             × headroom(cpuStale, cpuUsage)
             × headroom(memoryStale, memoryUsage)
             × riskFactor(riskScore))

headroom(stale, usagePercent):
    if stale: return STALE_HEADROOM_FACTOR       # neither "idle" nor "fully loaded" — a neutral guess
    return clamp(1 - usagePercent/100, HEADROOM_FLOOR, 1.0)

riskFactor(riskScore):
    return clamp(1 - RISK_PENALTY_WEIGHT × riskScore, MIN_RISK_FACTOR, 1.0)
```

Defaults: `CPU_CORE_WEIGHT=1.0`, `MEMORY_GB_WEIGHT=0.25`, `HEADROOM_FLOOR=0.1`,
`STALE_HEADROOM_FACTOR=0.5`, `RISK_PENALTY_WEIGHT=0.6`, `MIN_RISK_FACTOR=0.2`, `MIN_WEIGHT_FLOOR=0.01`.
Unreported `cpuCores`/`memoryGb` fall back to `1` rather than `0`, so a node that hasn't reported
capabilities yet still gets a fair, non-zero share instead of being silently excluded.

The job's sub-ranges are then cut proportionally to each selected node's weight using the
**largest-remainder method** — the same exact-total-preserving apportionment technique used to seat
parliaments proportionally, applied here to keep `Σ sub-range sizes = total range size` exactly, with
no node getting zero work purely from rounding.

Because the weight reads **live** headroom (not a static declared value), a node loaded up by one job's
share automatically scores lower on the *next* job submitted shortly after — back-to-back submissions
self-balance with zero additional fairness/rotation logic.

**Why not a trained model from the start?** No (node properties → actual task duration) outcome data
existed yet at the point this was built. `JobOutcomeLogger` records exactly that pair on every sub-task
completion — the seam for a future learned version is already in place, but shipping a heuristic now
(same reasoning `RuleBasedRiskScorer` below uses) beats waiting for a dataset that only this heuristic's
own operation can generate.

**Status.** Always on for job submission's *initial* dispatch. Reactive retry (`pickReplacementNode`)
and `ProactiveMigrator`'s replacement selection deliberately keep using plain round-robin — capacity
weighting is scoped to initial placement only, named explicitly rather than silently inconsistent.

**Evidence.** `HeuristicNodeCapacityScorerTest` (higher cores/memory/headroom → higher score; loaded,
stale, or at-risk nodes score lower but never below their floor); `SubmitJobIntegrationTest` with
deliberately mismatched node capabilities, asserting the higher-capacity node's real sub-range is
measurably larger while the combined result stays mathematically correct regardless of the split.

---

## 3. Rule-based risk scoring

**What.** Score a node's likelihood of failing *soon*, from trend data, cheaply enough to run every
5 seconds against every node with no external dependency — and to remain the honest fallback whenever a
trained ML model isn't actually available.

**Where.** `RuleBasedRiskScorer` — `java-control-plane/src/main/java/com/nextgen/controlplane/risk/RuleBasedRiskScorer.java`.

**How.** Three independent rules, each a real trend read from `NodeHistory`, combined as a capped
weighted sum (each rule worth 0.5, any one alone crosses the 0.5 `atRisk` threshold):

| Rule | Condition | Real signal used |
|---|---|---|
| Low battery | On battery power AND `batteryPercent < 15%` | The node's own OS-reported power state |
| Rising RTT | The last 5 `previousRttSeconds` values are **strictly increasing** | The agent's own heartbeat RTT, relayed one tick late (see "why not fabricate latency" below) |
| Memory pressure | Memory usage is **non-decreasing** across the window **and** already `≥ 90%` | Live agent-reported memory percent |

```
score = 0.0
if lowBattery(latest):           score += 0.5
if risingRtt(last 5 samples):    score += 0.5
if memoryPressure(last 5, latest): score += 0.5
riskScore = min(1.0, score)
atRisk = riskScore >= 0.5
```

A rule only fires when **every** sample it inspects actually carries the signal it needs (explicit
`available`/`known` flags checked, never assumed) — a gap in the history makes that rule abstain, not
guess. Any *one* rule alone is sufficient to flag `atRisk`: a node unplugged and nearly out of battery
is a real, standalone reason to move its work, and waiting for a second corroborating signal would waste
exactly the "before it dies" window this feature exists to buy.

**Why relay RTT instead of measuring one-way latency directly?** One-way network latency cannot be
measured without a synchronized clock between the two ends — a fabricated one-way number would be
exactly the kind of "looks precise, means nothing" metric this project's own testing discipline exists
to avoid. Instead, the agent's *own already-measured* round-trip time from its previous heartbeat is
relayed on the *next* heartbeat: a real number, reported one tick late, never invented.

**Why a hand-tuned heuristic instead of a model, here specifically?** This is the *always-available*
floor every other risk-scoring path (XGBoost below) falls back to when untrained, unreachable, or
timed out — it must work with zero data and zero external services from the very first node that ever
joins the cluster.

**Status.** Always on — the default `RiskScorer`, and the certain fallback whenever `MLRiskScorer`
can't get an honest answer from the predictor service.

**Evidence.** `RuleBasedRiskScorerTest` — one case per rule in isolation, one for the combined score,
and cases proving a data gap makes a rule abstain rather than guess.

---

## 4. Proactive migration (the project's core differentiator)

**What.** Move a node's in-flight work to a healthy node **before** the node actually dies, instead of
discovering the failure only after a missed heartbeat — the reactive approach every mainstream scheduler
(Kubernetes' NotReady+eviction, BOINC's fixed reassignment deadline) uses today.

**Where.** `RiskMonitor` (`java-control-plane/src/main/java/com/nextgen/controlplane/RiskMonitor.java`)
+ `ProactiveMigrator` (`java-control-plane/src/main/java/com/nextgen/controlplane/task/`).

**How.** `RiskMonitor` runs on its own daemon thread, structurally parallel to `HeartbeatMonitor`, on a
fixed interval (`DEFAULT_CHECK_INTERVAL_MS = 5000`):

```
every 5s, for each alive node:
    assessment := RiskScorer.score(node, recentHistory)
    transition := registry.updateRisk(node.id, assessment)   # atomic compute-and-return-transition
    if transition.risingEdge:                                # false→true edge ONLY
        migrationTrigger.migrateAwayFrom(node.id, reason)
        alertNotifier?.notifyNodeAtRisk(node.id, score, reason)
```

**The rising-edge condition is load-bearing, not an optimization.** Migrating once already moves
everything currently in flight; migrating again on every subsequent 5-second sweep while the node
remains risky would just repeatedly redispatch a task that is already correctly placed on its (fresher)
replacement, for no benefit.

`ProactiveMigrator.migrateAwayFrom` then, for every `DISPATCHED`/`RUNNING` task on the at-risk node:
picks a healthy replacement via `RoundRobinScheduler`, sends a best-effort `TaskCancel` to the old node,
calls `TaskRegistry.reassign` (bumps the attempt count, restarts from the original payload — no
checkpointing, stated plainly as a real limitation, not glossed over), and dispatches to the replacement
through the same `TaskDispatcher` path first placement used.

**What makes this safe: fencing.** Every `markRunning`/`markCompleted`/`markFailed` call is stamped
server-side with the *reporting node's own id* (from its stream identity, never trusted from the
payload) and only applies if that id still matches the task's *current* `assignedNodeId`. A late result
from the node the task was just migrated away from is dropped and counted
(`controlplane_stale_task_reports_total`), never silently accepted as authoritative — this is what
makes migrate-then-still-hear-from-the-old-node safe instead of a race.

**Why this can't be a complete replacement for reactive detection.** Distinguishing "slow" from "dead"
in bounded time is the **FLP impossibility result** — a fundamental limit of asynchronous distributed
systems, not an engineering gap this project could close with more code. A node that fails silently with
*no* observable trend beforehand (e.g. a sudden power cut) is still only caught reactively, once its
heartbeat actually stops. `HeartbeatMonitor`'s reactive path is therefore not replaced by this — it's a
backstop this algorithm reduces reliance on, never eliminates.

**Status.** Always on.

**Evidence.** `RiskMonitorTest` (injected clock, asserts migration fires exactly once on the rising
edge, not on every subsequent sweep); `ProactiveMigrationIntegrationTest` (real in-process gRPC, two
fake node streams, submits a task to node A, degrades A's real history past threshold, runs one sweep,
asserts node B receives the *same* task, and a late result from A afterward is dropped).

**Measured, live, not simulated:** during this project's own live end-to-end testing session, real
memory pressure on the test machine crossed the 90% ceiling on a running node. `RiskMonitor` fired
unprompted, `ProactiveMigrator` moved the running task to the other node, and the fencing logic
correctly dropped the stale report that arrived afterward from the node the task had just left —
detection-to-mitigation measured at **≈14ms**. For comparison: Kubernetes' reactive NotReady+eviction
path defaults to roughly **340 seconds** before a pod is rescheduled, and BOINC's default work-unit
reassignment deadline is **10 days**. These reactive-system numbers are the well-documented defaults of
those projects, not independently re-measured here; the ≈14ms figure is this project's own real,
observed measurement, not a simulation. See
[README.md's comparison-scope section](README.md#-comparison-scope--what-this-claims-against-kubernetesboinc-and-what-it-doesnt)
for the full, explicitly-bounded comparison — including what is **not** claimed.

---

## 5. XGBoost failure-risk classification (opt-in, trained)

**What.** Replace the hand-tuned heuristic above with a real learned classifier once enough real outcome
data exists — while never being allowed to *silently* replace it: every response is honest about
whether a trained model actually produced it.

**Where.** `python-predictor/train_risk_model.py --model-type xgboost` (training, default model type),
`python-predictor/model_store.py` (serving), Java-side client: `MLRiskScorer implements RiskScorer`
(`java-control-plane/src/main/java/com/nextgen/controlplane/risk/`).

**How.**

1. **Data.** Two real, disjoint sources, both operator-visible JSONL files: `risk_outcomes.jsonl` (one
   row per real `ALIVE → SUSPECTED_DEAD` transition — the positive/failure examples) and
   `risk_snapshots.jsonl` (opt-in, `RISK_SNAPSHOT_LOGGING_ENABLED=true`, one row per periodic sweep,
   labeled `1` if that node transitioned to `SUSPECTED_DEAD` within a configurable horizon afterward,
   else `0` — the source of negative/healthy examples a 100%-positive-only dataset can't provide).
2. **Features.** 14 engineered scalars per node (`python-predictor/features.py`): the same raw signals
   `RuleBasedRiskScorer` uses (battery, RTT trend, memory pressure) plus rolling mean/max of
   CPU/memory over the trend window, a least-squares RTT trend slope, and staleness. Both the logistic
   regression and XGBoost training paths share this one `extract_features` function — expanding it
   doesn't fork the pipeline.
3. **Training.** The raw `xgboost.Booster`/`xgboost.train(params, DMatrix(...), num_boost_round=...)`
   API directly — not the scikit-learn wrapper, since no scikit-learn dependency exists in this project.
   An `80/20` seeded train/validation split, a hard `MIN_TRAINING_EXAMPLES=20` floor (refuses to train
   below it), and an all-one-label refusal (a dataset with zero variation can't validate a classifier
   honestly).
4. **Current real trained model** (`python-predictor/model/risk_model.json`, checked at the time this
   document was written):

   | | |
   |---|---|
   | Training examples | **300,000** (real Alibaba PAI GPU cluster trace, imported via `import_alibaba_pai_trace.py`) |
   | Train accuracy | **88.94%** |
   | Validation accuracy | **76.27%** |
   | Feature count | 14 |

   Two other public cluster traces (Google's and a second Alibaba 2018 trace) were also imported and
   tried; neither improved on the above and this is documented plainly in `CHANGELOG.md` rather than
   only reporting the number that looked best.
5. **Serving.** `predictor_service.py`'s `GetPrediction` loads the model (mtime-polled hot reload, no
   restart needed) and returns `model_trained`, `training_example_count`, and `model_type` alongside the
   prediction — the caller must key off `model_trained`, never off the raw probability alone.
6. **Java-side honesty contract.** `MLRiskScorer` calls the predictor with a bounded deadline. If
   `model_trained=false`, the predictor is unreachable, or the call times out, it **always literally
   delegates to `RuleBasedRiskScorer`** — never fabricates or substitutes a number. This is the same
   discipline `requestPrediction`'s original advisory-only design already established.

**Why gradient-boosted trees over the original hand-rolled logistic regression?** Tree-based models
handle the feature interactions in this data (e.g. "rising RTT *and* already-high memory" is worse than
either alone) natively, without hand-engineering interaction terms, and tolerate missing/NaN feature
values by design — relevant since `staleness_millis` and rolling stats can be legitimately absent early
in a node's history.

**Status.** Opt-in, `ML_RISK_SCORER_ENABLED=true`, **and** `train_risk_model.py` must actually have been
run at least once — every response honestly reports `model_trained=false` until then. A separate opt-in
`auto_retrain.py` background thread (`AUTO_RETRAIN_ENABLED=true`, default off) periodically retrains a
**candidate** model and only promotes it if validation accuracy doesn't regress beyond a configurable
threshold (default 2 points) below the live model's — the live model can get better as data accumulates;
it cannot silently get worse.

**Evidence.** `python-predictor/tests/test_train_risk_model.py` (a synthetic, trivially-separable
dataset — proving the training path actually converges to correct classification, not just "runs
without crashing"); `MLRiskScorerTest` (real in-process gRPC against a fake `PredictorServiceImplBase` —
trained/high-risk, trained/low-risk, untrained-fallback, and unreachable-fallback, each a distinct case,
never mocked); `test_auto_retrain.py` (promotion/rejection guarantees).

---

## 6. LSTM load forecasting (opt-in, trained)

**What.** A genuinely different question from XGBoost above: not "is this node about to die" but "what
will this node's CPU/memory actually be in 5 minutes" — forecasting a continuous value from the raw
telemetry sequence, not classifying from collapsed trend scalars.

**Where.** `python-predictor/load_forecast_model.py` (model + training), `load_forecast_store.py`
(serving) — Java-side integration inside `MLRiskScorer`.

**How.** A small `torch.nn.LSTM(input_size=5, hidden_size=H, num_layers=1, batch_first=True)` plus a
linear head predicting `(cpu_percent, memory_percent)` at a configurable horizon (default 300s). Per
timestep, the raw (not collapsed) input is `[cpu_percent, memory_percent, battery_percent (0 if
unavailable), on_ac_power (0/1, 0.5 if unknown), previous_rtt_seconds (0 if unavailable)]`. Training
pairs come from consecutive `risk_snapshots.jsonl` rows for the same node: a sequence at time *t* paired
with the CPU/memory reading from a later snapshot found within tolerance of *t + horizon*. Sequences
shorter than `--sequence-length` (default 10) are dropped from training rather than padded — a stated,
deliberate first-cut scope cut, not an oversight.

`MLRiskScorer` folds a crossed-threshold forecast (`predicted_memory_percent ≥ 90%`, matching
`RuleBasedRiskScorer`'s own ceiling) into the risk score as one bounded, additive signal — it composes
with the XGBoost-driven score, never replaces or dominates it.

**Why a separate model instead of extending XGBoost?** XGBoost classifies from 14 collapsed scalars by
design — the raw per-timestep sequence a forecast needs is information that pipeline deliberately throws
away. An LSTM is the standard architecture for sequence-to-value forecasting where recent order matters
(not just recent aggregate values), which is exactly the difference between "was memory rising" (XGBoost
already captures this) and "what will memory specifically be."

**Status.** Opt-in — requires `RISK_SNAPSHOT_LOGGING_ENABLED=true` to collect training data and a
manual run of `train_load_forecast_model.py`. Deliberately **not** wired into
`HeuristicNodeCapacityScorer`/job-splitting in this pass (that scorer has no history parameter and no ML
counterpart today) — named explicitly as real future work rather than left silently inconsistent.

**Evidence.** `tests/test_load_forecast_model.py` — a synthetic linearly-trending CPU/memory series,
trained for a fixed number of epochs, asserting the forecast moves in the correct direction and lands
within a loose numeric tolerance of the true trend continuation (a statistical convergence check, since
exact-equality isn't meaningful for a regression model).

---

## 7. Raft consensus + leader election (opt-in)

**What.** Turn the control plane from a single process (a single point of failure) into a 3-replica,
fault-tolerant cluster — the standard solution to "what if the machine running the scheduler itself
dies."

**Where.** `com.nextgen.controlplane.raft` (`java-control-plane/src/main/java/com/nextgen/controlplane/raft/`)
— a from-scratch implementation, not a wrapped library.

**How, the one rule that makes the design work:** *a Raft command records a decision that has already
been made; it never records the inputs to a decision.* Everything that reads leader-local state
(heartbeat freshness, live node snapshots, the round-robin cursor, capacity/risk scores) happens on the
leader **before** proposing. The replicated command carries only the outcome. `RaftStateMachine.apply()`
is then a pure, deterministic replay over the existing `NodeRegistry`/`TaskRegistry`/`JobRegistry`,
which stay entirely Raft-unaware.

**What is replicated** (must survive a leader crash): node register/deregister, every task/job lifecycle
mutation, and the enrollment-token lifecycle (hash only, never the plaintext token).
**What stays leader-local** (self-heals within one interval, or has no correctness reason to replicate):
heartbeat processing, staleness sweeps, live risk scores, per-node telemetry history, and the
round-robin cursor.

Five correctness rules this implementation does not skip (each is a well-documented, easy-to-omit source
of silent data loss in hand-rolled Raft implementations):

1. **Vote persistence happens before the vote reply is sent**, not after — a node that granted a vote,
   crashed, and restarted must refuse to grant a second vote in the same term.
2. **The election deadline resets only when a vote is actually granted** (or a current leader is heard
   from), never on a rejected `RequestVote` — otherwise a stale-log candidate can suppress a legitimate
   election indefinitely.
3. **A follower's log is truncated only on a genuine term conflict**, never merely because an incoming
   batch is shorter than what's already held — truncating on a delayed/duplicate request is real data
   loss.
4. **Commit-index advancement requires the entry's term to equal the current term**, not just a
   replicated-majority count — this is the Raft paper's Figure-8 scenario, the single most-often-omitted
   safety rule in hand-rolled implementations, and its omission causes *silent* data loss. Paired with
   appending a no-op entry immediately on becoming leader, which is what lets a new leader's commit index
   advance past the previous term's tail promptly.
5. **A leader that hasn't heard from a majority within one election timeout steps down** — without this,
   a partitioned leader that still believes it's leader would serve arbitrarily stale reads forever,
   since every RPC (reads included) routes through the leader in this design.

A durable write-ahead log (`log.wal`, append-only, tab-separated `index / term / Base64URL(command) /
CRC32`) recovers by replaying and **truncating at the first line that fails its CRC, fails to parse, or
breaks index continuity** — directly testable by appending garbage to the file and confirming recovery
still lands on a consistent state.

**Why hand-rolled instead of an existing Raft library?** The design needed byte-identical determinism
across `NodeRegistry`/`TaskRegistry`/`JobRegistry` application — including every timestamp field, via an
`ApplyClock` that returns the leader's proposed timestamp while inside `apply()` and real wall-clock time
everywhere else — which is easier to guarantee end to end when the apply layer is written against this
project's own registries directly, rather than adapting them to fit a general-purpose library's state
machine contract.

**Status.** Opt-in, `RAFT_ENABLED=false` by default — every existing single-node deployment and test is
unaffected until an operator opts in. `docker-compose.raft.yml` runs a real 3-replica topology.

**Evidence.** Three-phase test strategy: Phase A tests Raft in isolation (election, log replication,
durability, split votes) against an in-memory transport with injectable faults; Phase B proves two
independent state machines given the same command sequence produce byte-identical output, including
every timestamp; Phase C is real in-process gRPC, headlined by `ReplicatedControlPlaneIntegrationTest` —
three wired replicas, register a node and a task, kill the leader, confirm the new leader recovers the
same assignment, reconnect the node, and confirm a real result is still correctly received. A dedicated
`RaftSafetyInvariantTest` runs a bounded randomized fault-injection loop (partitions, crashes, restarts)
asserting after every single step: at most one leader per term, the log-matching property,
`lastApplied ≤ commitIndex ≤ lastIndex`, and identical applied state across every replica at any shared
index.

**Explicitly out of scope, named rather than silently absent:** log compaction/snapshotting (state is
always replayed from index 1 — correctness-safe, costs only startup time as the log grows), dynamic
cluster membership (`RAFT_PEERS` is a static list; changing it needs a restart), pre-vote and
`AppendEntries` batching (standard production optimizations, not correctness-critical for a first cut),
and follower-served reads beyond the one deliberately-placed `GetClusterStatus` extension point (every
other RPC routes through the leader; the cheap step-down rule bounds staleness instead of a full
`ReadIndex` protocol).

---

## 8. Distributed Docker-Compose scheduling: node-level exclusivity

**What.** Spread a multi-service project across whichever nodes are currently idle and Docker-capable —
without a cluster-wide lock that would serialize unrelated projects through one node at a time.

**Where.** `JobCoordinator.submitDockerComposeJob` (`java-control-plane/src/main/java/com/nextgen/controlplane/job/`).

**How.** The candidate list for a `DOCKER_COMPOSE_SERVICE` job's placement is filtered to:

```
alive AND capabilities.dockerAvailable AND
    taskRegistry.tasksOnNode(nodeId).noneMatch(t -> t.kind == DOCKER_COMPOSE_SERVICE)
```

This filter *is* the entire exclusivity mechanism: a node already running one compose service is
excluded from a **second, concurrently-submitted** project's candidate list, but any *other* currently
idle Docker-capable node remains eligible. Two projects submitted back-to-back land on disjoint node
subsets automatically — this is node-level exclusivity, not a cluster-wide mutex, converting the cluster
into a real distributed build/run farm instead of one queue.

Replicas of the same service (`replicas: N`) reuse this same filter unchanged: since a node running one
Docker-Compose task is excluded from the candidate list for any other Docker-Compose sub-task in the
same job, N replicas land on N distinct nodes for free — no separate anti-affinity logic was needed.

**Why not weight this by `HeuristicNodeCapacityScorer` too?** It already is — the *exclusivity filter*
narrows the candidate list first, then the capacity scorer (§2) ranks and selects within whatever
survives the filter. The two compose cleanly: filter for eligibility, then score for proportional share.

**Status.** Always on for any `DOCKER_COMPOSE_SERVICE` job; `PRIME_COUNT_RANGE` tasks are unaffected —
the filter only applies on the Docker-kind branch.

**Evidence.** `JobCoordinatorTest`/`SubmitJobIntegrationTest` Docker-kind cases; live-verified — two
projects submitted back-to-back with more total nodes than either alone needs both completed
concurrently on disjoint node subsets in a real multi-node run.

---

## 9. Load-balanced multi-replica relay

**What.** Give `replicas: N` of a service a single stable address that actually distributes real
connections across all N running copies — the closest analog this project has to a Kubernetes Service,
built without abandoning the hub-and-spoke rule (no node ever accepts a direct inbound connection, from
the control plane or from any other node).

**Where.** `PortRelayManager` (`java-control-plane/src/main/java/com/nextgen/controlplane/task/`).

**How.** Each replica independently opens its own outbound `TunnelPort` stream to the control plane and
registers as one more backend for the same relay port (originally single-backend; extended to a
`CopyOnWriteArrayList` of backends with a round-robin cursor, the identical lock-free rotation idiom
`RoundRobinScheduler` §1 already established). Each new inbound consumer connection is assigned a
backend **once, at accept time**, and stays pinned to that backend for the connection's full lifetime —
necessary because routing a single long-lived connection to a different backend mid-stream would corrupt
whatever protocol is running over it. A backend disconnecting removes only that one backend and closes
only its own in-flight tunnels; the listener and every other replica keep serving without interruption.
The relay port itself is only released once the last backend detaches.

**Why round-robin at accept time instead of per-byte or per-request load balancing?** This relay is L4
(raw TCP), not an HTTP-aware proxy — it has no visibility into request boundaries within an established
connection, so the only meaningful unit to balance is the connection itself.

**Status.** Automatic whenever a Docker-Compose service declares `replicas > 1`.

**Evidence.** `PortRelayManagerTest` (multi-attach round-robin distribution; single-backend removal
without dropping the listener); a real end-to-end test with 2 replica backends and several real
connections, confirming responses genuinely come from both replicas (not just the first one attached),
and that a mid-run backend detach doesn't drop the survivor's traffic.

---

## 10. Rolling update + rollback

**What.** Replace a running project's service images/config with a new version without taking the whole
service down at once — and be able to reverse it.

**Where.** `JobCoordinator.updateJob`, driven by `nx update <job-id> <compose-file>` / `nx rollback`.

**How.** For each changed service with `replicas > 1`: sequentially, **one replica at a time** —
confirmed-cancel the old replica (§ below), dispatch the new one, and wait for it to actually be ready
(real container health via the `healthcheck:` result when declared, or `RUNNING` state otherwise) before
touching the next. Bounded parallelism of exactly 1 (never `maxSurge`/`maxUnavailable`-configurable) is
an explicit, named scope cut, not an oversight. `nx rollback` reconstructs the previous spec directly
from the superseded job's own stored task payloads (no separate version history kept, bounded depth of
1) and re-applies it through the identical one-at-a-time path.

**Confirmed cancellation, the primitive this depends on.** `CancelTask`/`nx down` used to be a
fire-and-forget push of a `TaskCancel` command with no confirmation the target actually stopped. It now
**polls `TaskRegistry` for a real terminal state** before returning success —
`TaskDispatcher.cancelAndAwaitConfirmation` is the shared, tested primitive both the CLI-facing cancel
RPC and this rolling-update path build on. Without this guarantee, "replace one replica at a time" could
not actually promise "never more than one replica down simultaneously" — the old replica might still be
mid-shutdown when the new one is judged ready.

**Why sequential instead of parallel-with-a-budget?** A configurable `maxSurge`/`maxUnavailable` is
genuinely more capability, but a hard sequential swap is the smallest correct version of "rolling" — it
guarantees the actual invariant that matters (at most one replica ever down at once) without needing a
scheduling policy for partial batches.

**Status.** Scoped to the direct (non-Raft) path in this pass; attempting it under Raft replication
fails with a clear, honest error rather than silently behaving differently.

**Evidence.** A synthetic 3-replica update test asserting replicas are replaced strictly one at a time
(never more than one down simultaneously — the actual rolling-update invariant) and gated on health; a
rollback test re-applying the stored previous spec and confirming it matches the original exactly.

---

## Summary: default vs. opt-in

| Algorithm | Status | Env var |
|---|---|---|
| Round-robin scheduling | Always on | — |
| Heuristic capacity-aware splitting | Always on (job initial dispatch) | — |
| Rule-based risk scoring | Always on (default scorer) | — |
| Proactive migration | Always on | — |
| Node-level Docker exclusivity | Always on (Docker-kind jobs) | — |
| Load-balanced replica relay | Automatic (`replicas > 1`) | — |
| Rolling update / rollback | Always on (non-Raft path) | — |
| XGBoost risk classification | Opt-in | `ML_RISK_SCORER_ENABLED=true` + trained model |
| Auto-retrain | Opt-in | `AUTO_RETRAIN_ENABLED=true` |
| LSTM load forecasting | Opt-in | `RISK_SNAPSHOT_LOGGING_ENABLED=true` + trained model |
| Raft consensus | Opt-in | `RAFT_ENABLED=true` |

## Full test evidence, as of this document

877+ automated tests across four modules (`java-control-plane`, `desktop-ui`, `cli`, `python-predictor`),
real Docker containers, real gRPC channels, real Raft fault-injection loops, zero mocked assertions on
any mechanism under test. Exact current counts are always available by running the suites directly —
see [DEVELOPMENT.md](DEVELOPMENT.md) — rather than restated here as a number that can silently drift out
of date.
