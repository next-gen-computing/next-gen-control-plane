#!/usr/bin/env bash
# ─────────────────────────────────────────────────────
# monitor.sh — Live terminal monitor for the cluster
# Uses the Python Rich-based monitor for a beautiful display
# Usage: ./scripts/monitor.sh
# ─────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "══════════════════════════════════════════════════"
echo "  📊 Next-Gen Control Plane — Live Monitor"
echo "══════════════════════════════════════════════════"
echo ""

# Check if Python and grpcio are available
if command -v python3 &>/dev/null; then
    PYTHON=python3
elif command -v python &>/dev/null; then
    PYTHON=python
else
    echo "❌ Python not found. Install Python 3.11+ to use the monitor."
    exit 1
fi

# Install deps if needed
pip install grpcio protobuf rich 2>/dev/null || pip3 install grpcio protobuf rich 2>/dev/null

# Run the Rich monitor
$PYTHON "$SCRIPT_DIR/monitor.py"
