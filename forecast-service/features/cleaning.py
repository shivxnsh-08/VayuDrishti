"""Turn raw (station, ts, aqi) rows into a regular time grid per station.

Rules (spec section 7, "Missing data"):
* impossible values (outside 0..500) become missing, they are never clipped into range;
* only *short* gaps (<= ``max_gap`` consecutive steps) are forward-filled;
* longer gaps stay missing and are flagged, so no model ever trains on invented data.
"""
from __future__ import annotations

import pandas as pd

AQI_MIN = 0
AQI_MAX = 500


def mark_impossible(values: pd.Series, lo: float = AQI_MIN, hi: float = AQI_MAX) -> pd.Series:
    """Replace values outside [lo, hi] (and non-numeric values) with NaN."""
    v = pd.to_numeric(values, errors="coerce")
    return v.where((v >= lo) & (v <= hi))


def fill_short_gaps(s: pd.Series, max_gap: int) -> pd.Series:
    """Forward-fill runs of NaN no longer than ``max_gap``; longer runs are left entirely NaN.

    ``Series.ffill(limit=k)`` is not enough: it would fill the first k steps of a long gap.
    """
    if max_gap <= 0 or s.empty:
        return s
    isna = s.isna()
    run_id = (isna != isna.shift()).cumsum()
    run_len = isna.groupby(run_id).transform("sum")
    fillable = isna & (run_len <= max_gap)
    return s.where(~fillable, s.ffill())


def regularize(
    df: pd.DataFrame,
    freq: str,
    max_gap: int,
    station_col: str = "station_id",
    ts_col: str = "ts",
    value_col: str = "aqi",
) -> pd.DataFrame:
    """Return a long frame ``[station_id, ts, aqi, imputed, gap]`` on a regular grid.

    The grid starts at each station's first timestamp (``origin="start"``) so that hourly data
    stamped at :30 (IST hours expressed in UTC) is not shifted onto :00 buckets.
    """
    columns = [station_col, ts_col, value_col, "imputed", "gap"]
    if df.empty:
        return pd.DataFrame(columns=columns)
    data = df[[station_col, ts_col, value_col]].copy()
    data[ts_col] = pd.to_datetime(data[ts_col], utc=True)
    frames = []
    for sid, g in data.groupby(station_col, sort=True):
        s = g.set_index(ts_col)[value_col].sort_index()
        s = mark_impossible(s)
        s = s[~s.index.duplicated(keep="last")]
        s = s.resample(freq, origin="start").mean()
        filled = fill_short_gaps(s, max_gap)
        frames.append(pd.DataFrame({
            station_col: sid,
            ts_col: filled.index,
            value_col: filled.to_numpy(),
            "imputed": (s.isna() & filled.notna()).to_numpy(),
            "gap": filled.isna().to_numpy(),
        }))
    return pd.concat(frames, ignore_index=True)


def gap_report(regular: pd.DataFrame, station_col: str = "station_id") -> pd.DataFrame:
    """Per-station share of imputed and missing steps (input to the data-quality audit, X1)."""
    return (regular.groupby(station_col)
            .agg(steps=("gap", "size"), missing_rate=("gap", "mean"), imputed_rate=("imputed", "mean"))
            .reset_index())
