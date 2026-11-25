"""Pure lifecycle decisions (no I/O), so every rule is unit-tested in isolation.

Training decision, in order:
1. readiness gate not passed                       -> do not train (no run is recorded)
2. dataset unchanged since the last completed run  -> do not train
3. last run(s) failed or were interrupted          -> retry after exponential backoff; after
   ``max_consecutive_failures`` on the same data, stop until the data changes
4. never trained successfully                      -> INITIAL training
5. trained before:
   - >= max_retrain_interval since then and any new hours   -> RETRAIN_MAX_INTERVAL
   - >= min_retrain_interval and >= min_new_hours new data   -> RETRAIN_NEW_DATA
   - otherwise                                               -> wait
"Completed" means the training ran to the end (PROMOTED, NOT_PROMOTED, or train.py's own INSUFFICIENT_DATA).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

import pandas as pd

from lifecycle.config import LifecycleConfig
from lifecycle.repository import COMPLETED, FAILED_LIKE


@dataclass(frozen=True)
class TrainDecision:
    train: bool
    trigger: str | None
    attempt: int
    reason: str


def _ts(v) -> pd.Timestamp | None:
    return None if v is None else pd.Timestamp(v).tz_convert("UTC") if pd.Timestamp(v).tzinfo else pd.Timestamp(v, tz="UTC")


def decide_training(now: datetime, ready: bool, readiness_reason: str, fingerprint: str | None,
                    data_last_ts, runs: list[dict], cfg: LifecycleConfig) -> TrainDecision:
    """``runs`` newest first, as returned by Repository.runs()."""
    if not ready:
        return TrainDecision(False, None, 0, f"readiness not met: {readiness_reason}")
    now_ts = _ts(now)
    finished = [r for r in runs if r["status"] != "RUNNING"]

    if any(r["status"] in COMPLETED and r.get("dataset_fingerprint") == fingerprint for r in finished):
        return TrainDecision(False, None, 0, "dataset unchanged since the last completed training")

    failures = 0
    for r in finished:
        if r["status"] in FAILED_LIKE:
            failures += 1
        else:
            break
    if failures:
        last = finished[0]
        if failures >= cfg.max_consecutive_failures and last.get("dataset_fingerprint") == fingerprint:
            return TrainDecision(False, None, 0,
                                 f"stopped after {failures} consecutive failures; waiting for new data")
        wait = timedelta(hours=min(cfg.failure_backoff_h * 2 ** (failures - 1), cfg.failure_backoff_max_h))
        retry_at = _ts(last["finished_at"]) + wait
        if now_ts < retry_at:
            return TrainDecision(False, None, 0, f"backing off after {failures} failure(s) until {retry_at.isoformat()}")
        return TrainDecision(True, "RETRY", failures + 1, f"retry after {failures} failure(s)")

    last_ok = next((r for r in finished if r["status"] in COMPLETED), None)
    if last_ok is None:
        return TrainDecision(True, "INITIAL", 1, "readiness met and no completed training yet")

    since = now_ts - _ts(last_ok["finished_at"])
    prev_last = _ts(last_ok.get("data_last_ts"))
    new_hours = ((_ts(data_last_ts) - prev_last) / pd.Timedelta(hours=1)) if prev_last is not None and data_last_ts else 0.0
    if since >= timedelta(hours=cfg.max_retrain_interval_h) and new_hours > 0:
        return TrainDecision(True, "RETRAIN_MAX_INTERVAL", 1,
                             f"{since} since last training (max interval) with {new_hours:.0f} h of new data")
    if since >= timedelta(hours=cfg.min_retrain_interval_h) and new_hours >= cfg.min_new_hours:
        return TrainDecision(True, "RETRAIN_NEW_DATA", 1, f"{new_hours:.0f} h of new data since last training")
    return TrainDecision(False, None, 0,
                         f"last training {since} ago with {new_hours:.0f} h of new data since; retrain needs "
                         f">= {cfg.min_retrain_interval_h:.0f} h and >= {cfg.min_new_hours} new hours "
                         f"(or {cfg.max_retrain_interval_h:.0f} h)")


@dataclass(frozen=True)
class DegradationDecision:
    streak: int
    rollback: bool
    reason: str


def decide_degradation(perf: dict, streak: int, cfg: LifecycleConfig) -> DegradationDecision:
    """``perf`` from monitor.recent_performance. Insufficient evidence never changes anything."""
    if not perf.get("sufficient"):
        return DegradationDecision(streak, False, f"insufficient evidence: {perf.get('reason')}")
    limit = perf["persistence_mae"] * (1 + cfg.degradation_margin)
    if perf["model_mae"] > limit:
        streak += 1
        if streak >= cfg.degradation_consecutive:
            return DegradationDecision(streak, True,
                                       f"model MAE {perf['model_mae']:.2f} > persistence {perf['persistence_mae']:.2f} "
                                       f"+{cfg.degradation_margin:.0%} on {streak} consecutive checks")
        return DegradationDecision(streak, False, f"degraded on {streak}/{cfg.degradation_consecutive} checks")
    return DegradationDecision(0, False, f"healthy: model MAE {perf['model_mae']:.2f} vs persistence {perf['persistence_mae']:.2f}")


def failure_rate_exceeded(events: list[tuple[datetime, bool]], now: datetime, cfg: LifecycleConfig) -> tuple[bool, dict]:
    """``events``: (time, ok) for each model prediction attempt."""
    since = _ts(now) - timedelta(minutes=cfg.failure_window_min)
    recent = [ok for t, ok in events if _ts(t) >= since]
    failures = sum(1 for ok in recent if not ok)
    stats = {"requests": len(recent), "failures": failures}
    exceeded = len(recent) >= cfg.failure_min_requests and failures / len(recent) >= cfg.failure_rate_threshold
    return exceeded, stats
