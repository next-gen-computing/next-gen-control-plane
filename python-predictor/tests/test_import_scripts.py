"""
Stage BB: exit-code hygiene for the external-trace import scripts — both previously exited 0 even when
every input row was skipped as unusable, which is a real gap for any cron/automation checking the exit
code alone (real risk is low since train_risk_model.py's own MIN_TRAINING_EXAMPLES floor independently
refuses to train on a near-empty dataset, but the exit code itself should still be honest).
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import import_alibaba_pai_trace  # noqa: E402
import import_external_trace  # noqa: E402


def test_zero_usable_rows_exits_non_zero(tmp_path):
    # A CSV whose header names don't match SCHEMA_MAP['google-2019'] at all — every row fails to
    # convert (KeyError on the expected column names) and is skipped.
    input_csv = tmp_path / "trace.csv"
    input_csv.write_text("unrelated_column,another_column\nfoo,bar\n")
    output_path = tmp_path / "out.jsonl"

    exit_code = import_external_trace.main([
        "--source", "google-2019",
        "--input", str(input_csv),
        "--output", str(output_path),
    ])

    assert exit_code == 1


def test_at_least_one_usable_row_exits_zero(tmp_path):
    schema = import_external_trace.SCHEMA_MAP["google-2019"]
    header = [schema["node_id_column"], schema["cpu_column"], schema["memory_column"],
              schema["timestamp_column"], schema["failure_column"]]
    input_csv = tmp_path / "trace.csv"
    input_csv.write_text(
        ",".join(header) + "\n"
        + "machine-1,0.5,0.3,1000,0\n"
    )
    output_path = tmp_path / "out.jsonl"

    exit_code = import_external_trace.main([
        "--source", "google-2019",
        "--input", str(input_csv),
        "--output", str(output_path),
    ])

    assert exit_code == 0
    assert output_path.read_text().strip() != ""


def test_a_missing_machine_metric_file_exits_cleanly_not_a_raw_traceback(tmp_path):
    """Stage EE: pd.read_csv was previously unguarded — a missing --machine-metric path raised a raw,
    uncaught FileNotFoundError instead of the same clean error/exit-1 every other missing-input case
    in this project's import scripts gets."""
    missing_path = str(tmp_path / "does_not_exist.csv")
    instance_table = tmp_path / "instances.csv"
    instance_table.write_text("inst_id,status,start_time,end_time,machine\n1,Terminated,0,10,m1\n")
    output_path = tmp_path / "out.jsonl"

    exit_code = import_alibaba_pai_trace.main([
        "--machine-metric", missing_path,
        "--instance-table", str(instance_table),
        "--output", str(output_path),
    ])

    assert exit_code == 1


def test_a_missing_instance_table_file_exits_cleanly_not_a_raw_traceback(tmp_path):
    machine_metric = tmp_path / "machine_metric.csv"
    machine_metric.write_text("machine,start_time,machine_cpu_usr,machine_cpu_kernel\nm1,0,10.0,5.0\n")
    missing_instance_table = str(tmp_path / "does_not_exist.csv")
    output_path = tmp_path / "out.jsonl"

    exit_code = import_alibaba_pai_trace.main([
        "--machine-metric", str(machine_metric),
        "--instance-table", missing_instance_table,
        "--output", str(output_path),
    ])

    assert exit_code == 1
