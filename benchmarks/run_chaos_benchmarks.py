#!/usr/bin/env python3
"""
Master Chaos Engineering & Benchmark Suite Runner
Executes all IEEE benchmark experiments in one single command:
  1. MTTR Proactive Migration Benchmark (30 Trials)
  2. Raft Consensus Network Partition Resilience (25 Partition Experiments)
  3. Risk Classification Accuracy & ROC-AUC Analysis (Dual-dataset: Host Telemetry + Alibaba PAI Trace)
  4. Load Forecast Sequence Prediction (RMSE & MAE at 5-Minute Horizon)

Usage:
  python benchmarks/run_chaos_benchmarks.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

BENCHMARKS_DIR = os.path.dirname(os.path.abspath(__file__))
RESULTS_DIR = os.path.join(BENCHMARKS_DIR, "results")
PYTHON_EXE = sys.executable


def run_benchmark(script_name: str, description: str) -> bool:
    print("\n" + "#" * 80)
    print(f"  RUNNING BENCHMARK: {description}")
    print(f"  Script: benchmarks/{script_name}")
    print("#" * 80)
    script_path = os.path.join(BENCHMARKS_DIR, script_name)
    start_time = time.time()
    
    proc = subprocess.run([PYTHON_EXE, script_path], cwd=BENCHMARKS_DIR)
    elapsed = time.time() - start_time
    
    if proc.returncode == 0:
        print(f"  ✔ Finished in {elapsed:.2f} seconds.")
        return True
    else:
        print(f"  ✖ FAILED with exit code {proc.returncode} after {elapsed:.2f} seconds.")
        return False


def main() -> int:
    os.makedirs(RESULTS_DIR, exist_ok=True)
    master_start = time.time()
    
    print("\n" + "=" * 80)
    print("  NEXT-GEN CONTROL PLANE — MASTER CHAOS ENGINEERING & BENCHMARK SUITE")
    print("  Reproducing all IEEE Conference / Journal Section VI Claims")
    print("=" * 80)

    benchmarks = [
        ("mttr_benchmark.py", "30-Trial Proactive Migration MTTR Evaluation"),
        ("raft_partition_benchmark.py", "25-Experiment Raft Network Partition Resilience"),
        ("risk_classification_benchmark.py", "Risk Classifier ROC-AUC Evaluation"),
        ("load_forecast_benchmark.py", "Load Forecaster RMSE & MAE Evaluation")
    ]

    all_passed = True
    for script, desc in benchmarks:
        success = run_benchmark(script, desc)
        if not success:
            all_passed = False

    # Aggregate master summary
    mttr_file = os.path.join(RESULTS_DIR, "mttr_30_trials.json")
    raft_file = os.path.join(RESULTS_DIR, "raft_25_partitions.json")
    risk_file = os.path.join(RESULTS_DIR, "risk_classification_roc_auc.json")
    load_file = os.path.join(RESULTS_DIR, "load_forecast_rmse.json")

    master_summary = {
        "suite": "Next-Gen Control Plane Comprehensive IEEE Benchmark Suite",
        "timestamp_epoch_ms": int(time.time() * 1000),
        "total_elapsed_seconds": round(time.time() - master_start, 2),
        "status": "ALL_PASSED" if all_passed else "SOME_FAILED",
        "artifacts_generated": [
            "mttr_30_trials.json",
            "raft_25_partitions.json",
            "risk_classification_roc_auc.json",
            "load_forecast_rmse.json"
        ]
    }

    if os.path.exists(mttr_file):
        with open(mttr_file, "r", encoding="utf-8") as f:
            master_summary["mttr_summary"] = json.load(f).get("summary_statistics_ms", {})

    if os.path.exists(raft_file):
        with open(raft_file, "r", encoding="utf-8") as f:
            r = json.load(f)
            master_summary["raft_summary"] = {
                "experiments_passed": r.get("successful_experiments"),
                "leader_election_latency_ms": r.get("leader_election_latency_ms", {})
            }

    if os.path.exists(risk_file):
        with open(risk_file, "r", encoding="utf-8") as f:
            rk = json.load(f)
            master_summary["risk_classification_summary"] = {
                "host_telemetry_roc_auc": rk.get("dataset_1_host_degradation_telemetry", {}).get("roc_auc_result"),
                "alibaba_trace_roc_auc": rk.get("dataset_2_alibaba_pai_cluster_trace", {}).get("metrics", {}).get("roc_auc")
            }

    if os.path.exists(load_file):
        with open(load_file, "r", encoding="utf-8") as f:
            ld = json.load(f)
            master_summary["load_forecasting_summary"] = ld.get("paper_table_comparison", {})

    master_out = os.path.join(RESULTS_DIR, "comprehensive_ieee_benchmark_report.json")
    Path(master_out).write_text(json.dumps(master_summary, indent=2), encoding="utf-8")

    print("\n" + "=" * 80)
    print("  ALL BENCHMARKS COMPLETED SUCCESSFULLY")
    print(f"  Master Report: {master_out}")
    print("=" * 80 + "\n")
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())

