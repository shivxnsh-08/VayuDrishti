"""Train, evaluate and (only if it earns it) promote an AQI forecasting model.

    python train.py --data history.csv                          # hourly, all candidates
    python train.py --data history.csv --models random_forest --dry-run
    python train.py --data city_day.csv --freq daily --station-col <c> --ts-col <c> --aqi-col <c>

Pipeline
1. Clean onto a regular grid (impossible values removed, only short gaps forward-filled).
2. Readiness gate: refuse to train unless the history covers warm-up + train + validation + test windows
   (hourly default 4 x 168 h = 28 days). The report says exactly what is missing. Nothing is synthesised.
3. Build direct multi-horizon rows (horizons 1..24 h; features only from data at or before the origin).
4. Chronological split on origin time:   [ train | validation | test ]
     train:      targets strictly before the validation window starts
     validation: origins in the validation window, targets before the test window starts
     test:       origins in the last window; never used for fitting, selection or calibration
5. Fit every candidate (Random Forest, LightGBM) on train; pick the one with the lowest validation MAE.
   Random Forest intervals are split-conformal, calibrated on the validation window.
6. Evaluate the selected model ONCE on the test window: MAE, RMSE, category accuracy overall and at
   horizons 1/3/6/12/24 h, per station, interval coverage. Same rows for every baseline.
7. Promotion gate: the model must beat persistence, the best baseline and the currently active model
   (if any) on test MAE. Otherwise the current model (or the persistence baseline) stays active.
8. On promotion: refit on all data with the same settings, save a new version, update the registry.
   Stations where a baseline beat the model on test are served that baseline (recorded in metadata).

Exit codes: 0 promoted or evaluated, 2 not enough data, 3 evaluated but not promoted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from data_io import load_history
from features.build import CONFIGS, build_rows
from features.cleaning import regularize
from models import metrics
from models.baselines import BASELINES, baseline_predictions
from models.lgbm import QuantileModel, feature_columns
from models.random_forest import RandomForestModel
from models.readiness import DAILY_PLAN, HOURLY_PLAN, SplitPlan, assess
from models.registry import Registry

REPORTED_HORIZONS = (1, 3, 6, 12, 24)
CANDIDATES = {
    "random_forest": lambda cols, quick: RandomForestModel(cols, {"n_estimators": 40} if quick else None),
    "lightgbm": lambda cols, quick: QuantileModel(cols, {"n_estimators": 60} if quick else None),
}


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--data", required=True)
    p.add_argument("--freq", choices=list(CONFIGS), default="hourly")
    p.add_argument("--out", default=str(Path(__file__).parent / "models" / "artifacts"))
    p.add_argument("--models", default="random_forest,lightgbm", help="comma-separated candidates")
    p.add_argument("--warmup-steps", type=int)
    p.add_argument("--min-train-steps", type=int)
    p.add_argument("--val-steps", type=int)
    p.add_argument("--test-steps", type=int)
    p.add_argument("--min-stations", type=int, default=1)
    p.add_argument("--max-train-rows", type=int, default=1_000_000,
                   help="random subsample of training rows (fixed seed) to bound memory")
    p.add_argument("--origin-stride", type=int, default=1,
                   help="keep every n-th origin hour per station when building rows (bounds memory; features use all data)")
    p.add_argument("--dry-run", action="store_true", help="evaluate and save the candidate, never promote")
    p.add_argument("--quick", action="store_true", help="small models (tests / smoke runs only)")
    p.add_argument("--station-col", default="station_id")
    p.add_argument("--ts-col", default="ts")
    p.add_argument("--aqi-col", default="aqi")
    p.add_argument("--dayfirst", action="store_true", help="parse dates as DD-MM-YYYY")
    return p.parse_args(argv)


def make_plan(args, freq: str) -> SplitPlan:
    base = HOURLY_PLAN if freq == "hourly" else DAILY_PLAN
    return SplitPlan(
        warmup_steps=args.warmup_steps if args.warmup_steps is not None else base.warmup_steps,
        min_train_steps=args.min_train_steps if args.min_train_steps is not None else base.min_train_steps,
        val_steps=args.val_steps if args.val_steps is not None else base.val_steps,
        test_steps=args.test_steps if args.test_steps is not None else base.test_steps,
        min_stations=args.min_stations)


def dataset_fingerprint(raw: pd.DataFrame) -> str:
    """Content hash of the cleaned input (order-independent), recorded for reproducibility."""
    canon = raw.sort_values(["station_id", "ts"]).reset_index(drop=True)
    return hashlib.sha256(pd.util.hash_pandas_object(canon, index=False).values.tobytes()).hexdigest()[:16]


def chronological_split(rows: pd.DataFrame, plan: SplitPlan, step: pd.Timedelta = pd.Timedelta(hours=1)):
    """Windows are measured in TIME back from the newest origin (not in number of origins), so they mean
    the same thing whether or not origins are strided."""
    last = rows["origin_ts"].max()
    test_start = last - (plan.test_steps - 1) * step
    val_start = test_start - plan.val_steps * step
    if not (rows["origin_ts"] < val_start).any():
        raise ValueError("not enough history before the validation and test windows")
    known = rows["y"].notna()
    train = rows[known & (rows["target_ts"] < val_start)]
    val = rows[known & (rows["origin_ts"] >= val_start) & (rows["origin_ts"] < test_start)
               & (rows["target_ts"] < test_start)]
    test = rows[known & (rows["origin_ts"] >= test_start)]
    bounds = {"train": [str(rows["origin_ts"].min()), str(val_start)],
              "validation": [str(val_start), str(test_start)],
              "test": [str(test_start), str(rows["origin_ts"].max())]}
    return train, val, test, bounds


def predictions(model, rows: pd.DataFrame) -> pd.DataFrame:
    q = model.predict(rows)
    out = rows[["station_id", "origin_ts", "target_ts", "horizon", "hour", "y"]].join(baseline_predictions(rows))
    out["model"], out["model_q10"], out["model_q90"] = q["q50"], q["q10"], q["q90"]
    return out


def evaluate(preds: pd.DataFrame) -> dict:
    methods = [*BASELINES.keys(), "model"]
    overall = metrics.summary(preds, methods).iloc[0].to_dict()
    by_h = metrics.by(preds, "horizon", methods)
    reported = by_h[by_h["horizon"].isin(REPORTED_HORIZONS)]
    cov = metrics.interval_coverage(preds, "model_q10", "model_q90")
    return {
        "rows": int(overall["n"]),
        "model_mae": overall["model_mae"], "model_rmse": overall["model_rmse"],
        "model_category_accuracy": overall["model_cat_acc"],
        "baselines": {b: {"mae": overall[f"{b}_mae"], "rmse": overall[f"{b}_rmse"],
                          "category_accuracy": overall[f"{b}_cat_acc"]} for b in BASELINES},
        "by_horizon": {int(r.horizon): {"model_mae": r.model_mae, "persistence_mae": r.persistence_mae,
                                        "model_rmse": r.model_rmse, "persistence_rmse": r.persistence_rmse,
                                        "best_baseline_mae": min(getattr(r, f"{b}_mae") for b in BASELINES)}
                       for r in reported.itertuples()},
        "interval_coverage": float(cov["coverage"].mean()) if not cov.empty else None,
        "interval_rows": int(cov["n"].sum()) if not cov.empty else 0,
    }


def main(argv=None, raw: pd.DataFrame | None = None, data_label: str | None = None) -> dict:
    """CLI entry point. The lifecycle manager passes an already-loaded ``raw`` frame (station_id, ts, aqi)
    instead of a CSV path; everything after loading is identical, including the promotion gate."""
    if raw is not None and argv is not None and "--data" not in argv:
        argv = ["--data", data_label or "<frame>", *argv]
    args = parse_args(argv)
    cfg = CONFIGS[args.freq]
    plan = make_plan(args, args.freq)
    registry = Registry(Path(args.out))
    started = datetime.now(timezone.utc)

    if raw is None:
        raw = load_history(args.data, args.station_col, args.ts_col, args.aqi_col, dayfirst=args.dayfirst)
    regular = regularize(raw, cfg.freq, cfg.max_gap)
    readiness = assess(regular, plan, cfg.step, "h" if args.freq == "hourly" else "d")
    report: dict = {"started_at": started.isoformat(timespec="seconds"), "freq": cfg.name,
                    "readiness": asdict(readiness), "data_file": str(args.data)}
    if not readiness.ready:
        report["status"] = "INSUFFICIENT_DATA"
        _write_report(registry, report)
        print(json.dumps(report, indent=2, default=str))
        return report

    rows = build_rows(regular, cfg, origin_stride=args.origin_stride)
    train, val, test, bounds = chronological_split(rows, plan, cfg.step)
    if len(train) > args.max_train_rows:
        train = train.sample(args.max_train_rows, random_state=42)
    cols = feature_columns(cfg)

    # ---- candidates: fit on train, select on validation ----------------------------------------
    candidates = {}
    for name in [m.strip() for m in args.models.split(",") if m.strip()]:
        if name not in CANDIDATES:
            raise SystemExit(f"unknown model {name}; choose from {sorted(CANDIDATES)}")
        model = CANDIDATES[name](cols, args.quick).fit(train)
        if isinstance(model, RandomForestModel):
            model.calibrate(val)
        val_preds = predictions(model, val)
        val_mae = metrics.summary(val_preds, [*BASELINES.keys(), "model"]).iloc[0]["model_mae"]
        candidates[name] = {"model": model, "validation_mae": float(val_mae)}
    best_name = min(candidates, key=lambda n: candidates[n]["validation_mae"])
    best = candidates[best_name]["model"]

    # ---- one evaluation on the untouched test window -----------------------------------------
    test_preds = predictions(best, test)
    ev = evaluate(test_preds)
    per_station = metrics.best_baseline_per_station(test_preds, model="model")
    best_baseline = min(ev["baselines"], key=lambda b: ev["baselines"][b]["mae"])

    active = registry.active()
    active_mae = None
    if active and active.get("freq") == cfg.name and (Path(args.out) / active["model_file"]).exists():
        import joblib
        current = joblib.load(Path(args.out) / active["model_file"])
        scored = metrics.comparable(test_preds, [*BASELINES.keys(), "model"])
        active_mae = float(np.mean(np.abs(current.predict(test.loc[scored.index])["q50"] - scored["y"])))

    reasons = []
    if not ev["model_mae"] < ev["baselines"]["persistence"]["mae"]:
        reasons.append("does not beat the persistence baseline")
    if not ev["model_mae"] < ev["baselines"][best_baseline]["mae"]:
        reasons.append(f"does not beat the best baseline ({best_baseline})")
    if active_mae is not None and not ev["model_mae"] < active_mae:
        reasons.append(f"does not beat the active model {active['version']} (MAE {active_mae:.2f})")
    passed = not reasons

    version = f"{best_name}-{started.strftime('%Y%m%dT%H%M%S')}"
    vdir = registry.version_dir(version)
    test_preds.to_csv(vdir / "eval_test_predictions.csv.gz", index=False)
    per_station.to_csv(vdir / "eval_per_station.csv", index=False)

    final = best
    if passed and not args.dry_run:
        # Refit on everything with the same settings; RF keeps its validation-calibrated interval offsets.
        final = CANDIDATES[best_name](cols, args.quick)
        offsets = getattr(best, "offsets", None)
        all_rows = rows[rows["y"].notna()]
        final.fit(all_rows.sample(args.max_train_rows, random_state=42) if len(all_rows) > args.max_train_rows else all_rows)
        if offsets is not None:
            final.offsets = offsets
    final.save(vdir / "model.joblib")

    metadata = {
        "version": version,
        "model_name": best_name,
        "freq": cfg.name,
        "trained_at": started.isoformat(timespec="seconds"),
        "horizons": list(range(1, cfg.horizon + 1)),
        "reported_horizons": list(REPORTED_HORIZONS),
        "features": cols,
        "dataset": {"file": str(args.data), "fingerprint": dataset_fingerprint(raw),
                    "stations": int(raw["station_id"].nunique()), "observations": int(len(raw)),
                    "first_ts": readiness.first_ts, "last_ts": readiness.last_ts,
                    "rows": {"train": int(len(train)), "validation": int(len(val)), "test": int(len(test))}},
        "training_window": {"start": bounds["train"][0], "end": bounds["test"][1]},
        "splits": bounds,
        "candidates": {n: {"validation_mae": c["validation_mae"]} for n, c in candidates.items()},
        "evaluation": {"test": ev, "best_baseline": best_baseline, "active_model_test_mae": active_mae},
        "intervals": {"method": "split-conformal on validation residuals" if best_name == "random_forest"
                      else "LightGBM quantile regression (q10, q90)",
                      "nominal_coverage": 0.8, "test_coverage": ev["interval_coverage"]},
        "refit_on_all_data": bool(passed and not args.dry_run),
        "use_baseline": {r.station_id: r.best_baseline for r in per_station.itertuples() if not r.model_beats_baseline},
        "promotion": {"passed": passed, "reasons": reasons, "dry_run": args.dry_run},
        # kept for the API's /model-info (backwards compatible)
        "n_stations": int(raw["station_id"].nunique()),
        "validation": {"method": "chronological train/validation/test; metrics on the test window",
                       # keyed by the real model name ("lgbm" for LightGBM, kept for existing clients)
                       "overall": {f"{'lgbm' if best_name == 'lightgbm' else best_name}_mae": ev["model_mae"],
                                   f"{'lgbm' if best_name == 'lightgbm' else best_name}_cat_acc": ev["model_category_accuracy"],
                                   **{f"{b}_mae": v["mae"] for b, v in ev["baselines"].items()},
                                   **{f"{b}_cat_acc": v["category_accuracy"] for b, v in ev["baselines"].items()}},
                       "interval_coverage_q10_q90": ev["interval_coverage"]},
    }
    registry.save_candidate(version, metadata)

    if passed and not args.dry_run:
        registry.promote(version, f"test MAE {ev['model_mae']:.2f} < persistence {ev['baselines']['persistence']['mae']:.2f}")
        report["status"] = "PROMOTED"
    else:
        report["status"] = "EVALUATED_NOT_PROMOTED"
    report.update({"version": version, "model_name": best_name, "candidates": metadata["candidates"],
                   "test": ev, "promotion": metadata["promotion"], "splits": bounds})
    _write_report(registry, report)
    print(json.dumps(report, indent=2, default=str))
    return report


def _write_report(registry: Registry, report: dict) -> None:
    (registry.root / "last_training_report.json").write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")


if __name__ == "__main__":
    result = main()
    sys.exit({"PROMOTED": 0, "INSUFFICIENT_DATA": 2, "EVALUATED_NOT_PROMOTED": 3}.get(result.get("status"), 0))
