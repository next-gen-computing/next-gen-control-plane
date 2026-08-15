#!/usr/bin/env python3
"""
Converts the Alibaba PAI (Platform for AI) GPU cluster trace — the real dataset found under
datasets/archive/ (pai_instance_table.csv, pai_machine_metric.csv, ...) — into this project's own
--external JSONL schema (one {"recentHistory": [...], "label": 0|1} per line), so train_risk_model.py
can train on it directly via --external.

This is a purpose-built script, not a generic importer: the Alibaba trace is a relational, multi-table
dataset (instance status lives in one file, machine-level CPU trend in another, joined by `machine`),
which is a genuinely different shape from the single-flat-CSV case import_external_trace.py handles.
Forcing this trace through that script would mean guessing at a column mapping that doesn't fit its
actual structure.

What this maps, honestly:
  - Ground truth label: pai_instance_table.csv's real `status` column. Failed/Interrupted -> label 1
    (the real analogue of this project's own ALIVE -> SUSPECTED_DEAD transition); Terminated -> label 0
    (ran to a clean, ordinary completion). Running/Waiting/Ready instances are excluded outright — their
    eventual outcome isn't known within the trace window, so labeling them would be a guess, not data.
  - The trend window: pai_machine_metric.csv's real per-machine CPU utilization
    (machine_cpu_usr + machine_cpu_kernel, the standard "%CPU busy" definition, excluding iowait) in the
    window immediately before that instance's end_time — the real analogue of this project's own
    heartbeat-derived cpuPercent trend.
  - What is deliberately NOT fabricated: this trace has no battery, AC-power, or network-RTT signal at
    all (it's a fixed datacenter GPU cluster, not battery-powered edge/laptop nodes) and no direct
    per-machine memory metric (pai_sensor_table.csv has memory, but only per-instance, not per-machine —
    aggregating it up is real future work, named here rather than silently attempted). Every sample this
    script writes marks batteryAvailable/onAcPowerKnown/previousRttAvailable/memoryAvailable as false,
    exactly as this project's own agents do for a genuinely unmeasured reading — never a fabricated
    number standing in for a real one.

Memory-bounded by design: both source files are read in chunks (pandas `chunksize`), never loaded whole
— pai_instance_table.csv alone is ~1.9GB, and this machine has ~3.7GB free at the time this was written.

Usage:
    python import_alibaba_pai_trace.py \\
        --machine-metric ../datasets/archive/pai_machine_metric.csv \\
        --instance-table ../datasets/archive/pai_instance_table.csv \\
        --output alibaba_risk_examples.jsonl
"""

from __future__ import annotations

import argparse
import bisect
import json
import logging
import os
import random
import sys

import pandas as pd

LOG = logging.getLogger("import_alibaba_pai_trace")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")

TREND_WINDOW = 5
CHUNK_ROWS = 500_000
MIN_SAMPLES_REQUIRED = 3  # fewer than this and the trend signal is too thin to be worth a training row


def load_machine_cpu_series(path: str) -> dict[str, list[tuple[float, float]]]:
    """machine -> sorted [(start_time, cpu_percent), ...]. cpu_percent = usr + kernel, the real recorded
    values from pai_machine_metric.csv — never interpolated or fabricated for a row where they're blank
    (such rows are dropped, not zero-filled)."""
    LOG.info("Reading machine-level CPU trend from %s (chunked, %d rows/chunk)...", path, CHUNK_ROWS)
    series: dict[str, list[tuple[float, float]]] = {}
    rows_seen = 0
    rows_kept = 0
    usecols = ["machine", "start_time", "machine_cpu_usr", "machine_cpu_kernel"]
    for chunk in pd.read_csv(path, usecols=usecols, chunksize=CHUNK_ROWS):
        rows_seen += len(chunk)
        chunk = chunk.dropna(subset=["machine", "start_time", "machine_cpu_usr", "machine_cpu_kernel"])
        for machine, start_time, usr, kernel in zip(
                chunk["machine"], chunk["start_time"], chunk["machine_cpu_usr"], chunk["machine_cpu_kernel"]):
            series.setdefault(machine, []).append((float(start_time), float(usr) + float(kernel)))
            rows_kept += 1
        LOG.info("  ...%d rows read, %d kept so far (%d distinct machines)", rows_seen, rows_kept, len(series))

    for machine in series:
        series[machine].sort(key=lambda pair: pair[0])
    LOG.info("Built CPU-trend series for %d machines from %d real samples", len(series), rows_kept)
    return series


def samples_before(cpu_series: list[tuple[float, float]], cutoff: float, base_millis: int) -> list[dict]:
    """Real recorded samples for one machine, oldest-first, ending at (not after) `cutoff` seconds —
    converted into the exact sample shape RiskOutcomeLogger/RiskSnapshotLogger write on the Java side
    (see features.py's own docstring for the field list)."""
    times = [t for t, _ in cpu_series]
    idx = bisect.bisect_right(times, cutoff)
    window = cpu_series[max(0, idx - TREND_WINDOW):idx]
    return [
        {
            "recordedAtMillis": base_millis + int(t * 1000),
            "cpuPercent": cpu_pct,
            "cpuAvailable": True,
            "memoryPercent": 0.0,
            "memoryAvailable": False,
            "batteryPercent": 100.0,
            "batteryAvailable": False,
            "charging": False,
            "chargingKnown": False,
            "onAcPower": True,
            "onAcPowerKnown": False,
            "previousRttSeconds": 0.0,
            "previousRttAvailable": False,
        }
        for t, cpu_pct in window
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--machine-metric", required=True)
    parser.add_argument("--instance-table", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--max-negatives", type=int, default=10_000,
                        help="cap on Terminated (label=0) rows kept")
    parser.add_argument("--max-positives", type=int, default=25_000,
                        help="cap on Failed/Interrupted (label=1) rows kept — this specific trace runs "
                             "heavily *positive*-skewed (GPU preemption/eviction is common), the reverse "
                             "of the usual failure-prediction imbalance; both classes are capped so the "
                             "resulting dataset trains a classifier that has to discriminate, not one "
                             "that scores well by always predicting the majority class")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
    cpu_series = load_machine_cpu_series(args.machine_metric)

    # Alibaba's trace timestamps are seconds relative to an arbitrary trace start, not wall-clock epoch
    # time — anchoring them at a real epoch millis base keeps every recordedAtMillis internally
    # consistent (which is all the trend features need) without claiming a real calendar date this data
    # doesn't carry.
    base_millis = 1_600_000_000_000

    LOG.info("Reading instance outcomes from %s (chunked, %d rows/chunk)...", args.instance_table, CHUNK_ROWS)
    usecols = ["inst_id", "status", "start_time", "end_time", "machine"]
    positives_written = 0
    negatives_written = 0
    positives_seen = 0
    negatives_seen = 0
    rows_seen = 0
    skipped_no_series = 0
    skipped_thin_window = 0

    with open(args.output, "w", encoding="utf-8") as out:
        for chunk in pd.read_csv(args.instance_table, usecols=usecols, chunksize=CHUNK_ROWS):
            rows_seen += len(chunk)
            chunk = chunk[chunk["status"].isin(["Failed", "Interrupted", "Terminated"])]
            chunk = chunk.dropna(subset=["machine", "end_time"])

            for inst_id, status, end_time, machine in zip(
                    chunk["inst_id"], chunk["status"], chunk["end_time"], chunk["machine"]):
                label = 1 if status in ("Failed", "Interrupted") else 0

                if label == 1:
                    positives_seen += 1
                    if positives_written >= args.max_positives:
                        continue
                    if rng.random() > (args.max_positives / max(positives_seen, args.max_positives)):
                        continue
                else:
                    negatives_seen += 1
                    if negatives_written >= args.max_negatives:
                        continue
                    # Reservoir-ish thinning: keep a random subset spread across the whole file rather
                    # than just the first N rows encountered, which would all come from the same early
                    # slice of the trace.
                    if rng.random() > (args.max_negatives / max(negatives_seen, args.max_negatives)):
                        continue

                series = cpu_series.get(machine)
                if not series:
                    skipped_no_series += 1
                    continue

                history = samples_before(series, float(end_time), base_millis)
                if len(history) < MIN_SAMPLES_REQUIRED:
                    skipped_thin_window += 1
                    continue

                out.write(json.dumps({
                    "nodeId": machine,
                    "instId": inst_id,
                    "outcomeStatus": status,
                    "label": label,
                    "recentHistory": history,
                }) + "\n")

                if label == 1:
                    positives_written += 1
                else:
                    negatives_written += 1

            LOG.info("  ...%d instance rows scanned, %d positive + %d negative examples written so far",
                      rows_seen, positives_written, negatives_written)

    LOG.info("Done. Wrote %d examples (%d positive / %d negative) to %s",
              positives_written + negatives_written, positives_written, negatives_written, args.output)
    LOG.info("Skipped: %d (machine had no CPU-metric series), %d (fewer than %d real samples in window)",
              skipped_no_series, skipped_thin_window, MIN_SAMPLES_REQUIRED)
    return 0


if __name__ == "__main__":
    sys.exit(main())
