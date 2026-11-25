"""Experiment X6: replay the alert policy over backtest predictions and compare with what
actually happened.

    python evaluation/alert_replay.py --predictions evaluation/results/<run>/predictions.csv \
        --threshold POOR --method lgbm

Policy replayed (mirrors AlertDecisionEngine, simplified: no quiet hours, no staleness):
* at every origin, alert if max predicted AQI over the next 24 h reaches the threshold category;
* dedupe on (station, category, 6-hour window of the first predicted crossing);
* cooldown: same-or-lower category within 6 h of the previous alert is not re-sent.

Ground truth: an "event" is a (station, 6-hour window) in which the ACTUAL AQI reached the threshold.
* precision = alerts whose window really reached the threshold within 24 h / alerts sent
* recall    = events that got an alert before they started / events
* lead time = hours between the alert and the first actual crossing it covered
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "forecast-service"))

from models.categories import categorize, category_names  # noqa: E402

WINDOW_H = 6
COOLDOWN_H = 6
HORIZON_H = 24


def _rank(cat):
    return category_names().index(cat) if cat is not None else -1


def replay(preds: pd.DataFrame, threshold: str, method: str) -> dict:
    preds = preds.copy()
    for c in ("origin_ts", "target_ts"):
        preds[c] = pd.to_datetime(preds[c], utc=True)
    thr = _rank(threshold)
    preds["pred_rank"] = [_rank(c) for c in categorize(preds[method])]
    preds["true_rank"] = [_rank(c) for c in categorize(preds["y"])]

    alerts = []
    for sid, g in preds.groupby("station_id"):
        last_time, last_rank, seen_keys = None, -1, set()
        for origin, o in g.sort_values("target_ts").groupby("origin_ts", sort=True):
            crossing = o[o["pred_rank"] >= thr]
            if crossing.empty:
                continue
            peak_rank = int(o["pred_rank"].max())
            first = crossing["target_ts"].min()
            window = first.floor(f"{WINDOW_H}h")
            key = (sid, peak_rank, window)
            in_cooldown = last_time is not None and origin - last_time < pd.Timedelta(hours=COOLDOWN_H)
            if key in seen_keys or (in_cooldown and peak_rank <= last_rank):
                continue
            seen_keys.add(key)
            last_time, last_rank = origin, peak_rank
            actual = o[o["true_rank"] >= thr]
            alerts.append({"station_id": sid, "alert_ts": origin, "window": window,
                           "hit": not actual.empty,
                           "lead_h": (actual["target_ts"].min() - origin) / pd.Timedelta(hours=1) if not actual.empty else np.nan})
    alerts = pd.DataFrame(alerts)

    # Events: actual threshold crossings, grouped into windows, observed via target times.
    truth = preds.drop_duplicates(["station_id", "target_ts"])
    truth = truth[truth["true_rank"] >= thr].copy()
    truth["window"] = truth["target_ts"].dt.floor(f"{WINDOW_H}h")
    events = truth.groupby(["station_id", "window"])["target_ts"].min().reset_index(name="first_crossing")

    detected = 0
    for e in events.itertuples():
        if alerts.empty:
            break
        a = alerts[(alerts["station_id"] == e.station_id) & (alerts["alert_ts"] <= e.first_crossing)
                   & (alerts["alert_ts"] >= e.first_crossing - pd.Timedelta(hours=HORIZON_H))]
        detected += int(not a.empty)

    n_alerts = len(alerts)
    return {
        "threshold": threshold, "method": method,
        "alerts": n_alerts, "events": len(events),
        "precision": float(alerts["hit"].mean()) if n_alerts else float("nan"),
        "recall": detected / len(events) if len(events) else float("nan"),
        "median_lead_h": float(alerts["lead_h"].median()) if n_alerts else float("nan"),
    }


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--predictions", required=True)
    p.add_argument("--threshold", default="POOR", choices=category_names())
    p.add_argument("--method", default="lgbm")
    args = p.parse_args(argv)
    preds = pd.read_csv(args.predictions)
    result = replay(preds, args.threshold, args.method)
    for k, v in result.items():
        print(f"{k:>14}: {v}")
    out = Path(args.predictions).with_name(f"alert_replay_{args.threshold}_{args.method}.csv")
    pd.DataFrame([result]).to_csv(out, index=False)
    print(f"written {out}")


if __name__ == "__main__":
    main()
