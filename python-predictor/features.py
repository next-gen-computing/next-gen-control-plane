"""
Feature engineering shared between training (train_risk_model.py) and serving (predictor_service.py).

Deliberately reimplements the same trend logic as RuleBasedRiskScorer.java (rising RTT, memory-pressure
trend) — no code is shared between the Java and Python sides, so these two must be kept hand-in-sync.
Cross-reference RuleBasedRiskScorerTest's case names for the exact behavior each function below must
match:
  - low-battery-on-battery cases            -> is_on_low_battery
  - rising/flat/falling RTT cases           -> has_rising_rtt
  - memory-pressure / decreasing-memory     -> has_memory_pressure

All functions take samples shaped exactly like the JSONL RiskOutcomeLogger/RiskSnapshotLogger write on
the Java side (and predictor_service.py builds the same shape from a real PredictionRequest's
TrendSample list) — oldest first, keys: recordedAtMillis, cpuPercent, cpuAvailable, memoryPercent,
memoryAvailable, batteryPercent, batteryAvailable, charging, chargingKnown, onAcPower, onAcPowerKnown,
previousRttSeconds, previousRttAvailable.
"""

from __future__ import annotations

import numpy as np

LOW_BATTERY_THRESHOLD_PERCENT = 15.0
MEMORY_CEILING_PERCENT = 90.0
TREND_WINDOW = 5

FEATURE_NAMES = [
    "battery_percent",
    "on_battery",
    "rtt_rising",
    "rtt_last_minus_first",
    "memory_pressure",
    "memory_percent",
    "cpu_percent",
    "history_length",
    # Stage H: added for the XGBoost path — gradient-boosted trees benefit from more raw signal than a
    # linear model needs. The logistic-regression path shares this same extract_features function, so a
    # model retrained after this change simply gets more inputs; no pipeline fork.
    "cpu_mean",
    "cpu_max",
    "memory_mean",
    "memory_max",
    "rtt_slope",
    "staleness_millis",
]


def _last_n(samples: list[dict], n: int = TREND_WINDOW) -> list[dict]:
    return samples[-n:] if len(samples) > n else samples


def is_on_low_battery(latest: dict) -> bool:
    return (
        latest.get("batteryAvailable", False)
        and latest.get("onAcPowerKnown", False)
        and not latest.get("onAcPower", True)
        and latest.get("batteryPercent", 100.0) < LOW_BATTERY_THRESHOLD_PERCENT
    )


def has_rising_rtt(samples: list[dict]) -> bool:
    window = _last_n(samples)
    if len(window) < TREND_WINDOW:
        return False
    previous = float("-inf")
    for sample in window:
        if not sample.get("previousRttAvailable", False):
            return False  # an honest trend needs every point real — a gap breaks it, not skips it
        rtt = sample.get("previousRttSeconds", 0.0)
        if rtt <= previous:
            return False
        previous = rtt
    return True


def has_memory_pressure(samples: list[dict], latest: dict) -> bool:
    if not latest.get("memoryAvailable", False) or latest.get("memoryPercent", 0.0) < MEMORY_CEILING_PERCENT:
        return False
    window = _last_n(samples)
    if len(window) < TREND_WINDOW:
        return False
    previous = float("-inf")
    for sample in window:
        if not sample.get("memoryAvailable", False):
            return False
        mem = sample.get("memoryPercent", 0.0)
        if mem < previous:
            return False  # dropped — not monotonically non-decreasing
        previous = mem
    return True


def _rolling_stat(window: list[dict], value_key: str, available_key: str, stat) -> float:
    values = [s[value_key] for s in window if s.get(available_key, False)]
    return float(stat(values)) if values else 0.0


def rtt_trend_slope(samples: list[dict]) -> float:
    """Least-squares slope of previousRttSeconds over the trend window, using sample position as x —
    a richer trend signal than rtt_last_minus_first's endpoints-only diff. 0.0 if fewer than 2 available
    points, matching rtt_last_minus_first's existing convention for insufficient data."""
    window = _last_n(samples)
    points = [(i, s["previousRttSeconds"]) for i, s in enumerate(window) if s.get("previousRttAvailable", False)]
    if len(points) < 2:
        return 0.0
    xs = np.array([p[0] for p in points], dtype=np.float64)
    ys = np.array([p[1] for p in points], dtype=np.float64)
    denom = float(np.sum((xs - xs.mean()) ** 2))
    if denom == 0.0:
        return 0.0
    return float(np.sum((xs - xs.mean()) * (ys - ys.mean())) / denom)


def staleness_millis(samples: list[dict]) -> float:
    """Gap between the two most-recent samples' recordedAtMillis. Deliberately NOT wall-clock
    "now minus latest" — that would make this feature irreproducible for offline training over
    historical JSONL rows, since it would depend on when training happens to run rather than on the
    data itself. A large inter-sample gap here is itself a real signal (irregular/delayed reporting)."""
    if len(samples) < 2:
        return 0.0
    return float(samples[-1].get("recordedAtMillis", 0) - samples[-2].get("recordedAtMillis", 0))


def extract_features(recent_samples: list[dict]) -> np.ndarray:
    """Projects a trend window (oldest first, possibly empty) into a fixed-size numeric feature vector.

    @raises ValueError if the resulting vector contains a NaN/Inf value. A proto float field is not
    range-checked by gRPC itself, so a malformed or malicious PredictionRequest could otherwise put a
    NaN anywhere in recent_samples and have it flow, unguarded, through standardization straight into a
    confidently-WRONG verdict: Python's `float("nan") >= 0.5` evaluates False, so an unguarded caller
    would silently report "HEALTHY" instead of the honest untrained fallback a missing model already
    gets. See model_store.predict's catch of this exact exception.
    """
    latest = recent_samples[-1] if recent_samples else {}

    battery_percent = latest.get("batteryPercent", 100.0) if latest.get("batteryAvailable", False) else 100.0
    on_battery = 1.0 if (latest.get("onAcPowerKnown", False) and not latest.get("onAcPower", True)) else 0.0
    rtt_rising = 1.0 if has_rising_rtt(recent_samples) else 0.0

    window = _last_n(recent_samples)
    rtts = [s["previousRttSeconds"] for s in window if s.get("previousRttAvailable", False)]
    rtt_last_minus_first = (rtts[-1] - rtts[0]) if len(rtts) >= 2 else 0.0

    memory_pressure = 1.0 if has_memory_pressure(recent_samples, latest) else 0.0
    memory_percent = latest.get("memoryPercent", 0.0) if latest.get("memoryAvailable", False) else 0.0
    cpu_percent = latest.get("cpuPercent", 0.0) if latest.get("cpuAvailable", False) else 0.0
    history_length = float(min(len(recent_samples), TREND_WINDOW))

    # A malformed/malicious input (e.g. an infinite previousRttSeconds, exercised by
    # test_extract_features_rejects_an_infinite_rtt) can make an intermediate computation below produce
    # NaN/Inf arithmetic (e.g. inf - inf) — expected and harmless, since np.isfinite(vector) below is the
    # single authoritative check that rejects it either way. Suppress numpy's transient warning about it.
    with np.errstate(invalid="ignore", over="ignore"):
        cpu_mean = _rolling_stat(window, "cpuPercent", "cpuAvailable", np.mean)
        cpu_max = _rolling_stat(window, "cpuPercent", "cpuAvailable", np.max)
        memory_mean = _rolling_stat(window, "memoryPercent", "memoryAvailable", np.mean)
        memory_max = _rolling_stat(window, "memoryPercent", "memoryAvailable", np.max)
        rtt_slope = rtt_trend_slope(recent_samples)
    staleness = staleness_millis(recent_samples)

    vector = np.array([
        battery_percent, on_battery, rtt_rising, rtt_last_minus_first,
        memory_pressure, memory_percent, cpu_percent, history_length,
        cpu_mean, cpu_max, memory_mean, memory_max, rtt_slope, staleness,
    ], dtype=np.float64)
    if not np.all(np.isfinite(vector)):
        raise ValueError("feature vector contains a non-finite value (NaN/Inf) — rejecting")
    return vector
