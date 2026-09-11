#!/usr/bin/env python3
"""
End-to-end scenario against a running `docker compose` stack.

Scenario, asserted rather than eyeballed:
  1. three nodes join and report real (non-zero, changing) metrics
  2. tasks distribute across all three
  3. a node is killed and is marked SUSPECTED_DEAD within the timeout window
  4. work reroutes away from it
  5. the node rejoins and is re-integrated with no duplicate entry
  6. Prometheus has scraped every component

Run:
    docker compose up --build -d
    python scripts/e2e-test.py

STATUS: this script is committed but has NOT been executed. The Docker daemon was
unavailable in the environment where it was written, so its assertions are
unverified. The equivalent guarantees ARE verified by the JVM test suite --
see KillAndReconnectIntegrationTest and MutualTlsEndToEndTest, which run the same
scenarios over a real gRPC transport.
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "http://localhost:8085/api/nodes"
PROMETHEUS = "http://localhost:9464"
EXPECTED_NODES = 3
# HeartbeatMonitor uses a 6s timeout and a 3s sweep, so a node can take up to ~9s
# to be declared dead. 20s leaves room for container scheduling noise.
DEATH_WINDOW_SECONDS = 20

passed = 0
failed = 0


def check(ok: bool, message: str) -> bool:
    global passed, failed
    if ok:
        passed += 1
        print(f"  PASS  {message}")
    else:
        failed += 1
        print(f"  FAIL  {message}")
    return ok


def fetch(url: str, timeout: int = 5):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def api(timeout: int = 5):
    return fetch(API, timeout)


def wait_for(predicate, seconds: int, poll: float = 1.0):
    """Polls until predicate returns truthy or the budget expires."""
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (urllib.error.URLError, OSError, json.JSONDecodeError):
            pass
        time.sleep(poll)
    return last


def compose(*args: str) -> int:
    return subprocess.run(["docker", "compose", *args], check=False).returncode


# ── 1. Nodes join ────────────────────────────────────────────────────────────

def test_nodes_join():
    print("\n[1] Nodes register and report real metrics")
    payload = wait_for(
        lambda: (d := api()) if len(d.get("nodes", [])) >= EXPECTED_NODES else None, 90)
    if not check(payload is not None and len(payload.get("nodes", [])) >= EXPECTED_NODES,
                 f"{EXPECTED_NODES} nodes registered"):
        return None

    for node in payload["nodes"]:
        check(bool(node.get("nodeId")) and bool(node.get("hostname")),
              f"node {node.get('nodeId')} reports an identity")

    # A reading must be present AND not flagged stale. The point of the exercise is
    # that unavailable readings are reported as unavailable, so a node whose metrics
    # are all null is a legitimate state -- but all three being null is not.
    measured = [n for n in payload["nodes"]
                if n.get("cpuUsage") is not None and not n.get("cpuStale")]
    check(len(measured) > 0, "at least one node reports a usable CPU reading")

    check(payload["summary"]["aliveNodes"] >= EXPECTED_NODES,
          "summary counts the nodes as alive")
    return payload


def test_metrics_change():
    print("\n[2] Metrics are live, not frozen")
    first = {n["nodeId"]: n.get("lastHeartbeatMs") for n in api()["nodes"]}
    time.sleep(6)
    second = {n["nodeId"]: n.get("lastHeartbeatMs") for n in api()["nodes"]}

    advanced = [k for k in first if second.get(k, 0) > first.get(k, 0)]
    check(len(advanced) >= EXPECTED_NODES,
          f"heartbeat timestamps advanced for {len(advanced)} nodes")


# ── 3. Task distribution ─────────────────────────────────────────────────────

def submit_tasks(count: int):
    """Submits tasks through the gRPC API via the monitor helper."""
    result = subprocess.run(
        [sys.executable, "scripts/integration-test.py", "--tasks-only", str(count)],
        capture_output=True, text=True, check=False)
    return result.stdout


def test_task_distribution():
    print("\n[3] Tasks distribute across nodes")
    output = submit_tasks(9)
    assigned = {line.split("->")[-1].strip()
                for line in output.splitlines() if "->" in line}
    check(len(assigned) >= 2,
          f"tasks spread across {len(assigned)} nodes")


# ── 4/5. Kill and rejoin ─────────────────────────────────────────────────────

def test_kill_and_rejoin():
    print("\n[4] A killed node is marked dead and stops receiving work")
    before = api()
    victim = "node3"

    if compose("stop", victim) != 0:
        check(False, f"could not stop {victim}")
        return

    payload = wait_for(
        lambda: (d := api()) if any(n["nodeId"] == victim and n["status"] == "SUSPECTED_DEAD"
                                    for n in d["nodes"]) else None,
        DEATH_WINDOW_SECONDS)
    dead = payload and any(n["nodeId"] == victim and n["status"] == "SUSPECTED_DEAD"
                           for n in payload["nodes"])
    check(bool(dead), f"{victim} marked SUSPECTED_DEAD within {DEATH_WINDOW_SECONDS}s")

    check(len(payload["nodes"]) == len(before["nodes"]),
          "the dead node is still listed (an operator must be able to see it)")

    output = submit_tasks(9)
    assigned = {line.split("->")[-1].strip()
                for line in output.splitlines() if "->" in line}
    check(victim not in assigned, f"no work routed to {victim} after it died")

    print("\n[5] The node rejoins cleanly")
    if compose("start", victim) != 0:
        check(False, f"could not restart {victim}")
        return

    payload = wait_for(
        lambda: (d := api()) if any(n["nodeId"] == victim and n["status"] == "ALIVE"
                                    for n in d["nodes"]) else None, 60)
    revived = payload and any(n["nodeId"] == victim and n["status"] == "ALIVE"
                              for n in payload["nodes"])
    check(bool(revived), f"{victim} is ALIVE again")

    ids = [n["nodeId"] for n in payload["nodes"]]
    check(len(ids) == len(set(ids)), "no duplicate entry was created on rejoin")
    check(len(ids) == EXPECTED_NODES, f"exactly {EXPECTED_NODES} nodes, no zombies")

    output = submit_tasks(9)
    assigned = {line.split("->")[-1].strip()
                for line in output.splitlines() if "->" in line}
    check(victim in assigned, f"{victim} is back in the scheduling rotation")


# ── 6. Prometheus ────────────────────────────────────────────────────────────

def test_prometheus():
    print("\n[6] Prometheus has scraped every component")
    targets = wait_for(lambda: fetch(f"{PROMETHEUS}/api/v1/targets"), 60)
    if not check(targets is not None, "Prometheus is reachable"):
        return

    active = targets["data"]["activeTargets"]
    up = {t["labels"].get("job") for t in active if t["health"] == "up"}
    for job in ("control-plane", "node-agents", "predictor"):
        check(job in up, f"job '{job}' is up")

    # The charts read these series; if they are absent the charts have nothing real
    # to draw and must show "no data" rather than inventing a line.
    for metric in ("node_cpu_usage", "controlplane_active_nodes",
                   "node_heartbeat_rtt_seconds_count"):
        result = fetch(f"{PROMETHEUS}/api/v1/query?query={metric}")
        check(len(result["data"]["result"]) > 0, f"series '{metric}' has data")


def main():
    print("=" * 62)
    print("  Next-Gen Control Plane - end-to-end scenario")
    print("=" * 62)

    if not test_nodes_join():
        print("\nNodes never registered; aborting.")
        return 1

    test_metrics_change()
    test_task_distribution()
    test_kill_and_rejoin()
    test_prometheus()

    total = passed + failed
    print("\n" + "=" * 62)
    print(f"  {passed}/{total} passed, {failed}/{total} failed")
    print("=" * 62)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
