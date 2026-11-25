"""Lifecycle settings, all from environment variables (LIFECYCLE_*), with conservative defaults.

Database access uses the standard libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD), so
no connection URL with an embedded password is ever built or logged.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, fields


@dataclass(frozen=True)
class LifecycleConfig:
    enabled: bool = False
    tick_seconds: int = 300                  # how often the background loop wakes up
    readiness_interval_h: float = 6.0        # readiness / (re)training check frequency
    monitor_interval_h: float = 1.0          # live-performance check frequency
    history_days: int = 60                   # most recent history loaded for training
    min_stations: int = 10                   # stations with good coverage required to train
    min_retrain_interval_h: float = 168.0    # no retraining sooner than this after a completed training ...
    min_new_hours: int = 72                  # ... and only once this much new data has arrived
    max_retrain_interval_h: float = 720.0    # retrain anyway after this long (if there is any new data)
    failure_backoff_h: float = 1.0           # retry delay after a failed/interrupted run, doubled each time
    failure_backoff_max_h: float = 24.0
    max_consecutive_failures: int = 3        # then wait for new data before trying again
    origin_stride: int = 6                   # training uses every 6th origin hour (memory bound)
    max_train_rows: int = 1_000_000
    models: str = "random_forest,lightgbm"
    monitor_window_h: int = 168              # rolling evaluation window on data newer than the training data
    monitor_min_rows: int = 2000             # below this, monitoring takes no action
    monitor_min_stations: int = 10
    monitor_min_new_hours: int = 48
    degradation_margin: float = 0.05         # model MAE worse than persistence by more than 5% ...
    degradation_consecutive: int = 2         # ... on this many consecutive checks -> rollback
    failure_window_min: int = 60             # prediction-failure rule: in the last N minutes ...
    failure_min_requests: int = 20           # ... at least this many model predictions ...
    failure_rate_threshold: float = 0.5      # ... and this share failed -> fall back

    @staticmethod
    def from_env() -> "LifecycleConfig":
        values = {}
        for f in fields(LifecycleConfig):
            raw = os.getenv(f"LIFECYCLE_{f.name.upper()}")
            if raw is None or raw == "":
                continue
            if f.type in ("bool", bool):
                values[f.name] = raw.strip().lower() in ("1", "true", "yes", "on")
            elif f.type in ("int", int):
                values[f.name] = int(raw)
            elif f.type in ("float", float):
                values[f.name] = float(raw)
            else:
                values[f.name] = raw
        return LifecycleConfig(**values)


def database_configured() -> bool:
    return bool(os.getenv("PGHOST"))
