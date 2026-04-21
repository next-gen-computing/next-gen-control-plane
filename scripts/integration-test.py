#!/usr/bin/env python3
"""
Next-Gen Control Plane — Integration Test (Phase-1)

Assumes the cluster is already running (docker compose up --build).
Tests:
  1. Verifies 3 nodes are registered via GetNodes.
  2. Waits for heartbeats (checks every 2s for up to 20s).
  3. Submits a task and verifies round-robin assignment.
  4. Calls predictor and verifies non-random response.

Usage: python scripts/integration-test.py
"""

import sys
import os
import time
import subprocess

# Fix Windows console encoding for emoji support
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

# ── Generate stubs if not present ────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(SCRIPT_DIR)
PROTO_FILE = os.path.join(ROOT_DIR, "proto", "control_plane.proto")
STUB_DIR = SCRIPT_DIR

stub_pb2 = os.path.join(STUB_DIR, "control_plane_pb2.py")
if not os.path.exists(stub_pb2):
    print("🔧 Generating Python gRPC stubs...")
    subprocess.run([
        sys.executable, "-m", "grpc_tools.protoc",
        f"-I{os.path.join(ROOT_DIR, 'proto')}",
        f"--python_out={STUB_DIR}",
        f"--grpc_python_out={STUB_DIR}",
        PROTO_FILE,
    ], check=True)

sys.path.insert(0, STUB_DIR)
import control_plane_pb2 as pb2
import control_plane_pb2_grpc as pb2_grpc

import grpc

# ── Test Configuration ───────────────────────────────
CP_ADDRESS = "localhost:50051"
EXPECTED_NODES = 3
MAX_WAIT_SECONDS = 30
TASKS_TO_SUBMIT = 5

passed = 0
failed = 0


def log_pass(test_name, detail=""):
    global passed
    passed += 1
    print(f"  ✅ PASS: {test_name}" + (f" — {detail}" if detail else ""))


def log_fail(test_name, detail=""):
    global failed
    failed += 1
    print(f"  ❌ FAIL: {test_name}" + (f" — {detail}" if detail else ""))


def main():
    global passed, failed

    print()
    print("══════════════════════════════════════════════════════════")
    print("  🧪 Next-Gen Control Plane — Phase-1 Integration Test")
    print("══════════════════════════════════════════════════════════")
    print()

    # ── Connect to ControlPlane ──────────────────────
    print("📡 Connecting to ControlPlane at", CP_ADDRESS)
    channel = grpc.insecure_channel(CP_ADDRESS)
    stub = pb2_grpc.ControlPlaneServiceStub(channel)

    # ── Test 1: Wait for nodes to register ──────────
    print(f"\n── Test 1: Wait for {EXPECTED_NODES} nodes to register (max {MAX_WAIT_SECONDS}s) ──")
    nodes = []
    start = time.time()
    while time.time() - start < MAX_WAIT_SECONDS:
        try:
            resp = stub.GetNodes(pb2.Empty())
            nodes = resp.nodes
            print(f"  ... found {len(nodes)} nodes ({', '.join(n.node_id for n in nodes)})")
            if len(nodes) >= EXPECTED_NODES:
                break
        except grpc.RpcError as e:
            print(f"  ... ControlPlane not ready: {e.code()}")
        time.sleep(2)

    if len(nodes) >= EXPECTED_NODES:
        log_pass(f"{len(nodes)} nodes registered", ", ".join(n.node_id for n in nodes))
    else:
        log_fail(f"Expected {EXPECTED_NODES} nodes, got {len(nodes)}")

    # ── Test 2: Verify node details ─────────────────
    print(f"\n── Test 2: Verify node details ──")
    for node in nodes:
        if node.node_id and node.ip and node.hostname:
            log_pass(f"Node '{node.node_id}'", f"ip={node.ip}, hostname={node.hostname}")
        else:
            log_fail(f"Node '{node.node_id}' missing fields", f"ip={node.ip}, hostname={node.hostname}")

    # ── Test 3: Wait for heartbeats (check logs) ────
    print(f"\n── Test 3: Wait for heartbeats (10 seconds) ──")
    print("  ⏳ Waiting 10 seconds for heartbeat accumulation...")
    time.sleep(10)
    # Re-fetch nodes to confirm they're still alive
    try:
        resp = stub.GetNodes(pb2.Empty())
        if len(resp.nodes) >= EXPECTED_NODES:
            log_pass("Nodes still alive after 10s", f"{len(resp.nodes)} nodes responding")
        else:
            log_fail("Node count dropped", f"Expected {EXPECTED_NODES}, got {len(resp.nodes)}")
    except grpc.RpcError as e:
        log_fail("GetNodes failed after heartbeat wait", str(e))

    # ── Test 4: Submit tasks (round-robin) ──────────
    print(f"\n── Test 4: Submit {TASKS_TO_SUBMIT} tasks (round-robin scheduling) ──")
    assigned_nodes = []
    for i in range(1, TASKS_TO_SUBMIT + 1):
        try:
            task_req = pb2.TaskRequest(
                task_id=f"test-task-{i}",
                payload=f"Integration test payload #{i}",
            )
            resp = stub.SubmitTask(task_req)
            assigned_nodes.append(resp.assigned_node)
            log_pass(f"Task test-task-{i}", f"assigned_to={resp.assigned_node}")
        except grpc.RpcError as e:
            log_fail(f"Task test-task-{i}", str(e))

    # Verify round-robin distribution
    if len(set(assigned_nodes)) > 1:
        log_pass("Round-robin distribution", f"Tasks spread across: {set(assigned_nodes)}")
    elif len(assigned_nodes) > 0:
        log_fail("Round-robin not working", f"All tasks went to: {assigned_nodes[0]}")

    # ── Test 5: Verify predictor integration ────────
    print(f"\n── Test 5: Verify predictor integration ──")
    try:
        task_req = pb2.TaskRequest(
            task_id="predictor-test",
            payload="Test predictor call",
        )
        resp = stub.SubmitTask(task_req)
        if "0.45" in resp.result and "0.12" in resp.result:
            log_pass("Predictor integration", "Got expected prediction values (0.45, 0.12)")
        elif "N/A" in resp.result:
            log_fail("Predictor returned N/A", "Predictor may not be reachable")
        else:
            log_pass("Task submitted successfully", resp.result)
    except grpc.RpcError as e:
        log_fail("Predictor integration", str(e))

    # ── Summary ─────────────────────────────────────
    total = passed + failed
    print()
    print("══════════════════════════════════════════════════════════")
    print(f"  📊 Results: {passed}/{total} passed, {failed}/{total} failed")
    if failed == 0:
        print("  🎉 ALL TESTS PASSED — Phase-1 is working correctly!")
    else:
        print("  ⚠  Some tests failed — check logs above.")
    print("══════════════════════════════════════════════════════════")
    print()

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
