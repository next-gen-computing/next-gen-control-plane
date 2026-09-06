"""
Unit tests for features.py — the Python-side reimplementation of RuleBasedRiskScorer.java's trend
logic. Case names cross-reference RuleBasedRiskScorerTest's own cases, per features.py's module
docstring on why the two must be kept in sync by hand.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from features import (  # noqa: E402
    FEATURE_NAMES,
    extract_features,
    has_memory_pressure,
    has_rising_rtt,
    is_on_low_battery,
    rtt_trend_slope,
    staleness_millis,
)


def sample(**overrides):
    base = {
        "recordedAtMillis": 0,
        "cpuPercent": 10.0, "cpuAvailable": True,
        "memoryPercent": 50.0, "memoryAvailable": True,
        "batteryPercent": 100.0, "batteryAvailable": True,
        "charging": True, "chargingKnown": True,
        "onAcPower": True, "onAcPowerKnown": True,
        "previousRttSeconds": 0.01, "previousRttAvailable": True,
    }
    base.update(overrides)
    return base


# ── is_on_low_battery ────────────────────────────────────────────────────

def test_low_battery_on_battery_triggers_risk():
    latest = sample(batteryPercent=10.0, onAcPower=False)
    assert is_on_low_battery(latest) is True


def test_battery_above_threshold_does_not_trigger():
    latest = sample(batteryPercent=50.0, onAcPower=False)
    assert is_on_low_battery(latest) is False


def test_low_battery_while_on_ac_power_does_not_trigger():
    latest = sample(batteryPercent=5.0, onAcPower=True)
    assert is_on_low_battery(latest) is False


def test_low_battery_with_unknown_ac_state_does_not_trigger():
    latest = sample(batteryPercent=5.0, onAcPowerKnown=False)
    assert is_on_low_battery(latest) is False


def test_unavailable_battery_does_not_trigger():
    latest = sample(batteryAvailable=False, batteryPercent=1.0, onAcPower=False)
    assert is_on_low_battery(latest) is False


# ── has_rising_rtt ───────────────────────────────────────────────────────

def test_rising_rtt_over_the_full_window_triggers_risk():
    window = [sample(previousRttSeconds=v) for v in [0.01, 0.02, 0.03, 0.04, 0.05]]
    assert has_rising_rtt(window) is True


def test_flat_rtt_does_not_trigger():
    window = [sample(previousRttSeconds=0.02) for _ in range(5)]
    assert has_rising_rtt(window) is False


def test_falling_rtt_does_not_trigger():
    window = [sample(previousRttSeconds=v) for v in [0.05, 0.04, 0.03, 0.02, 0.01]]
    assert has_rising_rtt(window) is False


def test_a_gap_in_rtt_availability_breaks_the_trend_rather_than_skipping_it():
    window = [sample(previousRttSeconds=v) for v in [0.01, 0.02, 0.03, 0.04]]
    window.append(sample(previousRttSeconds=0.05, previousRttAvailable=False))
    assert has_rising_rtt(window) is False


def test_fewer_than_the_trend_window_samples_does_not_trigger():
    window = [sample(previousRttSeconds=v) for v in [0.01, 0.02, 0.03]]
    assert has_rising_rtt(window) is False


# ── has_memory_pressure ──────────────────────────────────────────────────

def test_memory_pressure_triggers_risk():
    window = [sample(memoryPercent=v) for v in [90.0, 91.0, 92.0, 93.0, 95.0]]
    assert has_memory_pressure(window, window[-1]) is True


def test_decreasing_memory_does_not_trigger():
    window = [sample(memoryPercent=v) for v in [95.0, 93.0, 92.0, 91.0, 95.0]]
    assert has_memory_pressure(window, window[-1]) is False


def test_memory_below_ceiling_does_not_trigger_even_if_rising():
    window = [sample(memoryPercent=v) for v in [10.0, 20.0, 30.0, 40.0, 50.0]]
    assert has_memory_pressure(window, window[-1]) is False


def test_unavailable_memory_does_not_trigger():
    latest = sample(memoryAvailable=False, memoryPercent=95.0)
    assert has_memory_pressure([latest], latest) is False


# ── extract_features ─────────────────────────────────────────────────────

def test_extract_features_on_empty_history_does_not_crash_and_uses_safe_defaults():
    vector = extract_features([])
    assert vector.shape == (len(FEATURE_NAMES),)
    assert vector[0] == 100.0  # battery_percent default: unavailable -> full, never a fabricated low value


def test_extract_features_reflects_a_genuinely_risky_node():
    window = [sample(memoryPercent=v, batteryPercent=8.0, onAcPower=False) for v in
              [90.0, 91.0, 92.0, 93.0, 95.0]]
    vector = extract_features(window)
    # battery_percent, on_battery, rtt_rising, rtt_delta, memory_pressure, memory_percent, cpu, history_len, ...
    assert vector[0] == 8.0
    assert vector[1] == 1.0  # on_battery
    assert vector[4] == 1.0  # memory_pressure
    assert vector[5] == 95.0  # memory_percent


# ── Stage BB: NaN/Inf rejection ───────────────────────────

def test_extract_features_rejects_a_nan_cpu_percent():
    window = [sample(cpuPercent=float("nan"))]
    with pytest.raises(ValueError):
        extract_features(window)


def test_extract_features_rejects_a_nan_memory_percent():
    window = [sample(memoryPercent=float("nan"))]
    with pytest.raises(ValueError):
        extract_features(window)


def test_extract_features_rejects_an_infinite_rtt():
    window = [sample(previousRttSeconds=v) for v in [1.0, 2.0, float("inf"), 4.0, 5.0]]
    with pytest.raises(ValueError):
        extract_features(window)


# ── Stage H: rolling mean/max, rtt_trend_slope, staleness_millis ─────────

def test_rolling_mean_and_max_reflect_the_full_window():
    window = [sample(cpuPercent=v, memoryPercent=v + 10) for v in [10.0, 20.0, 30.0, 40.0, 50.0]]
    vector = extract_features(window)
    names = {name: i for i, name in enumerate(FEATURE_NAMES)}
    assert vector[names["cpu_mean"]] == 30.0
    assert vector[names["cpu_max"]] == 50.0
    assert vector[names["memory_mean"]] == 40.0
    assert vector[names["memory_max"]] == 60.0


def test_rolling_mean_and_max_skip_unavailable_samples():
    window = [
        sample(cpuPercent=10.0, cpuAvailable=True),
        sample(cpuPercent=999.0, cpuAvailable=False),
        sample(cpuPercent=30.0, cpuAvailable=True),
    ]
    vector = extract_features(window)
    names = {name: i for i, name in enumerate(FEATURE_NAMES)}
    assert vector[names["cpu_mean"]] == 20.0
    assert vector[names["cpu_max"]] == 30.0


def test_rolling_mean_and_max_default_to_zero_when_nothing_available():
    window = [sample(cpuAvailable=False)]
    vector = extract_features(window)
    names = {name: i for i, name in enumerate(FEATURE_NAMES)}
    assert vector[names["cpu_mean"]] == 0.0
    assert vector[names["cpu_max"]] == 0.0


def test_rtt_trend_slope_is_positive_for_a_rising_trend():
    window = [sample(previousRttSeconds=v) for v in [0.01, 0.02, 0.03, 0.04, 0.05]]
    assert rtt_trend_slope(window) > 0.0


def test_rtt_trend_slope_is_negative_for_a_falling_trend():
    window = [sample(previousRttSeconds=v) for v in [0.05, 0.04, 0.03, 0.02, 0.01]]
    assert rtt_trend_slope(window) < 0.0


def test_rtt_trend_slope_is_zero_for_a_flat_trend():
    window = [sample(previousRttSeconds=0.02) for _ in range(5)]
    assert rtt_trend_slope(window) == 0.0


def test_rtt_trend_slope_is_zero_with_fewer_than_two_available_points():
    window = [sample(previousRttSeconds=0.02, previousRttAvailable=False) for _ in range(5)]
    assert rtt_trend_slope(window) == 0.0


def test_staleness_millis_is_the_gap_between_the_two_most_recent_samples():
    window = [sample(recordedAtMillis=1000), sample(recordedAtMillis=1000), sample(recordedAtMillis=7500)]
    assert staleness_millis(window) == 6500.0


def test_staleness_millis_is_zero_with_fewer_than_two_samples():
    assert staleness_millis([sample(recordedAtMillis=1000)]) == 0.0
    assert staleness_millis([]) == 0.0
