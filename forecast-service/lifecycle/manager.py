"""The automated lifecycle loop. One ``tick()`` does, in order:

1. Recovery: RUNNING runs whose lock is free were orphaned by a restart/crash -> INTERRUPTED.
2. Safety: if the active model cannot be loaded, or recent model predictions fail at a high rate, fall back
   (previous model if it exists, otherwise the persistence baseline).
3. Monitoring (every ``monitor_interval_h``): score the active model on data newer than its training data;
   roll back only per policy.decide_degradation (enough evidence, consistently worse than persistence).
4. Readiness/training (every ``readiness_interval_h``): load real history from station_aqi, apply the
   672 h readiness gate, then policy.decide_training; start a training job (separate process) if due.

Every schedule time lives in ml_lifecycle_state, so a restart neither re-runs work early nor forgets it.
Errors in one step are logged and recorded; they never stop the loop or affect serving.
"""
from __future__ import annotations

import logging
import os
import subprocess
import sys
import threading
from collections import deque
from dataclasses import asdict
from datetime import datetime, timedelta
from pathlib import Path

import joblib
import pandas as pd

from features.build import HOURLY
from features.cleaning import regularize
from lifecycle import monitor
from lifecycle.config import LifecycleConfig
from lifecycle.policy import decide_degradation, decide_training, failure_rate_exceeded
from lifecycle.repository import Repository, utcnow
from models.readiness import SplitPlan, assess
from models.registry import Registry

log = logging.getLogger("vayudrishti.lifecycle")


class PredictionStats:
    """Thread-safe record of recent model prediction outcomes (fed by the /forecast endpoint)."""

    def __init__(self, maxlen: int = 5000):
        self._events: deque = deque(maxlen=maxlen)
        self._lock = threading.Lock()

    def record(self, ok: bool, at: datetime | None = None) -> None:
        with self._lock:
            self._events.append((at or utcnow(), ok))

    def snapshot(self) -> list:
        with self._lock:
            return list(self._events)

    def clear(self) -> None:
        with self._lock:
            self._events.clear()


class SubprocessRunner:
    """Runs ``python -m lifecycle.job`` so CPU-heavy training never blocks the API process."""

    def __init__(self, model_dir: Path):
        self.model_dir = model_dir
        self._proc: subprocess.Popen | None = None

    def running(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def start(self, trigger: str, attempt: int) -> None:
        cwd = Path(__file__).resolve().parent.parent
        self._proc = subprocess.Popen([sys.executable, "-m", "lifecycle.job", "--trigger", trigger,
                                       "--attempt", str(attempt), "--model-dir", str(self.model_dir)],
                                      cwd=cwd, env=os.environ.copy())


class LifecycleManager:
    def __init__(self, repo: Repository, model_dir: Path, cfg: LifecycleConfig, runner=None, store=None,
                 stats: PredictionStats | None = None, clock=utcnow):
        self.repo, self.cfg, self.store, self.clock = repo, cfg, store, clock
        self.model_dir = Path(model_dir)
        self.registry = Registry(self.model_dir)
        self.runner = runner or SubprocessRunner(self.model_dir)
        self.stats = stats or PredictionStats()
        self.plan = SplitPlan(min_stations=cfg.min_stations)

    # ---- scheduling helpers ------------------------------------------------------------------
    def _due(self, key: str, now: datetime) -> bool:
        state = self.repo.get_state(key)
        return state is None or pd.Timestamp(state["at"]) <= pd.Timestamp(now)

    def _schedule(self, key: str, now: datetime, hours: float) -> None:
        self.repo.set_state(key, {"at": (pd.Timestamp(now) + pd.Timedelta(hours=hours)).isoformat()})

    # ---- the loop body ---------------------------------------------------------------------------
    def tick(self) -> dict:
        now = self.clock()
        out: dict = {"at": now.isoformat()}
        for name, step in (("recover", self.recover), ("safety", self.safety),
                           ("monitor", self.monitor), ("training", self.check_training)):
            try:
                out[name] = step(now)
            except Exception as e:   # a failing step must never stop the loop or serving
                log.exception("Lifecycle step %s failed", name)
                out[name] = {"error": f"{type(e).__name__}: {e}"[:300]}
        return out

    def recover(self, now: datetime) -> dict:
        if self.runner.running():
            return {"interrupted": 0}
        n = self.repo.mark_interrupted()
        if n:
            self.repo.add_event("RECOVERY", f"{n} orphaned training run(s) marked INTERRUPTED", None)
        return {"interrupted": n}

    def _fall_back(self, reason: str, event_type: str) -> str:
        """Previous model if one exists, otherwise the persistence baseline. Returns what is now active."""
        try:
            pointer = self.registry.rollback()
            msg = f"rolled back to {pointer['version']}: {reason}"
            active = pointer["version"]
        except (ValueError, KeyError):
            self.registry.deactivate(reason)
            msg = f"deactivated; persistence baseline serving: {reason}"
            active = "persistence"
        self.repo.add_event(event_type, msg, {"reason": reason})
        if self.store is not None:
            self.store.refresh()
        self.stats.clear()
        log.warning(msg)
        return active

    def safety(self, now: datetime) -> dict:
        if self.store is None:
            return {"skipped": "no model store in this process"}
        self.store.refresh()
        if self.store.load_error and self.registry.active():
            return {"action": self._fall_back(f"active model failed to load ({self.store.load_error})", "LOAD_ERROR_FALLBACK")}
        exceeded, counts = failure_rate_exceeded(self.stats.snapshot(), now, self.cfg)
        if exceeded and self.registry.active():
            return {"action": self._fall_back(f"prediction failures {counts['failures']}/{counts['requests']} "
                                              f"in {self.cfg.failure_window_min} min", "FAILURE_RATE_FALLBACK"), **counts}
        return {"ok": True, **counts}

    def monitor(self, now: datetime) -> dict:
        if not self._due("next_monitor", now):
            return {"skipped": "not due"}
        self._schedule("next_monitor", now, self.cfg.monitor_interval_h)
        active = self.registry.active()
        if not active:
            return {"skipped": "no active model (persistence baseline serving)"}
        model = self.store.model if self.store is not None and self.store.version == active["version"] \
            else joblib.load(self.model_dir / active["model_file"])
        history = self.repo.load_history(now - timedelta(hours=self.cfg.monitor_window_h + 192))
        perf = monitor.recent_performance(model, active, history, now, self.cfg)
        streak_state = self.repo.get_state("degradation") or {}
        streak = streak_state.get("streak", 0) if streak_state.get("version") == active["version"] else 0
        decision = decide_degradation(perf, streak, self.cfg)
        self.repo.set_state("degradation", {"version": active["version"], "streak": decision.streak,
                                            "last": perf, "reason": decision.reason, "checked_at": now.isoformat()})
        self.repo.add_event("MONITOR", decision.reason, {"version": active["version"], **perf})
        if not decision.rollback:
            return {"perf": perf, "decision": decision.reason}

        # Roll back to the previous model only if it beats persistence on the same window; else baseline.
        previous = next((h["previous"] for h in reversed(self.registry.history())
                         if h["version"] == active["version"] and h.get("previous")), None)
        if previous:
            prev_meta = self.registry.metadata(previous)
            prev_model = joblib.load(self.model_dir / previous / "model.joblib")
            prev_mae = monitor.mae_on_same_rows(prev_model, prev_meta, history, active["dataset"]["last_ts"], now, self.cfg)
            if prev_mae is not None and prev_mae < perf["persistence_mae"]:
                self.registry.rollback()
                msg = f"rolled back {active['version']} -> {previous} ({decision.reason}; previous MAE {prev_mae:.2f})"
                self.repo.add_event("ROLLBACK", msg, {"from": active["version"], "to": previous, "perf": perf})
                self.repo.set_state("degradation", {"version": previous, "streak": 0, "checked_at": now.isoformat()})
                if self.store is not None:
                    self.store.refresh()
                return {"perf": perf, "action": f"rollback to {previous}"}
        self.registry.deactivate(decision.reason)
        self.repo.add_event("ROLLBACK", f"deactivated {active['version']}; persistence baseline serving ({decision.reason})",
                            {"from": active["version"], "to": None, "perf": perf})
        self.repo.set_state("degradation", {"version": None, "streak": 0, "checked_at": now.isoformat()})
        if self.store is not None:
            self.store.refresh()
        return {"perf": perf, "action": "deactivated"}

    def check_training(self, now: datetime) -> dict:
        if not self._due("next_readiness_check", now):
            return {"skipped": "not due"}
        if self.runner.running() or not self.repo.lock_is_free():
            return {"skipped": "a training job is already running"}
        self._schedule("next_readiness_check", now, self.cfg.readiness_interval_h)

        raw = self.repo.load_history(now - timedelta(days=self.cfg.history_days))
        regular = regularize(raw, HOURLY.freq, HOURLY.max_gap)
        readiness = assess(regular, self.plan, HOURLY.step, "h")
        quality = self.repo.quality_summary(now - timedelta(days=7))
        fingerprint = data_last = None
        if readiness.ready:
            from train import dataset_fingerprint
            fingerprint, data_last = dataset_fingerprint(raw), raw["ts"].max()
        decision = decide_training(now, readiness.ready, readiness.reason, fingerprint, data_last,
                                   self.repo.runs(50), self.cfg)

        previous = self.repo.get_state("readiness") or {}
        current = {"ready": readiness.ready, "reason": readiness.reason, "span_hours": readiness.span_steps,
                   "required_hours": readiness.required_steps, "stations_total": readiness.stations_total,
                   "stations_usable": readiness.stations_usable, "first_ts": readiness.first_ts,
                   "last_ts": readiness.last_ts, "quality_7d": quality, "checked_at": now.isoformat(),
                   "decision": decision.reason}
        self.repo.set_state("readiness", current)
        if previous.get("ready") != readiness.ready or previous.get("first_ts") is None:
            self.repo.add_event("READINESS", f"readiness {'met' if readiness.ready else 'not met'}: {readiness.reason}",
                                {k: current[k] for k in ("span_hours", "required_hours", "stations_usable")})
        if decision.train:
            self.runner.start(decision.trigger, decision.attempt)
            self.repo.add_event("TRAINING_TRIGGERED", f"{decision.trigger} (attempt {decision.attempt}): {decision.reason}", None)
        return {"readiness": readiness.ready, "decision": asdict(decision)}

    # ---- visibility -------------------------------------------------------------------------------
    def status(self, runs: int = 20, events: int = 30) -> dict:
        active = self.registry.active()
        history = self.registry.history()
        all_runs = self.repo.runs(runs)
        last_success = next((r for r in all_runs if r["status"] == "PROMOTED"), None)
        last_completed = next((r for r in all_runs if r["status"] in ("PROMOTED", "NOT_PROMOTED")), None)
        return {
            "enabled": True,
            "forecasting_method": f"MODEL ({active['version']})" if active else "BASELINE_PERSISTENCE",
            "active_model": {"version": active["version"], "model_name": active.get("model_name"),
                             "activated_at": active.get("activated_at")} if active else None,
            "training_running": any(r["status"] == "RUNNING" for r in all_runs),
            "last_successful_training": last_success["finished_at"] if last_success else None,
            "last_completed_training": last_completed["finished_at"] if last_completed else None,
            "next_readiness_check": (self.repo.get_state("next_readiness_check") or {}).get("at"),
            "next_monitor_check": (self.repo.get_state("next_monitor") or {}).get("at"),
            "readiness": self.repo.get_state("readiness"),
            "monitoring": self.repo.get_state("degradation"),
            "training_runs": all_runs,
            "registry_history": history[-20:],
            "events": self.repo.events(events),
        }


def start_background(manager: LifecycleManager, stop: threading.Event) -> threading.Thread:
    def loop():
        log.info("ML lifecycle loop started (tick every %ss)", manager.cfg.tick_seconds)
        while not stop.is_set():
            manager.tick()
            stop.wait(manager.cfg.tick_seconds)
    t = threading.Thread(target=loop, name="ml-lifecycle", daemon=True)
    t.start()
    return t
