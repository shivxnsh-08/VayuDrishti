"""Inference for POST /forecast.

Method choice, per request (spec "Honesty"):
1. the promoted LightGBM model, unless validation showed a baseline was better at this station;
2. otherwise that baseline (or persistence when nothing else is known).
Ranges: model -> q10/q90; baseline -> empirical quantiles of past h-step changes.
"""
from __future__ import annotations

from datetime import datetime, timezone

import numpy as np
import pandas as pd

from app.model_store import ModelStore
from app.schemas import ForecastRequest, ForecastResponse, Prediction
from features.build import HOURLY, horizon_rows
from features.cleaning import AQI_MAX, regularize
from models.baselines import BASELINES, empirical_interval


def _clip(v: float) -> int:
    return int(round(min(max(v, 0), AQI_MAX)))


def make_forecast(req: ForecastRequest, store: ModelStore, now: datetime | None = None,
                  min_points: int = 24) -> ForecastResponse:
    cfg = HOURLY
    raw = pd.DataFrame([{"station_id": req.station_id, "ts": r.ts, "aqi": r.aqi} for r in req.readings])
    # Same readiness rule as the backend: enough distinct, plausible hourly observations.
    # Counted on the raw input, before any gap filling, so imputed values never count.
    valid = raw[(raw["aqi"] >= 0) & (raw["aqi"] <= AQI_MAX)]
    distinct_hours = pd.to_datetime(valid["ts"], utc=True).dt.floor("h").nunique()
    if distinct_hours < min_points:
        raise ValueError(f"insufficient history: {distinct_hours} valid hourly observations, need {min_points}")
    regular = regularize(raw, cfg.freq, cfg.max_gap)
    series = regular.set_index("ts")["aqi"].astype(float)
    origin = series.last_valid_index()
    if origin is None:
        raise ValueError("no valid readings")
    series = series.loc[:origin]

    horizons = list(range(1, req.horizon_hours + 1))
    rows = horizon_rows(series, cfg, with_target=False, horizons=horizons)
    rows = rows[rows["origin_ts"] == origin].copy()
    rows.insert(0, "station_id", req.station_id)

    store.refresh()   # pick up a promotion/rollback without a restart
    baseline = store.baseline_for(req.station_id)
    if store.model is not None and baseline is None:
        q = store.model.predict(rows)
        preds, lows, highs = q["q50"].to_numpy(), q["q10"].to_numpy(), q["q90"].to_numpy()
        method, version = "MODEL", store.version
    else:
        name = baseline or "persistence"
        col = BASELINES[name]
        if rows[col].isna().any():          # e.g. not enough history for "same hour yesterday"
            name, col = "persistence", BASELINES["persistence"]
        preds = rows[col].to_numpy()
        bounds = [empirical_interval(series, h) for h in horizons]
        lows = np.array([p + b[0] if b[0] is not None else np.nan for p, b in zip(preds, bounds)])
        highs = np.array([p + b[1] if b[1] is not None else np.nan for p, b in zip(preds, bounds)])
        method, version = f"BASELINE_{name.upper()}", "baseline"

    predictions = []
    for ts, p, lo, hi in zip(rows["target_ts"], preds, lows, highs):
        point = _clip(p)
        lower = None if np.isnan(lo) else min(_clip(lo), point)
        upper = None if np.isnan(hi) else max(_clip(hi), point)
        predictions.append(Prediction(target_ts=ts.to_pydatetime(), predicted_aqi=point, lower=lower, upper=upper))

    return ForecastResponse(
        station_id=req.station_id,
        generated_at=now or datetime.now(timezone.utc),
        origin_ts=origin.to_pydatetime(),
        model_version=version,
        method=method,
        predictions=predictions,
    )
