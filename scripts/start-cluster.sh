#!/usr/bin/env bash
# ─────────────────────────────────────────────────────
# start-cluster.sh — Starts the Next-Gen Control Plane cluster
# Usage: ./scripts/start-cluster.sh
# ─────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "══════════════════════════════════════════════════"
echo "  🚀 Starting Next-Gen Control Plane Cluster"
echo "══════════════════════════════════════════════════"

cd "$ROOT_DIR"

echo "📦 Building and starting services..."
docker compose up --build -d

echo ""
echo "⏳ Waiting for services to start..."
sleep 5

echo ""
echo "📊 Service status:"
docker compose ps

echo ""
echo "══════════════════════════════════════════════════"
echo "  ✅ Cluster is running!"
echo "  • ControlPlane gRPC: localhost:50051"
echo "  • Predictor gRPC:    localhost:50052"
echo "  • Prometheus (CP):   localhost:9090/metrics"
echo "  • Prometheus (Pred): localhost:9091/metrics"
echo ""
echo "  View logs:    docker compose logs -f"
echo "  Stop cluster: docker compose down"
echo "══════════════════════════════════════════════════"
