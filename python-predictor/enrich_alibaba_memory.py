#!/usr/bin/env python3
"""
Enriches the JSONL import_alibaba_pai_trace.py already produced with a real machine-level
memory-pressure trend — the feature that first pass left honestly marked unavailable.

Why this is a separate pass, not folded into the first script: pai_sensor_table.csv turned out (verified
by direct inspection — see the two-column duplicate check run before this was written) to hold exactly
one lifetime-average reading per (inst_id, worker_name), not a time series the way pai_machine_metric.csv
is. There is no such thing as "this worker's memory at time T" in this trace. What IS real and
computable: at any timestamp T, which workers were actually running on a given machine (from
pai_instance_table.csv's start_time/end_time), and what each of their own real avg_mem readings was — sum
those and divide by the machine's real cap_mem (pai_machine_spec.csv) for a genuine, if approximate,
"how much of this machine's memory was in use around time T" percentage. Confirmed against Alibaba's own
schema docs (github.com/alibaba/clusterdata, cluster-trace-gpu-v2020/README.md) before writing this:
avg_mem and cap_mem are both real GB values, not normalized fractions — the percentage this produces is
a real unit conversion, not a guessed one.

What this deliberately does NOT claim: this is concurrent-workers'-own-average summed, not a true
instantaneous machine-wide reading (which the trace doesn't contain at all) — a real, named
approximation, not hidden as if it were exact telemetry.

Usage:
    python enrich_alibaba_memory.py \\
        --sensor-table   ../datasets/archive/pai_sensor_table.csv \\
        --instance-table ../datasets/archive/pai_instance_table.csv \\
        --machine-spec   ../datasets/archive/pai_machine_spec.csv \\
        --input          alibaba_risk_examples.jsonl \\
        --output         alibaba_risk_examples_with_memory.jsonl
"""

from __future__ import annotations

import argparse
import json
import logging
import sys

import numpy as np
import pandas as pd

LOG = logging.getLogger("enrich_alibaba_memory")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")

CHUNK_ROWS = 500_000
INFINITY = float("inf")


def load_worker_avg_mem(path: str) -> dict[str, float]:
    LOG.info("Pass A: reading per-worker avg_mem from %s...", path)
    worker_mem: dict[str, float] = {}
    rows_seen = 0
    for chunk in pd.read_csv(path, usecols=["worker_name", "avg_mem"], chunksize=CHUNK_ROWS):
        rows_seen += len(chunk)
        chunk = chunk.dropna(subset=["worker_name", "avg_mem"])
        for worker, mem in zip(chunk["worker_name"], chunk["avg_mem"]):
            worker_mem[worker] = float(mem)
        LOG.info("  ...%d rows read, %d workers with a real avg_mem so far", rows_seen, len(worker_mem))
    LOG.info("Pass A done: %d workers with a real memory reading", len(worker_mem))
    return worker_mem


def load_machine_cap_mem(path: str) -> dict[str, float]:
    df = pd.read_csv(path, usecols=["machine", "cap_mem"]).dropna()
    return dict(zip(df["machine"], df["cap_mem"].astype(float)))


def build_machine_intervals(instance_table_path: str, worker_mem: dict[str, float],
                             relevant_machines: set[str]) -> dict[str, dict[str, np.ndarray]]:
    """machine -> {starts, ends, mems} as numpy arrays, restricted to machines this run's examples
    actually reference (relevant_machines) — the same real inst_id/worker rows either way, just not
    wasting memory building interval data for machines nothing downstream will ever query."""
    LOG.info("Pass B: joining instance timing against Pass A's memory readings...")
    raw: dict[str, list[tuple[float, float, float]]] = {}
    rows_seen = 0
    matched = 0
    usecols = ["worker_name", "start_time", "end_time", "machine"]
    for chunk in pd.read_csv(instance_table_path, usecols=usecols, chunksize=CHUNK_ROWS):
        rows_seen += len(chunk)
        chunk = chunk.dropna(subset=["worker_name", "start_time", "machine"])
        chunk = chunk[chunk["machine"].isin(relevant_machines)]
        for worker, start, end, machine in zip(
                chunk["worker_name"], chunk["start_time"], chunk["end_time"], chunk["machine"]):
            mem = worker_mem.get(worker)
            if mem is None:
                continue
            # A still-running worker (no end_time) genuinely is still consuming memory at any query
            # time after it started — treated as open-ended, not dropped and not zero-filled.
            end_val = float(end) if pd.notna(end) else INFINITY
            raw.setdefault(machine, []).append((float(start), end_val, mem))
            matched += 1
        LOG.info("  ...%d instance rows scanned, %d joined to a real memory reading", rows_seen, matched)

    LOG.info("Pass B done: %d machines have interval data (%d joined workers)", len(raw), matched)
    intervals: dict[str, dict[str, np.ndarray]] = {}
    for machine, rows in raw.items():
        starts = np.array([r[0] for r in rows], dtype=np.float64)
        ends = np.array([r[1] for r in rows], dtype=np.float64)
        mems = np.array([r[2] for r in rows], dtype=np.float64)
        intervals[machine] = {"starts": starts, "ends": ends, "mems": mems}
    return intervals


def memory_percent_at(intervals: dict[str, np.ndarray] | None, cap_mem: float | None, t: float) -> float | None:
    if intervals is None or not cap_mem:
        return None
    active = (intervals["starts"] <= t) & (intervals["ends"] >= t)
    if not active.any():
        return 0.0
    total_gb = float(intervals["mems"][active].sum())
    return min(100.0, (total_gb / cap_mem) * 100.0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sensor-table", required=True)
    parser.add_argument("--instance-table", required=True)
    parser.add_argument("--machine-spec", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    LOG.info("Reading existing examples from %s...", args.input)
    examples = []
    relevant_machines: set[str] = set()
    with open(args.input, encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            examples.append(row)
            relevant_machines.add(row["nodeId"])
    LOG.info("Loaded %d examples across %d distinct machines", len(examples), len(relevant_machines))

    worker_mem = load_worker_avg_mem(args.sensor_table)
    cap_mem_by_machine = load_machine_cap_mem(args.machine_spec)
    intervals_by_machine = build_machine_intervals(args.instance_table, worker_mem, relevant_machines)
    del worker_mem  # Pass C only needs the per-machine interval arrays from here on.

    LOG.info("Pass C: computing real memory-pressure trend for each example's history window...")
    enriched_with_memory = 0
    for row in examples:
        machine = row["nodeId"]
        intervals = intervals_by_machine.get(machine)
        cap_mem = cap_mem_by_machine.get(machine)
        had_any = False
        for sample in row["recentHistory"]:
            t = (sample["recordedAtMillis"] - 1_600_000_000_000) / 1000.0  # invert the same anchor used to build it
            pct = memory_percent_at(intervals, cap_mem, t)
            if pct is not None:
                sample["memoryPercent"] = pct
                sample["memoryAvailable"] = True
                had_any = True
        if had_any:
            enriched_with_memory += 1

    with open(args.output, "w", encoding="utf-8") as out:
        for row in examples:
            out.write(json.dumps(row) + "\n")

    LOG.info("Done. %d/%d examples got a real memory reading on at least one sample. Wrote %s",
              enriched_with_memory, len(examples), args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
