"""Feature engineering for direct multi-horizon forecasting.

Design: one row per (station, origin time t, horizon h). Features use only values at or
before t; the target is y(t + h). One LightGBM model learns all horizons, with ``horizon`` as
a feature ("direct" strategy). Compared with one model per horizon it shares data across
horizons; compared with recursive forecasting it does not feed its own errors back in.

Leakage rule: every feature is ``series.shift(k)`` with k >= 0 relative to the origin.
The seasonal features look at the *target's* hour on previous days, y(t + h - P*k); because
h <= P (horizon never exceeds the seasonal period) that is always at or before t.
``tests/test_features.py`` checks this by corrupting future values.
"""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd


@dataclass(frozen=True)
class FreqConfig:
    name: str
    freq: str                 # pandas frequency of the grid
    step: pd.Timedelta        # one step
    horizon: int              # number of steps to forecast
    lags: tuple[int, ...]     # lag_k = y(t - k)
    windows: tuple[int, ...]  # rolling windows ending at t
    season_period: int        # P: 24 for hourly (same hour yesterday), 7 for daily (same weekday)
    n_seasons: int            # how many past periods to average for the seasonal mean
    max_gap: int              # forward-fill limit in cleaning
    local_tz: str = "Asia/Kolkata"


HOURLY = FreqConfig("hourly", "h", pd.Timedelta(hours=1), 24, (0, 1, 2, 3, 5, 11, 23, 47, 167),
                    (3, 6, 24, 168), 24, 7, 3)
DAILY = FreqConfig("daily", "D", pd.Timedelta(days=1), 1, (0, 1, 2, 6, 13), (3, 7, 14), 7, 4, 1)
CONFIGS = {"hourly": HOURLY, "daily": DAILY}

# Indian seasons, as they matter for pollution: winter (Dec-Feb), summer (Mar-May),
# monsoon (Jun-Sep), post-monsoon (Oct-Nov, crop burning).
_SEASON = {12: 0, 1: 0, 2: 0, 3: 1, 4: 1, 5: 1, 6: 2, 7: 2, 8: 2, 9: 2, 10: 3, 11: 3}


def lag_cols(cfg: FreqConfig) -> list[str]:
    return [f"lag_{k}" for k in cfg.lags] + ["diff_1"]


def rolling_cols(cfg: FreqConfig) -> list[str]:
    return [f"roll_{stat}_{w}" for w in cfg.windows for stat in ("mean", "max")]


CALENDAR_COLS = ["hour", "dow", "month", "season"]
SEASONAL_COLS = ["seasonal_1", "seasonal_mean"]


def origin_features(series: pd.Series, cfg: FreqConfig) -> pd.DataFrame:
    """Features known at each origin t (the series index), from y(<= t) only."""
    f = pd.DataFrame(index=series.index)
    for k in cfg.lags:
        f[f"lag_{k}"] = series.shift(k)
    f["diff_1"] = series - series.shift(1)
    for w in cfg.windows:
        roll = series.rolling(w, min_periods=max(1, w // 2))
        f[f"roll_mean_{w}"] = roll.mean()
        f[f"roll_max_{w}"] = roll.max()
    return f


def horizon_rows(series: pd.Series, cfg: FreqConfig, with_target: bool = True,
                 horizons: list[int] | None = None) -> pd.DataFrame:
    """Expand origin features into one row per horizon, adding target-time features."""
    if cfg.horizon > cfg.season_period:
        raise ValueError("horizon must not exceed season_period, or seasonal features would leak")
    base = origin_features(series, cfg)
    frames = []
    for h in horizons or range(1, cfg.horizon + 1):
        d = base.copy()
        d["horizon"] = h
        target_ts = series.index + h * cfg.step
        local = target_ts.tz_convert(cfg.local_tz) if target_ts.tz is not None else target_ts
        d["target_ts"] = target_ts
        d["hour"] = local.hour
        d["dow"] = local.dayofweek
        d["month"] = local.month
        d["season"] = local.month.map(_SEASON)
        p = cfg.season_period
        d["seasonal_1"] = series.shift(p - h).to_numpy()
        past = pd.DataFrame({k: series.shift(p * k - h).to_numpy() for k in range(1, cfg.n_seasons + 1)})
        # Mean of the available past periods; require at least half of them to be present.
        mean = past.mean(axis=1, skipna=True)
        d["seasonal_mean"] = mean.where(past.count(axis=1) >= (cfg.n_seasons + 1) // 2).to_numpy()
        if with_target:
            d["y"] = series.shift(-h).to_numpy()
        frames.append(d)
    out = pd.concat(frames)
    out.index.name = "origin_ts"
    return out.reset_index()


def build_rows(regular: pd.DataFrame, cfg: FreqConfig, with_target: bool = True,
               origin_stride: int = 1, origin_after: pd.Timestamp | None = None) -> pd.DataFrame:
    """All stations: regular long frame -> model rows with ``station_id``.

    ``origin_stride`` > 1 keeps every n-th origin (aligned on the global clock, so all stations share the
    same origins and chronological splits stay clean). Features are still computed from the full series;
    only the number of rows shrinks. Used to bound training memory."""
    frames = []
    for sid, g in regular.groupby("station_id", sort=True):
        s = g.set_index("ts")["aqi"].astype(float)
        rows = horizon_rows(s, cfg, with_target=with_target)
        if origin_stride > 1:
            # step index since the epoch via timestamp arithmetic: independent of the datetime resolution
            # (pandas 2 stores ns, pandas 3 may store us; astype("int64") would differ between them)
            steps = (rows["origin_ts"] - pd.Timestamp(0, tz="UTC")) // cfg.step
            rows = rows[steps % origin_stride == 0]
        if origin_after is not None:   # filter per station before concatenating (memory)
            rows = rows[rows["origin_ts"] > origin_after]
        rows.insert(0, "station_id", sid)
        frames.append(rows)
    if not frames:
        return pd.DataFrame()
    rows = pd.concat(frames, ignore_index=True)
    # An origin with no current value cannot be forecast from.
    return rows[rows["lag_0"].notna()].reset_index(drop=True)
