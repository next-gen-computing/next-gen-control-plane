#!/usr/bin/env python3
"""
Maps a locally-supplied cluster-trace CSV file into this project's own JSONL training schema, so
external data can be blended into train_risk_model.py's training set via --external.

IMPORTANT — read before using:
This script does NOT download anything. It holds no BigQuery/Aliyun OSS credentials and cannot be
pointed at a bucket URL or a query — bulk multi-GB retrieval of Google's or Alibaba's public cluster
traces is out of scope for this tool. Download the trace files yourself (see each dataset's own
documentation) and place them under data/external_traces/<source>/ (gitignored), then run this script
against the local file(s).

Usage:
    python import_external_trace.py --source google-2019 \\
        --input /path/to/data/external_traces/google-2019 --output /path/to/imported/google-2019.jsonl

Supported --source values and their expected columns are declared in SCHEMA_MAP below. Every column
name is marked TODO verify — cluster-trace schemas differ across releases (Google's 2011 traces are not
the same shape as the 2019 ones; Alibaba's 2017 traces are not the same shape as the 2018 ones), and no
sample file was available to verify against when this script was written. Check your actual file's
header row before trusting the mapping, and update SCHEMA_MAP accordingly.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import logging
import os
import sys

LOG = logging.getLogger("import_external_trace")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")

# Each entry maps this project's needs to a column name in the source CSV, and the raw value that means
# "this row represents a real failure/eviction" for the failure_column/failure_value pair.
# TODO verify every column name below against your actual file's header row before first use — these
# are documentation-recalled column names, not verified against a real sample file.
SCHEMA_MAP = {
    "google-2019": {
        "node_id_column": "machine_id",
        "cpu_column": "average_usage.cpus",
        "memory_column": "average_usage.memory",
        "timestamp_column": "start_time",
        "failure_column": "failed",
        "failure_value": "1",
    },
    "google-2011": {
        "node_id_column": "machine_id",
        "cpu_column": "mean_cpu_usage_rate",
        "memory_column": "canonical_mem_usage",
        "timestamp_column": "timestamp",
        "failure_column": "event_type",
        "failure_value": "5",
    },
    "alibaba-2018": {
        "node_id_column": "machine_id",
        "cpu_column": "cpu_util_percent",
        "memory_column": "mem_util_percent",
        "timestamp_column": "time_stamp",
        "failure_column": "failure",
        "failure_value": "1",
    },
    "alibaba-2017": {
        "node_id_column": "machine_id",
        "cpu_column": "cpu_util_percent",
        "memory_column": "mem_util_percent",
        "timestamp_column": "time_stamp",
        "failure_column": "failure",
        "failure_value": "1",
    },
}


def find_input_files(input_path: str) -> list[str]:
    if os.path.isdir(input_path):
        return sorted(glob.glob(os.path.join(input_path, "*.csv")))
    return [input_path]


def convert_row(row: dict, schema: dict) -> dict | None:
    """
    Maps one CSV row into a single-sample training record, or None if the row is missing a column this
    schema needs — skipped rather than guessed.

    A single-sample `recentHistory` is a real but minimal trend window — reconstructing a true
    multi-sample trend per node (like NodeHistory's rolling window) would need grouping consecutive rows
    by node id, which is a per-source aggregation step intentionally left to the operator once real
    files are in hand, not attempted generically here across every trace format's different row order
    and granularity.
    """
    try:
        node_id = row[schema["node_id_column"]]
        cpu = float(row[schema["cpu_column"]])
        memory = float(row[schema["memory_column"]])
        timestamp = float(row[schema["timestamp_column"]])
        is_failure = row.get(schema["failure_column"], "") == schema["failure_value"]
    except (KeyError, ValueError):
        return None

    sample = {
        "recordedAtMillis": int(timestamp),
        "cpuPercent": cpu, "cpuAvailable": True,
        "memoryPercent": memory, "memoryAvailable": True,
        "batteryPercent": 100.0, "batteryAvailable": False,
        "charging": False, "chargingKnown": False,
        "onAcPower": True, "onAcPowerKnown": False,
        "previousRttSeconds": 0.0, "previousRttAvailable": False,
    }
    return {
        "nodeId": node_id,
        "snapshotAtMillis": int(timestamp),
        "recentHistory": [sample],
        "label": 1 if is_failure else 0,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, choices=sorted(SCHEMA_MAP.keys()))
    parser.add_argument("--input", required=True, help="a local CSV file, or a directory of them")
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    schema = SCHEMA_MAP[args.source]
    input_files = find_input_files(args.input)
    if not input_files:
        LOG.error("No CSV files found at %s", args.input)
        return 1

    output_dir = os.path.dirname(os.path.abspath(args.output))
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    written = 0
    skipped = 0
    with open(args.output, "w", encoding="utf-8") as out:
        for path in input_files:
            LOG.info("Reading %s", path)
            with open(path, "r", encoding="utf-8", newline="") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    record = convert_row(row, schema)
                    if record is None:
                        skipped += 1
                        continue
                    out.write(json.dumps(record) + "\n")
                    written += 1

    LOG.info("Wrote %d record(s), skipped %d malformed/incomplete row(s) -> %s", written, skipped, args.output)
    if skipped > 0:
        LOG.warning("A non-zero skip count often means the column names in SCHEMA_MAP['%s'] don't match "
                    "your actual file's header row — check it and update SCHEMA_MAP.", args.source)
    return 0


if __name__ == "__main__":
    sys.exit(main())
