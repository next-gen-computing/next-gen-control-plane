#!/usr/bin/env python3
"""
Next-Gen Control Plane — Live Terminal Monitor
Queries GetNodes RPC every 2 seconds and displays a rich table.
Also fetches Prometheus metrics for extra detail.

Usage: python scripts/monitor.py
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
STUB_DIR = os.path.join(SCRIPT_DIR)

# Generate stubs in the scripts directory
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

try:
    from rich.console import Console
    from rich.table import Table
    from rich.live import Live
    from rich.panel import Panel
    from rich.text import Text
    HAS_RICH = True
except ImportError:
    HAS_RICH = False


def get_nodes(stub):
    """Calls GetNodes RPC and returns list of NodeInfo."""
    try:
        response = stub.GetNodes(pb2.Empty())
        return response.nodes
    except grpc.RpcError as e:
        return None


def build_table(nodes, iteration):
    """Builds a Rich table from node list."""
    table = Table(
        title=f"🖥  Next-Gen Control Plane — Live Node Health  (refresh #{iteration})",
        show_header=True,
        header_style="bold cyan",
        border_style="bright_blue",
    )
    table.add_column("Node ID", style="bold white", min_width=10)
    table.add_column("Hostname", style="dim white", min_width=15)
    table.add_column("IP Address", style="dim white", min_width=15)
    table.add_column("Status", min_width=12)

    if nodes is None:
        table.add_row("⚠", "ControlPlane unreachable", "", "[red]OFFLINE[/red]")
        return table

    if len(nodes) == 0:
        table.add_row("—", "No nodes registered yet", "", "[yellow]WAITING[/yellow]")
        return table

    for node in nodes:
        status = "[green]● REGISTERED[/green]"
        table.add_row(
            node.node_id,
            node.hostname,
            node.ip,
            status,
        )

    return table


def run_rich_monitor():
    """Rich-based live monitor with auto-refresh."""
    console = Console()
    channel = grpc.insecure_channel("localhost:50051")
    stub = pb2_grpc.ControlPlaneServiceStub(channel)

    console.print("\n[bold cyan]Connecting to ControlPlane at localhost:50051...[/bold cyan]\n")

    iteration = 0
    with Live(console=console, refresh_per_second=1) as live:
        while True:
            iteration += 1
            nodes = get_nodes(stub)
            table = build_table(nodes, iteration)
            live.update(Panel(table, border_style="bright_blue"))
            time.sleep(2)


def run_simple_monitor():
    """Fallback monitor without Rich."""
    channel = grpc.insecure_channel("localhost:50051")
    stub = pb2_grpc.ControlPlaneServiceStub(channel)

    print("\nConnecting to ControlPlane at localhost:50051...")
    iteration = 0
    while True:
        iteration += 1
        nodes = get_nodes(stub)
        os.system('cls' if os.name == 'nt' else 'clear')
        print(f"\n=== Next-Gen Control Plane — Live Monitor (#{iteration}) ===\n")
        if nodes is None:
            print("  ⚠ ControlPlane unreachable")
        elif len(nodes) == 0:
            print("  No nodes registered yet...")
        else:
            print(f"  {'Node ID':<12} {'Hostname':<20} {'IP':<16} {'Status'}")
            print(f"  {'─'*12} {'─'*20} {'─'*16} {'─'*10}")
            for n in nodes:
                print(f"  {n.node_id:<12} {n.hostname:<20} {n.ip:<16} REGISTERED")
        print(f"\n  Total nodes: {len(nodes) if nodes else 0}")
        time.sleep(2)


if __name__ == "__main__":
    try:
        if HAS_RICH:
            run_rich_monitor()
        else:
            print("ℹ  Install 'rich' for a prettier display: pip install rich")
            run_simple_monitor()
    except KeyboardInterrupt:
        print("\n\n👋 Monitor stopped.")
