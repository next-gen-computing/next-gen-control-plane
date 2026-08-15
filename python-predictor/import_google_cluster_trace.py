#!/usr/bin/env python3
"""
Converts the real Google ClusterData2019 (Borg cluster trace v3) — downloaded directly from the public
`clusterdata_2019_a` GCS bucket over plain HTTPS, no BigQuery/registration needed
(https://storage.googleapis.com/clusterdata_2019_a/<table>-<shard>.parquet.gz) — into this project's own
--external JSONL schema (one {"recentHistory": [...], "label": 0|1} per line), so train_risk_model.py can
train on it directly via --external, exactly like import_alibaba_pai_trace.py.

A purpose-built script, not a generic importer, for the same reason import_alibaba_pai_trace.py is: this
trace is relational (machine departure events live in machine_events, per-task resource usage lives in
instance_usage, joined by machine_id) and this project's own schema needs a real join, not a column
rename.

What this maps, and — important — the one real semantic gap this trace has that Alibaba's didn't:
  - Ground truth label: machine_events' `type` field. type=2 (REMOVE) is used as label=1. Verified by
    directly inspecting the real downloaded data (not assumed from documentation, which does not define
    this precisely): of the 10,001 distinct machines in cell "a", 9,650 (96.5%) have BOTH ADD and REMOVE
    events, most in repeating ADD->REMOVE->ADD->REMOVE cycles. This means REMOVE in this trace is a
    routine, frequently-repeating "this machine's resources became unavailable" event, NOT a rare
    catastrophic-failure signal the way Alibaba's Failed/Interrupted instance status was. The schema
    genuinely does not distinguish a hardware/software failure from a scheduled maintenance cycling —
    there is no separate "reason" field, and this was confirmed by attempting to reach the detailed
    trace-format documentation (a Google-Drive-hosted PDF that isn't fetchable headlessly) and, when that
    failed, inspecting the actual event sequences directly instead of guessing.
    This is used anyway, deliberately: this project's actual use case (ProactiveMigrator) is "move work
    off a node before it stops being available, for any reason," not narrowly "predict hardware failure"
    — a REMOVE event is a real instance of exactly that condition, whatever its cause. Labeled honestly
    as a broader "resource departure" signal, not overclaimed as "failure."
  - The trend window: instance_usage's real per-instance `average_usage.cpus`/`.memory` (Resources
    message: cpus = normalized compute units (NCUs), memory = normalized RAM bytes), summed across every
    instance placed on a machine within each 5-minute bucket to approximate that machine's own total
    load — the same "sum of concurrently-active workers" honest-approximation technique
    enrich_alibaba_memory.py already uses for Alibaba's memory feature, not a true instantaneous
    per-machine reading (none exists in this trace at the machine level).
  - Normalization to a 0-100 percentage: machine_events' `capacity` field (also a Resources message) is
    only populated on some events for a given machine (empirically: present on the REMOVE/UPDATE rows
    sampled, None on the ADD rows sampled) — the last-known non-null capacity for a machine_id is used
    as the normalizing denominator. A machine with no capacity ever observed is honestly marked
    cpuAvailable/memoryAvailable=False for that sample rather than guessing a percentage.
  - What is deliberately NOT fabricated: no battery/AC-power/RTT signal exists in this trace either (a
    fixed datacenter Borg cluster, same as Alibaba) — every sample marks those fields unavailable exactly
    as import_alibaba_pai_trace.py already does.

Memory-bounded by design: machine_events for one cell is small (~46K rows, loaded whole); instance_usage
is processed one downloaded shard (Parquet, ~36MB each) at a time, never all shards loaded simultaneously.

Usage:
    python import_google_cluster_trace.py \\
        --machine-events ../datasets/google_cluster_trace/machine_events.parquet.gz \\
        --instance-usage-glob "../datasets/google_cluster_trace/instance_usage_*.parquet" \\
        --output google_risk_examples.jsonl
"""

from __future__ import annotations

import argparse
import bisect
import glob
import json
import logging
import os
import random
import sys
from collections import defaultdict

import pandas as pd

LOG = logging.getLogger("import_google_cluster_trace")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")

TREND_WINDOW = 5
MIN_SAMPLES_REQUIRED = 3
BUCKET_SECONDS = 300  # 5-minute buckets when summing concurrent instance usage per machine
NEGATIVE_HORIZON_SECONDS = 3600  # a "stable" negative sample must be at least this far from any REMOVE


def load_machine_events(path: str) -> tuple[dict[int, list[float]], dict[int, dict[str, float]]]:
    """Real REMOVE timestamps per machine (seconds since trace start) and the last-known real capacity
    per machine, both read directly from machine_events — no synthetic values."""
    df = pd.read_parquet(path)
    removes: dict[int, list[float]] = defaultdict(list)
    capacity: dict[int, dict[str, float]] = {}

    df = df.sort_values("time")
    for machine_id, t, event_type, cap in zip(df["machine_id"], df["time"], df["type"], df["capacity"]):
        if event_type == 2:  # REMOVE
            removes[int(machine_id)].append(float(t) / 1_000_000.0)
        if cap is not None and cap.get("cpus") and cap.get("memory"):
            capacity[int(machine_id)] = {"cpus": float(cap["cpus"]), "memory": float(cap["memory"])}

    for machine_id in removes:
        removes[machine_id].sort()
    LOG.info("Loaded %d machines with at least one REMOVE event; real capacity known for %d machines",
              len(removes), len(capacity))
    return dict(removes), capacity


def load_usage_series(shard_paths: list[str]) -> dict[int, list[tuple[float, float, float]]]:
    """Real per-machine (time_bucket_seconds, summed_cpus_ncu, summed_memory_normalized) series, built by
    summing every instance's average_usage placed on that machine within each 5-minute bucket — the sum
    approximates that machine's own total concurrent load, the same honest technique already used for
    Alibaba's memory feature."""
    buckets: dict[tuple[int, int], list[float]] = defaultdict(lambda: [0.0, 0.0])
    rows_seen = 0
    for shard_path in shard_paths:
        df = pd.read_parquet(shard_path, columns=["start_time", "machine_id", "average_usage"])
        for start_time, machine_id, usage in zip(df["start_time"], df["machine_id"], df["average_usage"]):
            if usage is None:
                continue
            bucket = int(start_time // 1_000_000) // BUCKET_SECONDS
            key = (int(machine_id), bucket)
            entry = buckets[key]
            entry[0] += float(usage.get("cpus") or 0.0)
            entry[1] += float(usage.get("memory") or 0.0)
            rows_seen += 1
        LOG.info("  ...%s: %d rows folded in (%d distinct machine/bucket pairs so far)",
                  os.path.basename(shard_path), rows_seen, len(buckets))

    series: dict[int, list[tuple[float, float, float]]] = defaultdict(list)
    for (machine_id, bucket), (cpus, mem) in buckets.items():
        series[machine_id].append((float(bucket * BUCKET_SECONDS), cpus, mem))
    for machine_id in series:
        series[machine_id].sort(key=lambda row: row[0])
    LOG.info("Built usage series for %d distinct machines from %d real instance-usage rows",
              len(series), rows_seen)
    return dict(series)


def samples_before(series: list[tuple[float, float, float]], cutoff: float, base_millis: int,
                    capacity: dict[str, float] | None) -> list[dict]:
    """Real recorded samples for one machine, oldest-first, ending at (not after) `cutoff` seconds —
    same output shape import_alibaba_pai_trace.py's samples_before() produces."""
    times = [t for t, _, _ in series]
    idx = bisect.bisect_right(times, cutoff)
    window = series[max(0, idx - TREND_WINDOW):idx]

    samples = []
    for t, cpus_ncu, mem_norm in window:
        if capacity and capacity.get("cpus"):
            cpu_pct = min(100.0, 100.0 * cpus_ncu / capacity["cpus"])
            cpu_available = True
        else:
            cpu_pct = 0.0
            cpu_available = False
        if capacity and capacity.get("memory"):
            mem_pct = min(100.0, 100.0 * mem_norm / capacity["memory"])
            mem_available = True
        else:
            mem_pct = 0.0
            mem_available = False

        samples.append({
            "recordedAtMillis": base_millis + int(t * 1000),
            "cpuPercent": cpu_pct,
            "cpuAvailable": cpu_available,
            "memoryPercent": mem_pct,
            "memoryAvailable": mem_available,
            "batteryPercent": 100.0,
            "batteryAvailable": False,
            "charging": False,
            "chargingKnown": False,
            "onAcPower": True,
            "onAcPowerKnown": False,
            "previousRttSeconds": 0.0,
            "previousRttAvailable": False,
        })
    return samples


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--machine-events", required=True)
    parser.add_argument("--instance-usage-glob", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--max-positives", type=int, default=15_000)
    parser.add_argument("--max-negatives", type=int, default=15_000)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
    removes, capacity = load_machine_events(args.machine_events)

    shard_paths = sorted(glob.glob(args.instance_usage_glob))
    if not shard_paths:
        LOG.error("No instance-usage shards matched %s", args.instance_usage_glob)
        return 1
    LOG.info("Reading %d real instance-usage shards...", len(shard_paths))
    usage_series = load_usage_series(shard_paths)

    base_millis = 1_600_000_000_000  # same arbitrary-but-consistent anchor import_alibaba_pai_trace.py uses

    # Positive candidates: every real REMOVE event for a machine we have a usage series for.
    positive_candidates: list[tuple[int, float]] = []
    for machine_id, remove_times in removes.items():
        if machine_id not in usage_series:
            continue
        for t in remove_times:
            positive_candidates.append((machine_id, t))
    rng.shuffle(positive_candidates)

    # Negative candidates: real usage-series timestamps for a machine, kept only if genuinely far from
    # every one of that machine's own REMOVE events — a real "this machine stayed available" moment, not
    # a guess.
    negative_candidates: list[tuple[int, float]] = []
    for machine_id, series in usage_series.items():
        remove_times = removes.get(machine_id, [])
        for t, _, _ in series:
            if all(abs(t - r) > NEGATIVE_HORIZON_SECONDS for r in remove_times):
                negative_candidates.append((machine_id, t))
    rng.shuffle(negative_candidates)

    positives_written = 0
    negatives_written = 0
    skipped_thin_window = 0

    with open(args.output, "w", encoding="utf-8") as out:
        for machine_id, cutoff in positive_candidates:
            if positives_written >= args.max_positives:
                break
            history = samples_before(usage_series[machine_id], cutoff, base_millis, capacity.get(machine_id))
            if len(history) < MIN_SAMPLES_REQUIRED:
                skipped_thin_window += 1
                continue
            out.write(json.dumps({
                "nodeId": f"gcp-machine-{machine_id}",
                "outcomeStatus": "REMOVE",
                "label": 1,
                "recentHistory": history,
            }) + "\n")
            positives_written += 1

        for machine_id, cutoff in negative_candidates:
            if negatives_written >= args.max_negatives:
                break
            history = samples_before(usage_series[machine_id], cutoff, base_millis, capacity.get(machine_id))
            if len(history) < MIN_SAMPLES_REQUIRED:
                skipped_thin_window += 1
                continue
            out.write(json.dumps({
                "nodeId": f"gcp-machine-{machine_id}",
                "outcomeStatus": "STABLE",
                "label": 0,
                "recentHistory": history,
            }) + "\n")
            negatives_written += 1

    LOG.info("Done. Wrote %d examples (%d positive / %d negative) to %s",
              positives_written + negatives_written, positives_written, negatives_written, args.output)
    LOG.info("Skipped %d candidates with fewer than %d real samples in window", skipped_thin_window,
              MIN_SAMPLES_REQUIRED)
    return 0


if __name__ == "__main__":
    sys.exit(main())
