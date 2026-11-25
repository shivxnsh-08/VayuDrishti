"""Live performance of the active model on data it has never seen.

Rows are built from history with the normal feature pipeline; only origins AFTER the model's training data
ended and within the last ``monitor_window_h`` hours, with a known actual, are scored. The predictions scored
are what the API serves (the model, or the station's baseline where the model lost on its test window), and
they are compared with persistence on exactly the same rows.
"""
from __future__ import annotations

from datetime import datetime

import numpy as np
import pandas as pd

from features.build import HOURLY, build_rows
from features.cleaning import regularize
from lifecycle.config import LifecycleConfig
from models.baselines import BASELINES


def served_predictions(model, meta: dict, rows: pd.DataFrame) -> np.ndarray:
    pred = model.predict(rows)["q50"].to_numpy()
    fallback = meta.get("use_baseline", {}) or {}
    if fallback:
        for station, baseline in fallback.items():
            mask = (rows["station_id"] == station).to_numpy()
            if mask.any():
                pred[mask] = rows.loc[mask, BASELINES[baseline]].to_numpy()
    return pred


def recent_rows(history: pd.DataFrame, trained_until, now: datetime, cfg: LifecycleConfig) -> pd.DataFrame:
    """Rows with origins after the training data ended and inside the rolling window. ``history`` should
    reach back ``monitor_window_h`` + 168 h so that lag features are populated."""
    regular = regularize(history, HOURLY.freq, HOURLY.max_gap)
    since = max(pd.Timestamp(trained_until), pd.Timestamp(now) - pd.Timedelta(hours=cfg.monitor_window_h))
    rows = build_rows(regular, HOURLY, origin_stride=cfg.origin_stride, origin_after=since)
    if rows.empty:
        return rows
    return rows[rows["y"].notna() & rows["lag_0"].notna()]


def recent_performance(model, meta: dict, history: pd.DataFrame, now: datetime, cfg: LifecycleConfig) -> dict:
    trained_until = (meta.get("dataset") or {}).get("last_ts")
    if not trained_until:
        return {"sufficient": False, "reason": "active model has no training-data end time"}
    rows = recent_rows(history, trained_until, now, cfg)
    stations = int(rows["station_id"].nunique()) if len(rows) else 0
    # hours of new data covered (origins are strided, so measure the span, not the count)
    new_hours = int((rows["origin_ts"].max() - rows["origin_ts"].min()) / pd.Timedelta(hours=1)) + 1 if len(rows) else 0
    base = {"rows": int(len(rows)), "stations": stations, "new_hours": new_hours, "trained_until": str(trained_until)}
    short = []
    if len(rows) < cfg.monitor_min_rows:
        short.append(f"{len(rows)} rows < {cfg.monitor_min_rows}")
    if stations < cfg.monitor_min_stations:
        short.append(f"{stations} stations < {cfg.monitor_min_stations}")
    if new_hours < cfg.monitor_min_new_hours:
        short.append(f"{new_hours} new hours < {cfg.monitor_min_new_hours}")
    if short:
        return {**base, "sufficient": False, "reason": "; ".join(short)}
    pred = served_predictions(model, meta, rows)
    y = rows["y"].to_numpy()
    return {**base, "sufficient": True, "reason": "ok",
            "model_mae": float(np.mean(np.abs(pred - y))),
            "persistence_mae": float(np.mean(np.abs(rows["lag_0"].to_numpy() - y)))}


def mae_on_same_rows(model, meta: dict, history: pd.DataFrame, trained_until, now: datetime,
                     cfg: LifecycleConfig) -> float | None:
    """MAE of another model (e.g. the rollback candidate) on the same recent window."""
    rows = recent_rows(history, trained_until, now, cfg)
    if rows.empty:
        return None
    return float(np.mean(np.abs(served_predictions(model, meta, rows) - rows["y"].to_numpy())))
