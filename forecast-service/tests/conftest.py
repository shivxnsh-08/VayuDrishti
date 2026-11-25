"""Synthetic data for tests. It is NOT real AQI data and no result from it should be reported."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest


def synthetic_hourly(days: int = 30, stations: tuple[str, ...] = ("s1", "s2", "s3"), seed: int = 7,
                     drift_amp: float = 30.0) -> pd.DataFrame:
    """Daily cycle + slow multi-day drift + noise, stamped at :30 UTC like IST hours.

    With the default small drift, the 7-day same-hour mean baseline is near-optimal by construction, so whether a
    model beats it is a coin flip. A larger ``drift_amp`` (multi-day pollution build-ups/clean-outs) is structure a
    real forecaster should exploit - recent level plus daily shape - while a 7-day mean lags behind it.
    """
    rng = np.random.default_rng(seed)
    idx = pd.date_range("2026-01-01 00:30", periods=days * 24, freq="h", tz="UTC")
    frames = []
    for i, sid in enumerate(stations):
        hours = np.arange(len(idx))
        base = 180 + 40 * i
        daily = 80 * np.sin(2 * np.pi * (hours % 24) / 24)
        drift = drift_amp * np.sin(2 * np.pi * hours / (24 * 9))
        aqi = np.clip(base + daily + drift + rng.normal(0, 8, len(idx)), 0, 500)
        frames.append(pd.DataFrame({"station_id": sid, "ts": idx, "aqi": aqi.round()}))
    return pd.concat(frames, ignore_index=True)


@pytest.fixture
def hourly_df() -> pd.DataFrame:
    return synthetic_hourly()
