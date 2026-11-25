"""Experiment X1: data-quality audit from an export of the ``readings`` table.

    python evaluation/data_quality_audit.py --readings readings.csv --out docs/data-quality-report.md

Input columns: station_id, pollutant, ts, value, quality (see sql/export_readings.sql).
Per station: readings by quality flag, missing/invalid/stuck rates, hourly coverage.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd


def audit(readings: pd.DataFrame) -> pd.DataFrame:
    r = readings.copy()
    r["ts"] = pd.to_datetime(r["ts"], utc=True)
    counts = r.pivot_table(index="station_id", columns="quality", values="pollutant", aggfunc="count", fill_value=0)
    for q in ("OK", "MISSING", "INVALID", "STUCK"):
        if q not in counts:
            counts[q] = 0
    total = counts[["OK", "MISSING", "INVALID", "STUCK"]].sum(axis=1)
    out = pd.DataFrame({
        "readings": total,
        "missing_rate": counts["MISSING"] / total,
        "invalid_rate": counts["INVALID"] / total,
        "stuck_rate": counts["STUCK"] / total,
    })
    span = r.groupby("station_id")["ts"].agg(["min", "max"])
    hours_expected = ((span["max"] - span["min"]) / pd.Timedelta(hours=1)).round() + 1
    hours_seen = r.assign(h=r["ts"].dt.floor("h")).groupby("station_id")["h"].nunique()
    out["hourly_coverage"] = (hours_seen / hours_expected).clip(upper=1.0)
    out["first_ts"], out["last_ts"] = span["min"], span["max"]
    return out.sort_values("hourly_coverage").reset_index()


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--readings", required=True)
    p.add_argument("--out", default="docs/data-quality-report.md")
    args = p.parse_args(argv)
    table = audit(pd.read_csv(args.readings))
    fmt = table.copy()
    for c in ("missing_rate", "invalid_rate", "stuck_rate", "hourly_coverage"):
        fmt[c] = (fmt[c] * 100).round(1).astype(str) + "%"
    lines = ["# Data-quality report", "",
             f"Source: `{args.readings}`. Stations: {len(table)}. "
             f"Period: {table['first_ts'].min()} to {table['last_ts'].max()}.", "",
             fmt.drop(columns=["first_ts", "last_ts"]).to_markdown(index=False), "",
             "Definitions: MISSING = source sent no value; INVALID = unparseable, negative or implausible; "
             "STUCK = identical value for 6+ consecutive readings. Only OK readings feed the AQI."]
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
