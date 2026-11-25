"""Random Forest direct multi-horizon model with split-conformal prediction intervals.

* Point forecast: RandomForestRegressor over the same feature rows as LightGBM (one model, horizon is
  a feature). scikit-learn >= 1.4 handles missing feature values natively, so early-history NaNs need no
  imputation.
* Station: encoded as an integer code from a fixed category list (unseen stations -> NaN).
* Intervals: split conformal. After fitting, ``calibrate`` takes a LATER validation window the model never
  saw and stores, per horizon, the 10th and 90th percentiles of the residuals (y - prediction). The
  interval is prediction + those offsets. Coverage is then measured on the separate test window by
  train.py and stored with the model; nothing about coverage is assumed.

``predict`` returns columns q10, q50, q90 like ``QuantileModel`` so the service can use either model.
"""
from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor

DEFAULT_PARAMS = {
    "n_estimators": 200,
    "min_samples_leaf": 5,
    "max_features": 0.5,
    "n_jobs": -1,
    "random_state": 42,   # reproducible
}


class RandomForestModel:
    name = "random_forest"

    def __init__(self, columns: list[str], params: dict | None = None):
        self.columns = columns
        self.params = {**DEFAULT_PARAMS, **(params or {})}
        self.station_categories: list[str] = []
        self.model: RandomForestRegressor | None = None
        self.offsets: dict[int, tuple[float, float]] = {}   # horizon -> (q10 residual, q90 residual)
        self.coverage_target = 0.8

    def _matrix(self, rows: pd.DataFrame) -> np.ndarray:
        x = rows[self.columns].copy()
        if "station_id" in x:
            x["station_id"] = pd.Categorical(x["station_id"], categories=self.station_categories).codes.astype(float)
            x.loc[x["station_id"] < 0, "station_id"] = np.nan
        return x.to_numpy(dtype=float)

    def fit(self, rows: pd.DataFrame) -> "RandomForestModel":
        train = rows[rows["y"].notna()]
        if train.empty:
            raise ValueError("no training rows with a known target")
        if "station_id" in self.columns:
            self.station_categories = sorted(train["station_id"].astype(str).unique())
        self.model = RandomForestRegressor(**self.params)
        self.model.fit(self._matrix(train), train["y"].to_numpy())
        return self

    def point(self, rows: pd.DataFrame) -> np.ndarray:
        if self.model is None:
            raise RuntimeError("model is not fitted")
        return self.model.predict(self._matrix(rows))

    def calibrate(self, rows: pd.DataFrame, coverage: float = 0.8, min_per_horizon: int = 30) -> dict[int, int]:
        """Split-conformal offsets per horizon from a held-out window. Returns residual counts per horizon.
        Horizons with fewer than ``min_per_horizon`` residuals get no interval (NaN), not a guess."""
        cal = rows[rows["y"].notna()]
        resid = cal["y"].to_numpy() - self.point(cal)
        lo_q, hi_q = (1 - coverage) / 2, 1 - (1 - coverage) / 2
        self.coverage_target = coverage
        self.offsets, counts = {}, {}
        for h, idx in cal.groupby("horizon").indices.items():
            counts[int(h)] = len(idx)
            if len(idx) >= min_per_horizon:
                r = resid[idx]
                self.offsets[int(h)] = (float(np.quantile(r, lo_q)), float(np.quantile(r, hi_q)))
        return counts

    def predict(self, rows: pd.DataFrame) -> pd.DataFrame:
        p = self.point(rows)
        lo = np.array([self.offsets.get(int(h), (np.nan, np.nan))[0] for h in rows["horizon"]])
        hi = np.array([self.offsets.get(int(h), (np.nan, np.nan))[1] for h in rows["horizon"]])
        q10, q90 = p + lo, p + hi
        # keep the point inside its own interval
        q10 = np.where(np.isnan(q10), np.nan, np.minimum(q10, p))
        q90 = np.where(np.isnan(q90), np.nan, np.maximum(q90, p))
        return pd.DataFrame({"q10": q10, "q50": p, "q90": q90}, index=rows.index)

    def save(self, path: Path) -> None:
        joblib.dump(self, path)

    @staticmethod
    def load(path: Path) -> "RandomForestModel":
        return joblib.load(path)
