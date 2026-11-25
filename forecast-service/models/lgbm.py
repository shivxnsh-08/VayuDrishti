"""LightGBM quantile models (10th, 50th, 90th percentile).

* The median (q50) is the point forecast: it minimises absolute error, matching the MAE metric.
* q10/q90 give an 80% range. Coverage is checked in the backtest (experiment X5) rather than assumed.
* Feature groups can be dropped for the ablation experiment (X4).
"""
from __future__ import annotations

from pathlib import Path

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd

from features.build import CALENDAR_COLS, SEASONAL_COLS, FreqConfig, lag_cols, rolling_cols

QUANTILES = (0.1, 0.5, 0.9)

DEFAULT_PARAMS = {
    "n_estimators": 400,
    "learning_rate": 0.05,
    "num_leaves": 31,
    "min_child_samples": 20,
    "subsample": 0.8,
    "subsample_freq": 1,
    "colsample_bytree": 0.9,
    "verbose": -1,
}


def feature_groups(cfg: FreqConfig) -> dict[str, list[str]]:
    return {
        "lags": lag_cols(cfg),
        "rolling": rolling_cols(cfg),
        "calendar": list(CALENDAR_COLS),
        "seasonal": list(SEASONAL_COLS),
        "horizon": ["horizon"],
        "station": ["station_id"],
    }


def feature_columns(cfg: FreqConfig, drop_groups: tuple[str, ...] = ()) -> list[str]:
    unknown = set(drop_groups) - set(feature_groups(cfg))
    if unknown:
        raise ValueError(f"unknown feature groups: {sorted(unknown)}")
    return [c for g, cols in feature_groups(cfg).items() if g not in drop_groups for c in cols]


class QuantileModel:
    def __init__(self, columns: list[str], params: dict | None = None, quantiles=QUANTILES):
        self.columns = columns
        self.params = {**DEFAULT_PARAMS, **(params or {})}
        self.quantiles = tuple(quantiles)
        self.station_categories: list[str] = []
        self.models: dict[float, lgb.LGBMRegressor] = {}

    def _matrix(self, rows: pd.DataFrame) -> pd.DataFrame:
        x = rows[self.columns].copy()
        if "station_id" in x:
            # Fixed category list: unseen stations become NaN (LightGBM treats as missing).
            x["station_id"] = pd.Categorical(x["station_id"], categories=self.station_categories)
        return x

    def fit(self, rows: pd.DataFrame) -> "QuantileModel":
        train = rows[rows["y"].notna()]
        if train.empty:
            raise ValueError("no training rows with a known target")
        if "station_id" in self.columns:
            self.station_categories = sorted(train["station_id"].astype(str).unique())
        x = self._matrix(train)
        for q in self.quantiles:
            m = lgb.LGBMRegressor(objective="quantile", alpha=q, **self.params)
            m.fit(x, train["y"].to_numpy())
            self.models[q] = m
        return self

    def predict(self, rows: pd.DataFrame) -> pd.DataFrame:
        x = self._matrix(rows)
        preds = np.column_stack([self.models[q].predict(x) for q in self.quantiles])
        preds = np.sort(preds, axis=1)   # quantile models are fitted separately and can cross
        return pd.DataFrame(preds, columns=[f"q{int(q * 100)}" for q in self.quantiles], index=rows.index)

    def save(self, path: Path) -> None:
        joblib.dump(self, path)

    @staticmethod
    def load(path: Path) -> "QuantileModel":
        return joblib.load(path)
