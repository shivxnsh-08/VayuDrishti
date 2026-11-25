"""Automated ML lifecycle: readiness, scheduling, duplicate prevention, failures/retries, promotion and
rejection, restart recovery, monitoring/rollback, safety fallbacks and visibility.

These tests drive the real LifecycleManager, the real training job and the real train.main (same gate)
against an in-memory repository and a fixed clock. The data is synthetic and only exercises code paths;
nothing here is a real performance result.
"""
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.model_store import ModelStore
from lifecycle.config import LifecycleConfig
from lifecycle.job import run_job
from lifecycle.manager import LifecycleManager
from lifecycle.policy import decide_degradation, decide_training
from lifecycle.repository import InMemoryRepository
from models.registry import Registry

ROOT = Path(__file__).resolve().parent.parent
NOW = datetime(2026, 12, 20, 12, 30, tzinfo=timezone.utc)
CFG = LifecycleConfig(enabled=True, min_stations=2, models="random_forest", origin_stride=6,
                      readiness_interval_h=6, monitor_interval_h=1, monitor_min_rows=50,
                      monitor_min_stations=2, monitor_min_new_hours=24)
QUICK = ["--quick"]


class Clock:
    def __init__(self, t):
        self.t = t

    def __call__(self):
        return self.t

    def advance(self, **kw):
        self.t = self.t + timedelta(**kw)


def seasonal(hours, end=NOW, stations=("s1", "s2", "s3"), seed=7):
    """Daily cycle + a 60-hour swing: learnable from recent lags, but neither persistence nor
    same-hour-yesterday can follow it, so a trained model can genuinely beat every baseline."""
    rng = np.random.default_rng(seed)
    idx = pd.date_range(end=pd.Timestamp(end), periods=hours, freq="h")
    frames = []
    for i, s in enumerate(stations):
        h = (idx - pd.Timestamp("2026-01-01T00:30Z")) / pd.Timedelta(hours=1)
        a = 180 + 40 * i + 60 * np.sin(2 * np.pi * (h % 24) / 24) + 70 * np.sin(2 * np.pi * h / 60) + rng.normal(0, 6, hours)
        frames.append(pd.DataFrame({"station_id": s, "ts": idx, "aqi": np.clip(a, 0, 500).round()}))
    return pd.concat(frames, ignore_index=True)


def random_walk(hours, end=NOW, stations=("s1", "s2", "s3"), seed=3):
    """Persistence is optimal: no model should be promoted."""
    rng = np.random.default_rng(seed)
    idx = pd.date_range(end=pd.Timestamp(end), periods=hours, freq="h")
    return pd.concat([pd.DataFrame({"station_id": s, "ts": idx,
                                    "aqi": np.clip(200 + np.cumsum(rng.normal(0, 6, hours)), 0, 500).round()})
                      for s in stations], ignore_index=True)


class InlineRunner:
    """Runs the real job synchronously (production uses a subprocess)."""

    def __init__(self, repo, model_dir, clock):
        self.repo, self.model_dir, self.clock = repo, model_dir, clock
        self.started = []

    def running(self):
        return False

    def start(self, trigger, attempt):
        self.started.append((trigger, attempt))
        run_job(self.repo, CFG, self.model_dir, trigger, attempt, extra_args=QUICK, now=self.clock())


def make(tmp_path, history, clock=None, store=True):
    clock = clock or Clock(NOW)
    repo = InMemoryRepository(history)
    model_dir = tmp_path / "models"
    model_dir.mkdir(exist_ok=True)
    mgr = LifecycleManager(repo, model_dir, CFG, runner=InlineRunner(repo, model_dir, clock),
                           store=ModelStore(model_dir) if store else None, clock=clock)
    return mgr, repo, clock


def statuses(repo):
    return [r["status"] for r in repo.runs()]


# ---- readiness --------------------------------------------------------------------------------

def test_insufficient_data_never_trains_and_does_not_spam_events(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(6))           # the real situation: 6 hourly observations
    out = mgr.tick()
    assert out["training"]["readiness"] is False
    assert repo.runs() == []
    state = repo.get_state("readiness")
    assert state["ready"] is False and state["span_hours"] == 6 and state["required_hours"] == 672
    clock.advance(hours=7)
    mgr.tick()
    assert repo.runs() == []
    assert [e["event_type"] for e in repo.events()].count("READINESS") == 1


def test_repeated_snapshots_are_not_extra_hours(tmp_path):
    h = seasonal(6)
    dup = pd.concat([h, h, h], ignore_index=True)            # same snapshot ingested three times
    mgr, repo, _ = make(tmp_path, dup)
    mgr.tick()
    assert repo.get_state("readiness")["span_hours"] == 6


def test_readiness_becoming_satisfied_triggers_initial_training_and_promotion(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(200))
    mgr.tick()
    assert repo.runs() == []
    repo.history = seasonal(30 * 24)                          # data has accumulated
    clock.advance(hours=7)
    mgr.tick()
    runs = repo.runs()
    assert len(runs) == 1 and runs[0]["trigger"] == "INITIAL"
    assert runs[0]["status"] == "PROMOTED", runs[0]
    assert runs[0]["dataset_fingerprint"] and runs[0]["model_version"].startswith("random_forest-")
    assert set(runs[0]["metrics"]["by_horizon"]) == {1, 3, 6, 12, 24}
    assert Registry(mgr.model_dir).active()["version"] == runs[0]["model_version"]
    mgr.store.refresh()                                        # what the next /forecast request does
    assert mgr.store.version == runs[0]["model_version"]       # served without restart
    assert "PROMOTION" in [e["event_type"] for e in repo.events()]
    s = mgr.status()
    assert s["forecasting_method"].startswith("MODEL") and s["last_successful_training"] is not None


def test_unchanged_dataset_is_not_retrained(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    clock.advance(hours=7)
    mgr.tick()
    assert len(repo.runs()) == 1
    assert "unchanged" in repo.get_state("readiness")["decision"]


# ---- duplicate prevention ---------------------------------------------------------------------

def test_no_training_while_another_job_holds_the_lock(tmp_path):
    mgr, repo, _ = make(tmp_path, seasonal(30 * 24))
    repo.lock_held = True                                     # a job in another process
    out = mgr.tick()
    assert out["training"] == {"skipped": "a training job is already running"}
    assert run_job(repo, CFG, mgr.model_dir, "MANUAL", 1, extra_args=QUICK, now=NOW) == {"status": "LOCKED"}
    assert repo.runs() == []


def test_no_second_start_while_runner_is_busy(tmp_path):
    mgr, repo, _ = make(tmp_path, seasonal(30 * 24))
    mgr.runner.running = lambda: True
    assert mgr.tick()["training"] == {"skipped": "a training job is already running"}
    assert mgr.runner.started == []


# ---- failures, retries, evaluation failure, rejection ---------------------------------------

def test_training_failure_backs_off_retries_and_stops(tmp_path, monkeypatch):
    import train
    monkeypatch.setattr(train, "main", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("disk full")))
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    assert statuses(repo) == ["FAILED"] and "RuntimeError" in repo.runs()[0]["error"]
    assert repo.runs()[0]["dataset_fingerprint"]

    clock.advance(minutes=30)                                 # readiness not due yet anyway
    mgr.tick()
    assert len(repo.runs()) == 1
    clock.advance(hours=6)                                    # due; backoff (1 h) has passed
    mgr.tick()
    assert [r["attempt"] for r in repo.runs()] == [2, 1] and repo.runs()[0]["trigger"] == "RETRY"
    clock.advance(hours=6)
    mgr.tick()                                                # third failure
    clock.advance(hours=6)
    mgr.tick()
    assert len(repo.runs()) == 3
    assert "waiting for new data" in repo.get_state("readiness")["decision"]
    assert Registry(mgr.model_dir).active() is None           # baseline still serving

    monkeypatch.undo()
    repo.history = seasonal(30 * 24 + 1, end=NOW + timedelta(hours=1))   # new data arrives
    clock.advance(hours=6)
    mgr.tick()
    assert repo.runs()[0]["status"] == "PROMOTED" and repo.runs()[0]["trigger"] == "RETRY"


def test_evaluation_failure_is_recorded_and_nothing_is_promoted(tmp_path, monkeypatch):
    import train
    monkeypatch.setattr(train, "evaluate", lambda preds: (_ for _ in ()).throw(ValueError("metric failed")))
    mgr, repo, _ = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    run = repo.runs()[0]
    assert run["status"] == "FAILED" and "ValueError" in run["error"]
    assert Registry(mgr.model_dir).active() is None
    assert "TRAINING_FAILED" in [e["event_type"] for e in repo.events()]


def test_promotion_rejected_when_model_does_not_beat_persistence(tmp_path):
    mgr, repo, _ = make(tmp_path, random_walk(30 * 24))
    mgr.tick()
    run = repo.runs()[0]
    assert run["status"] == "NOT_PROMOTED"
    assert "persistence" in run["reasons"]
    assert Registry(mgr.model_dir).active() is None
    assert mgr.status()["forecasting_method"] == "BASELINE_PERSISTENCE"


# ---- restart safety -------------------------------------------------------------------------------

def test_restart_during_training_marks_run_interrupted_then_retries(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    repo.insert_run("INITIAL", 1)                             # job was running when the container restarted
    repo.lock_held = True                                     # ...and while it is alive nothing is touched
    mgr.tick()
    assert statuses(repo) == ["RUNNING"]
    repo.lock_held = False                                    # process died: Postgres released the lock
    clock.advance(hours=7)
    mgr.tick()
    runs = repo.runs()
    assert runs[-1]["status"] == "INTERRUPTED"
    assert "RECOVERY" in [e["event_type"] for e in repo.events()]
    assert runs[0]["trigger"] == "RETRY" and runs[0]["status"] == "PROMOTED"


# ---- retraining policy (pure) ------------------------------------------------------------------

def _done(status="PROMOTED", finished=NOW, last=NOW, fp="a"):
    return {"status": status, "finished_at": finished, "data_last_ts": last, "dataset_fingerprint": fp}


@pytest.mark.parametrize("hours_since,new_hours,expected", [
    (24, 100, None),                      # too soon
    (200, 10, None),                      # enough time, not enough new data
    (200, 100, "RETRAIN_NEW_DATA"),
    (800, 5, "RETRAIN_MAX_INTERVAL"),     # max interval with some new data
    (800, 0, None),                       # nothing new at all
])
def test_retraining_triggers(hours_since, new_hours, expected):
    runs = [_done(finished=NOW - timedelta(hours=hours_since), last=NOW - timedelta(hours=new_hours))]
    d = decide_training(NOW, True, "ready", "b", NOW, runs, CFG)
    assert d.trigger == expected and d.train == (expected is not None)


def test_not_ready_and_unchanged_data_never_train():
    assert not decide_training(NOW, False, "6 h of 672", None, None, [], CFG).train
    assert not decide_training(NOW, True, "ok", "a", NOW, [_done(fp="a")], CFG).train


# ---- monitoring and rollback ----------------------------------------------------------------------

def test_monitoring_with_insufficient_evidence_takes_no_action(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    clock.advance(hours=2)                                    # only 2 new hours since training
    repo.history = seasonal(30 * 24 + 2, end=NOW + timedelta(hours=2))
    out = mgr.tick()
    assert "insufficient evidence" in out["monitor"]["decision"]
    assert Registry(mgr.model_dir).active() is not None


def test_sustained_degradation_rolls_back_to_baseline_after_two_checks(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    version = Registry(mgr.model_dir).active()["version"]
    # Afterwards the air becomes flat: persistence is perfect, the daily-cycle model is now clearly worse.
    flat_idx = pd.date_range(start=NOW + timedelta(hours=1), periods=96, freq="h")
    flat = pd.concat([pd.DataFrame({"station_id": s, "ts": flat_idx, "aqi": 150.0}) for s in ("s1", "s2", "s3")])
    repo.history = pd.concat([seasonal(30 * 24), flat], ignore_index=True)
    clock.t = NOW + timedelta(hours=96)
    first = mgr.tick()["monitor"]
    assert "1/2" in first["decision"] and Registry(mgr.model_dir).active()["version"] == version
    clock.advance(hours=1)
    second = mgr.tick()["monitor"]
    assert second["action"] == "deactivated"                  # no previous model -> persistence baseline
    assert Registry(mgr.model_dir).active() is None
    assert any(e["event_type"] == "ROLLBACK" for e in repo.events())


def test_degradation_policy_is_strict():
    ok = {"sufficient": True, "model_mae": 10.0, "persistence_mae": 10.0}
    bad = {"sufficient": True, "model_mae": 11.0, "persistence_mae": 10.0}
    assert not decide_degradation(ok, 1, CFG).rollback and decide_degradation(ok, 1, CFG).streak == 0
    assert decide_degradation(bad, 0, CFG).streak == 1 and not decide_degradation(bad, 0, CFG).rollback
    assert decide_degradation(bad, 1, CFG).rollback
    assert decide_degradation({"sufficient": False, "reason": "few rows"}, 1, CFG).streak == 1


# ---- safety fallbacks -------------------------------------------------------------------------------

def test_model_that_fails_to_load_falls_back(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    active = Registry(mgr.model_dir).active()
    (mgr.model_dir / active["model_file"]).write_bytes(b"corrupted")
    Registry(mgr.model_dir).activate(active["version"], "re-point to trigger reload")
    mgr.store.refresh()
    assert mgr.store.load_error
    out = mgr.tick()
    assert out["safety"]["action"] == "persistence"
    assert "LOAD_ERROR_FALLBACK" in [e["event_type"] for e in repo.events()]


def test_high_prediction_failure_rate_falls_back_but_low_rate_does_not(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(30 * 24))
    mgr.tick()
    for _ in range(25):
        mgr.stats.record(True, NOW)
    for _ in range(5):
        mgr.stats.record(False, NOW)
    assert mgr.tick()["safety"]["ok"] is True                 # 5/30 failures: below threshold
    for _ in range(30):
        mgr.stats.record(False, NOW)
    assert mgr.tick()["safety"]["action"] == "persistence"
    assert Registry(mgr.model_dir).active() is None


def test_a_failing_step_never_stops_the_loop(tmp_path):
    mgr, repo, _ = make(tmp_path, seasonal(6))
    repo.load_history = lambda since: (_ for _ in ()).throw(ConnectionError("db down"))
    out = mgr.tick()
    assert "ConnectionError" in out["training"]["error"]
    assert "recover" in out and "safety" in out


# ---- visibility ---------------------------------------------------------------------------------------

def test_api_exposes_lifecycle_status(tmp_path):
    mgr, repo, clock = make(tmp_path, seasonal(6), store=False)
    mgr.tick()
    settings = Settings(model_dir=mgr.model_dir, rules_file=ROOT / "advice" / "rules.yaml", groq_api_key=None,
                        groq_model="x", llm_timeout_s=1)
    client = TestClient(create_app(settings, use_llm=False, lifecycle=mgr, start_lifecycle_loop=False))
    status = client.get("/lifecycle").json()
    assert status["forecasting_method"] == "BASELINE_PERSISTENCE"
    assert status["readiness"]["ready"] is False and status["next_readiness_check"]
    info = client.get("/model-info").json()
    assert info["model_loaded"] is False
    assert info["lifecycle"]["readiness"]["required_hours"] == 672
    assert client.get("/health").json()["lifecycle_enabled"] is True
