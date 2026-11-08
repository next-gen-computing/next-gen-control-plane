#!/usr/bin/env python3
"""End-to-end checks against a running server and externally managed real agents.

The base Compose stack starts only the server, predictor, and Prometheus. Start the
expected node agents separately, then run this script. Optional node-loss checks
need explicit stop/start commands, for example:

        python scripts/e2e-test.py --expected-nodes 3 --victim-node-id node3 \
            --stop-command "ssh worker3 sudo systemctl stop nextgen-agent" \
            --start-command "ssh worker3 sudo systemctl start nextgen-agent"

Without a victim and commands, registration, metrics, task execution, and Prometheus
checks still run; node-loss and rejoin checks are reported as skipped.
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import grpc
import control_plane_pb2 as pb2
import control_plane_pb2_grpc as pb2_grpc

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
    return fetch(args.dashboard_url, timeout)


def wait_for(predicate, seconds: int, poll: float = 1.0):
    """Polls until predicate returns truthy or the budget expires."""
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (urllib.error.URLError, OSError, json.JSONDecodeError, grpc.RpcError):
            pass
        time.sleep(poll)
    return last


def run_node_command(command: str, node_id: str) -> bool:
    rendered = command.replace("{node_id}", node_id)
    process = None
    try:
        process = subprocess.Popen(rendered, shell=True, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
        exit_code = process.wait(timeout=30)
        if exit_code != 0:
            print(f"  command for {node_id} exited with status {exit_code}")
        return exit_code == 0
    except subprocess.TimeoutExpired as error:
        if process is not None:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               check=False, timeout=10, capture_output=True)
            else:
                process.kill()
                process.wait(timeout=5)
        print(f"  command failed for {node_id}: {error}")
        return False
    except OSError as error:
        print(f"  command failed for {node_id}: {error}")
        return False


# ── 1. Nodes join ────────────────────────────────────────────────────────────

def test_nodes_join():
    print("\n[1] Nodes register and report real metrics")
    def expected_nodes_registered():
        data = api()
        return data if len(data.get("nodes", [])) >= EXPECTED_NODES else None

    payload = wait_for(expected_nodes_registered, 90)
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

def submit_tasks(count: int, forbidden_node: str | None = None) -> list[str]:
    """Submits and verifies real prime-count tasks through the control-plane API."""
    channel = grpc.insecure_channel(args.control_plane)
    stub = pb2_grpc.ControlPlaneServiceStub(channel)
    assigned_nodes = []
    try:
        for index in range(count):
            start = 2 + index * 1000
            for attempt in range(3):
                task_id = f"e2e-{uuid.uuid4().hex}"
                response = stub.SubmitTask(pb2.TaskRequest(
                    task_id=task_id,
                    payload=json.dumps({"range_start": start, "range_end": start + 1000}),
                    kind=pb2.TASK_KIND_PRIME_COUNT_RANGE,
                ), timeout=10)
                node_id = response.assigned_node

                if node_id != "NONE" and response.result.startswith("FAILED"):
                    try:
                        error = stub.GetTaskStatus(
                            pb2.TaskStatusRequest(task_id=task_id), timeout=5).error.lower()
                    except grpc.RpcError:
                        error = ""
                    if "no open task channel" in error and attempt < 2:
                        print(f"  task channel for {node_id} is still opening; retrying")
                        continue

                accepted = node_id != "NONE" and not response.result.startswith("FAILED")
                check(accepted, f"task {task_id} accepted by {node_id}: {response.result}")
                if not accepted:
                    break

                assigned_nodes.append(node_id)
                if forbidden_node:
                    check(node_id != forbidden_node,
                          f"task {task_id} was not assigned to unavailable node {forbidden_node}")

                def terminal_status():
                    status = stub.GetTaskStatus(pb2.TaskStatusRequest(task_id=task_id), timeout=5)
                    return status if status.state in (pb2.TASK_STATE_COMPLETED,
                                                       pb2.TASK_STATE_FAILED) else None

                status = wait_for(terminal_status, 60)
                if (status is not None and status.state == pb2.TASK_STATE_FAILED
                        and "no open task channel" in status.error.lower() and attempt < 2):
                    print(f"  task channel for {node_id} closed during dispatch; retrying")
                    continue

                completed = status is not None and status.state == pb2.TASK_STATE_COMPLETED
                try:
                    result = json.loads(status.result_json) if completed else {}
                except (TypeError, json.JSONDecodeError):
                    result = {}
                check(completed and isinstance(result.get("prime_count"), int),
                      f"task {task_id} completed on {node_id} with a real prime-count result")
                break
    finally:
        channel.close()
    return assigned_nodes


def test_task_distribution():
    print("\n[3] Tasks distribute across nodes")
    assigned = set(submit_tasks(args.expected_nodes * 3))
    check(len(assigned) >= min(2, args.expected_nodes),
          f"tasks spread across {len(assigned)} nodes")


# ── 4/5. Kill and rejoin ─────────────────────────────────────────────────────

def test_kill_and_rejoin():
    if not args.victim_node_id:
        print("\n[4/5] Skipped node-loss checks; pass --victim-node-id and stop/start commands to enable")
        return

    print("\n[4] A killed node is marked dead and stops receiving work")
    before = api()
    victim = args.victim_node_id

    if not args.stop_command or not run_node_command(args.stop_command, victim):
        check(False, f"could not stop {victim}; configure --stop-command")
        return

    dead = False
    try:
        def victim_is_dead():
            data = api()
            return data if any(n["nodeId"] == victim and n["status"] == "SUSPECTED_DEAD"
                               for n in data["nodes"]) else None

        payload = wait_for(victim_is_dead, DEATH_WINDOW_SECONDS)
        dead = bool(payload and any(n["nodeId"] == victim and n["status"] == "SUSPECTED_DEAD"
                                    for n in payload["nodes"]))
        check(dead, f"{victim} marked SUSPECTED_DEAD within {DEATH_WINDOW_SECONDS}s")

        if dead:
            check(len(payload["nodes"]) == len(before["nodes"]),
                  "the dead node is still listed (an operator must be able to see it)")
            assigned = submit_tasks(max(6, args.expected_nodes * 2), forbidden_node=victim)
            check(victim not in assigned, f"no work routed to {victim} after it died")
    finally:
        restarted = bool(args.start_command) and run_node_command(args.start_command, victim)
        check(restarted, f"restart command completed for {victim}")

    if not dead or not restarted:
        return

    print("\n[5] The node rejoins cleanly")
    def victim_is_alive():
        data = api()
        return data if any(n["nodeId"] == victim and n["status"] == "ALIVE"
                           for n in data["nodes"]) else None

    payload = wait_for(victim_is_alive, 60)
    revived = payload and any(n["nodeId"] == victim and n["status"] == "ALIVE"
                              for n in payload["nodes"])
    check(bool(revived), f"{victim} is ALIVE again")
    if not revived:
        return

    ids = [n["nodeId"] for n in payload["nodes"]]
    check(len(ids) == len(set(ids)), "no duplicate entry was created on rejoin")
    check(len(ids) == EXPECTED_NODES, f"exactly {EXPECTED_NODES} nodes, no zombies")

    assigned = submit_tasks(max(6, args.expected_nodes * 2))
    check(victim in assigned, f"{victim} is back in the scheduling rotation")


# ── 6. Prometheus ────────────────────────────────────────────────────────────

def test_prometheus():
    if args.skip_prometheus:
        print("\n[6] Skipped Prometheus checks by request")
        return

    print("\n[6] Prometheus has scraped every component")
    targets = wait_for(lambda: fetch(f"{args.prometheus_url}/api/v1/targets"), 60)
    if not check(targets is not None, "Prometheus is reachable"):
        return

    active = targets["data"]["activeTargets"]
    up = {t["labels"].get("job") for t in active if t["health"] == "up"}
    for job in ("control-plane", "predictor", "prometheus"):
        check(job in up, f"job '{job}' is up")

    # Agents are outbound-only and cannot be scraped directly through arbitrary NATs.
    # Verify control-plane metrics that aggregate real heartbeat activity instead.
    for metric in ("controlplane_active_nodes", "controlplane_heartbeats_total",
                   "controlplane_heartbeat_interarrival_seconds_count"):
        query = urllib.parse.urlencode({"query": metric})
        result = fetch(f"{args.prometheus_url}/api/v1/query?{query}")
        check(len(result["data"]["result"]) > 0, f"series '{metric}' has data")


def main():
    global args, EXPECTED_NODES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-plane", default="localhost:50051")
    parser.add_argument("--dashboard-url", default=API)
    parser.add_argument("--prometheus-url", default=PROMETHEUS)
    parser.add_argument("--skip-prometheus", action="store_true",
                        help="skip Prometheus checks when validating a local cluster without it")
    parser.add_argument("--expected-nodes", type=int, default=EXPECTED_NODES)
    parser.add_argument("--victim-node-id")
    parser.add_argument("--stop-command",
                        help="shell command to stop the victim; use {node_id} as a placeholder")
    parser.add_argument("--start-command",
                        help="shell command to restart the victim; use {node_id} as a placeholder")
    args = parser.parse_args()
    EXPECTED_NODES = args.expected_nodes
    if EXPECTED_NODES < 1:
        parser.error("--expected-nodes must be at least 1")
    if bool(args.stop_command) != bool(args.start_command):
        parser.error("--stop-command and --start-command must be provided together")
    if args.victim_node_id and not args.stop_command:
        parser.error("node-loss checks require both --stop-command and --start-command")

    print("=" * 62)
    print("  Next-Gen Control Plane - live-cluster end-to-end scenario")
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
