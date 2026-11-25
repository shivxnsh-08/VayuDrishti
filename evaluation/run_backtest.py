"""Experiments X2-X5: rolling-origin backtest of baselines vs LightGBM.

    python evaluation/run_backtest.py --data history.csv --freq hourly --folds 4
    python evaluation/run_backtest.py --data history.csv --ablate calendar      # X4 ablation

Writes CSVs + a markdown summary to evaluation/results/<run-name>/. Record the dataset dates,
station count, training window and hardware next to every number you report (spec section 13).
"""
from __future__ import annotations

import argparse
import json
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "forecast-service"))

from data_io import load_history  # noqa: E402
from features.build import CONFIGS, build_rows  # noqa: E402
from features.cleaning import gap_report, regularize  # noqa: E402
from models import metrics  # noqa: E402
from models.backtest import run_backtest  # noqa: E402


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--data", required=True)
    p.add_argument("--freq", choices=list(CONFIGS), default="hourly")
    p.add_argument("--folds", type=int, default=4)
    p.add_argument("--test-size", type=int, help="origins per fold (default 168 hourly / 30 daily)")
    p.add_argument("--ablate", nargs="*", default=[], help="feature groups to drop: lags rolling calendar seasonal station")
    p.add_argument("--station-col", default="station_id")
    p.add_argument("--ts-col", default="ts")
    p.add_argument("--aqi-col", default="aqi")
    p.add_argument("--dayfirst", action="store_true")
    p.add_argument("--name", help="results folder name")
    args = p.parse_args(argv)

    cfg = CONFIGS[args.freq]
    raw = load_history(args.data, args.station_col, args.ts_col, args.aqi_col, dayfirst=args.dayfirst)
    regular = regularize(raw, cfg.freq, cfg.max_gap)
    rows = build_rows(regular, cfg)
    preds = run_backtest(rows, cfg, n_folds=args.folds, test_size=args.test_size, drop_groups=tuple(args.ablate))

    name = args.name or f"{cfg.name}-{'-'.join(args.ablate) or 'full'}-{datetime.now():%Y%m%d-%H%M}"
    out = ROOT / "evaluation" / "results" / name
    out.mkdir(parents=True, exist_ok=True)

    summary = metrics.summary(preds)
    per_station = metrics.best_baseline_per_station(preds)
    per_horizon = metrics.by(preds, "horizon")
    per_hour = metrics.by(preds, "hour")
    confusion = metrics.confusion(preds)
    coverage = metrics.interval_coverage(preds)

    preds.to_csv(out / "predictions.csv", index=False)
    summary.to_csv(out / "summary.csv", index=False)
    per_station.to_csv(out / "per_station.csv", index=False)
    per_horizon.to_csv(out / "per_horizon.csv", index=False)
    per_hour.to_csv(out / "per_hour.csv", index=False)
    confusion.to_csv(out / "confusion_lgbm.csv")
    coverage.to_csv(out / "interval_coverage.csv", index=False)
    gap_report(regular).to_csv(out / "gaps.csv", index=False)

    meta = {
        "data_file": str(args.data),
        "freq": cfg.name,
        "data_start": str(raw["ts"].min()), "data_end": str(raw["ts"].max()),
        "n_stations": int(raw["station_id"].nunique()),
        "folds": args.folds, "ablated_groups": args.ablate,
        "test_period": [str(preds["origin_ts"].min()), str(preds["origin_ts"].max())],
        "hardware": f"{platform.processor() or platform.machine()} / {platform.system()} {platform.release()}",
        "python": platform.python_version(),
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    s = summary.iloc[0]
    methods = metrics.METHODS
    lines = [f"# Backtest: {name}", "", "```json", json.dumps(meta, indent=2), "```", "",
             "| Method | MAE | RMSE | Category accuracy |", "|---|---|---|---|"]
    lines += [f"| {m} | {s[f'{m}_mae']:.1f} | {s[f'{m}_rmse']:.1f} | {s[f'{m}_cat_acc']:.1%} |" for m in methods]
    beats = int(per_station["model_beats_baseline"].sum())
    lines += ["", f"Model beats the best baseline at {beats} of {len(per_station)} stations "
                  f"(see per_station.csv; the others are served by their best baseline).",
              f"Mean q10-q90 interval coverage: {coverage['coverage'].mean():.1%} (nominal 80%).", "",
              "Rows compared: " + str(int(s["n"])) + ". Numbers come from this run only; do not round them up."]
    (out / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    print(f"\nResults in {out}")


if __name__ == "__main__":
    main()
