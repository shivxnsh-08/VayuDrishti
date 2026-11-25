"""Loading history CSVs into the canonical long format ``station_id, ts, aqi``.

* Hourly: export of the backend's ``station_aqi`` table (see evaluation/sql/export_station_aqi.sql).
* Daily (cold start): the historical day-wise city AQI dataset. Its column names are NOT
  assumed here; pass them with --station-col/--ts-col/--aqi-col after checking the file (VERIFY).
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd


def load_history(path: str | Path, station_col: str = "station_id", ts_col: str = "ts", aqi_col: str = "aqi",
                 tz_if_naive: str = "Asia/Kolkata", dayfirst: bool = False) -> pd.DataFrame:
    df = pd.read_csv(path)
    missing = {station_col, ts_col, aqi_col} - set(df.columns)
    if missing:
        raise ValueError(f"columns not found in {path}: {sorted(missing)}; available: {list(df.columns)}")
    ts = pd.to_datetime(df[ts_col], dayfirst=dayfirst)
    if ts.dt.tz is None:
        ts = ts.dt.tz_localize(tz_if_naive)
    return pd.DataFrame({
        "station_id": df[station_col].astype(str),
        "ts": ts.dt.tz_convert("UTC"),
        "aqi": pd.to_numeric(df[aqi_col], errors="coerce"),
    })
