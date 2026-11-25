"""Baselines (spec: "must exist first"). Each is a column already present in the feature rows,
so baselines and the model are always evaluated on exactly the same rows.

* persistence            y_hat(t+h) = y(t)
* same_hour_yesterday    y_hat(t+h) = y(t+h-24h)   (daily data: same weekday last week)
* same_hour_7d_mean      mean of the same hour over the last 7 days (daily data: last 4 weeks)
"""
from __future__ import annotations

import numpy as np
import pandas as pd

BASELINES: dict[str, str] = {
    "persistence": "lag_0",
    "same_hour_yesterday": "seasonal_1",
    "same_hour_7d_mean": "seasonal_mean",
}


def baseline_predictions(rows: pd.DataFrame) -> pd.DataFrame:
    return pd.DataFrame({name: rows[col].to_numpy() for name, col in BASELINES.items()}, index=rows.index)


def empirical_interval(series: pd.Series, horizon: int, quantiles=(0.1, 0.9), min_samples: int = 10):
    """Range for a baseline forecast from the series' own history: quantiles of the change over
    ``horizon`` steps. Returns (None, None) when there is too little history to say anything."""
    changes = (series.shift(-horizon) - series).dropna()
    if len(changes) < min_samples:
        return None, None
    lo, hi = np.quantile(changes.to_numpy(), quantiles)
    return float(lo), float(hi)
