"""CPCB-style AQI from hourly CONCENTRATIONS (used only for historical backfill).

The live feed already provides per-pollutant sub-indices; history from OpenAQ provides raw
concentrations, so here we must compute the AQI ourselves:

1. average each pollutant over its CPCB averaging window (24 h for PM10, PM2.5, NO2, SO2, NH3;
   8 h for CO and O3 - VERIFY), requiring ``min_hours`` valid hours in the window;
2. convert each average to a sub-index by linear interpolation in the breakpoint table
   (same table and the same gap/cap rules as the backend's AqiCalculator);
3. overall AQI = max sub-index, only if at least ``min_pollutants`` sub-indices exist and one of
   them is PM2.5 or PM10.

Nothing is imputed: an hour without enough coverage simply has no AQI.
"""
from __future__ import annotations

import math
from functools import lru_cache
from pathlib import Path

import pandas as pd
import yaml

CONFIG = Path(__file__).resolve().parent.parent / "config" / "aqi_breakpoints.yaml"


@lru_cache(maxsize=1)
def config() -> dict:
    with open(CONFIG, encoding="utf-8") as f:
        return yaml.safe_load(f)


def sub_index(pollutant: str, conc: float) -> int | None:
    rows = config()["breakpoints"].get(pollutant)
    if not rows or conc is None or pd.isna(conc) or conc < 0:
        return None
    last = rows[-1]
    if conc > last["c_high"]:
        return int(last["i_high"])
    for i, bp in enumerate(rows):
        upper = rows[i + 1]["c_low"] if i + 1 < len(rows) else bp["c_high"]
        if conc >= bp["c_low"] and (conc < upper or i + 1 == len(rows)):
            c = min(conc, bp["c_high"])
            value = (bp["i_high"] - bp["i_low"]) / (bp["c_high"] - bp["c_low"]) * (c - bp["c_low"]) + bp["i_low"]
            # Java Math.round semantics (half up), not Python's banker's rounding
            return int(math.floor(value + 0.5))
    return None


def averaged(series: pd.Series, pollutant: str) -> pd.Series:
    """Trailing average over the pollutant's window, on an hourly grid, with a coverage minimum."""
    avg = config()["averaging"][pollutant]
    s = series.sort_index()
    s = s[~s.index.duplicated(keep="last")]
    grid = s.resample("h", origin="start").mean()
    return grid.rolling(f"{avg['hours']}h", min_periods=avg["min_hours"]).mean()


def hourly_aqi(long: pd.DataFrame) -> pd.DataFrame:
    """``long``: columns ts (UTC), pollutant (internal code), value (CPCB units).
    Returns ts, aqi, dominant_pollutant, pollutant_count for hours that qualify."""
    cfg = config()
    subs = {}
    for pollutant, g in long.groupby("pollutant"):
        if pollutant not in cfg["averaging"]:
            continue
        avg = averaged(g.set_index("ts")["value"].astype(float), pollutant)
        subs[pollutant] = avg.map(lambda c, p=pollutant: sub_index(p, c))
    if not subs:
        return pd.DataFrame(columns=["ts", "aqi", "dominant_pollutant", "pollutant_count"])
    table = pd.DataFrame(subs)
    out = []
    required = set(cfg["required_any_of"])
    for ts, row in table.iterrows():
        valid = row.dropna()
        if len(valid) < cfg["min_pollutants"] or not required & set(valid.index):
            continue
        dominant = valid.astype(float).idxmax()
        out.append({"ts": ts, "aqi": int(valid[dominant]), "dominant_pollutant": dominant,
                    "pollutant_count": int(len(valid))})
    return pd.DataFrame(out, columns=["ts", "aqi", "dominant_pollutant", "pollutant_count"])
