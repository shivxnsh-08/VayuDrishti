"""Training pipeline: readiness gate, chronological splits, Random Forest + conformal intervals,
promotion gate, registry rollback, hot reload and prediction-failure handling.

All data here is synthetic and exists only to exercise the code paths; no result from it is reported.
"""
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.model_store import ModelStore
from features.build import HOURLY, build_rows
from features.cleaning import regularize
from models.random_forest import RandomForestModel
from models.lgbm import feature_columns
from models.readiness import SplitPlan, assess
from models.registry import Registry
from tests.conftest import synthetic_hourly
from train import chronological_split
from train import main as train_main

ROOT = Path(__file__).resolve().parent.parent
SMALL = ["--warmup-steps", "24", "--min-train-steps", "72", "--val-steps", "48", "--test-steps", "48", "--quick"]


def _csv(tmp_path, df, name="history.csv"):
    p = tmp_path / name
    df.to_csv(p, index=False)
    return str(p)


def random_walk(days=12, stations=("a", "b"), seed=3):
    """Persistence is the optimal forecast for a random walk: no model should beat it."""
    rng = np.random.default_rng(seed)
    idx = pd.date_range("2026-01-01 00:30", periods=days * 24, freq="h", tz="UTC")
    frames = []
    for sid in stations:
        walk = np.clip(200 + np.cumsum(rng.normal(0, 6, len(idx))), 0, 500)
        frames.append(pd.DataFrame({"station_id": sid, "ts": idx, "aqi": walk.round()}))
    return pd.concat(frames, ignore_index=True)


# ---- readiness ------------------------------------------------------------------------------

def test_six_hours_of_history_is_refused_without_writing_a_model(tmp_path):
    """Mirrors the real state on 2026-11-22: 6 hourly observations per station."""
    df = synthetic_hourly(days=1).groupby("station_id").head(6)
    out = tmp_path / "models"
    report = train_main(["--data", _csv(tmp_path, df), "--out", str(out)])
    assert report["status"] == "INSUFFICIENT_DATA"
    assert report["readiness"]["span_steps"] == 6
    assert report["readiness"]["required_steps"] == 672
    assert "more days of continuous data" in report["readiness"]["reason"]
    assert not (out / "current.json").exists()
    assert json.loads((out / "last_training_report.json").read_text())["status"] == "INSUFFICIENT_DATA"


def test_imputed_hours_do_not_count_toward_readiness():
    df = synthetic_hourly(days=3, stations=("a",))
    df = df[df.index % 3 == 0]   # every third hour only; gaps of 2 get forward-filled
    reg = regularize(df, "h", 3)
    r = assess(reg, SplitPlan(warmup_steps=1, min_train_steps=1, val_steps=1, test_steps=1))
    assert r.valid_station_hours == len(df)


# ---- splits ---------------------------------------------------------------------------------

def test_chronological_split_has_no_leakage():
    rows = build_rows(regularize(synthetic_hourly(days=10), "h", 3), HOURLY)
    plan = SplitPlan(warmup_steps=24, min_train_steps=72, val_steps=48, test_steps=48)
    train, val, test, bounds = chronological_split(rows, plan)
    val_start, test_start = pd.Timestamp(bounds["validation"][0]), pd.Timestamp(bounds["test"][0])
    assert train["target_ts"].max() < val_start            # no training target inside validation/test
    assert val["origin_ts"].min() >= val_start
    assert val["target_ts"].max() < test_start              # selection never sees test targets
    assert test["origin_ts"].min() >= test_start
    assert len(set(train.index) & set(val.index)) == 0 and len(set(val.index) & set(test.index)) == 0


# ---- Random Forest + conformal intervals ----------------------------------------------------

def test_random_forest_conformal_intervals():
    rows = build_rows(regularize(synthetic_hourly(days=12), "h", 3), HOURLY)
    plan = SplitPlan(warmup_steps=24, min_train_steps=72, val_steps=72, test_steps=48)
    train, val, test, _ = chronological_split(rows, plan)
    m = RandomForestModel(feature_columns(HOURLY), {"n_estimators": 30}).fit(train)
    counts = m.calibrate(val, coverage=0.8)
    assert set(m.offsets) == {h for h, n in counts.items() if n >= 30}
    q = m.predict(test)
    assert (q["q10"] <= q["q50"]).all() and (q["q50"] <= q["q90"]).all()
    coverage = ((test["y"] >= q["q10"]) & (test["y"] <= q["q90"])).mean()
    assert 0.5 < coverage <= 1.0   # measured, not assumed; train.py stores the measured value


def test_uncalibrated_horizons_get_no_interval():
    rows = build_rows(regularize(synthetic_hourly(days=8), "h", 3), HOURLY)
    m = RandomForestModel(feature_columns(HOURLY), {"n_estimators": 10}).fit(rows)
    q = m.predict(rows.head(50))
    assert q["q10"].isna().all() and q["q50"].notna().all()


# ---- promotion gate, registry, rollback -----------------------------------------------------

# Multi-day level swings: structure a forecaster should exploit and the 7-day same-hour mean cannot. With the
# default small drift the seasonal baseline is near-optimal and promotion was a coin flip (seed-dependent).
LEARNABLE = {"days": 14, "drift_amp": 90.0}


def test_predictable_data_is_promoted_with_full_metadata(tmp_path):
    out = tmp_path / "models"
    report = train_main(["--data", _csv(tmp_path, synthetic_hourly(**LEARNABLE)), "--out", str(out), *SMALL])
    assert report["status"] == "PROMOTED", report.get("promotion")
    t = report["test"]
    assert t["model_mae"] < t["baselines"]["persistence"]["mae"]
    assert set(t["by_horizon"]) == {1, 3, 6, 12, 24}
    meta = Registry(out).active()
    assert meta["version"] == report["version"] and meta["promotion"]["passed"]
    assert meta["dataset"]["fingerprint"] and meta["dataset"]["rows"]["test"] > 0
    assert meta["intervals"]["test_coverage"] is not None
    assert (out / meta["model_file"]).exists()


def test_random_walk_is_not_promoted_and_baseline_stays(tmp_path):
    out = tmp_path / "models"
    report = train_main(["--data", _csv(tmp_path, random_walk()), "--out", str(out), *SMALL])
    assert report["status"] == "EVALUATED_NOT_PROMOTED"
    assert any("persistence" in r for r in report["promotion"]["reasons"])
    assert not (out / "current.json").exists()          # persistence baseline remains in service


def test_identical_retrain_does_not_replace_active_model(tmp_path):
    data = _csv(tmp_path, synthetic_hourly(**LEARNABLE))
    out = tmp_path / "models"
    first = train_main(["--data", data, "--out", str(out), "--models", "random_forest", *SMALL])
    assert first["status"] == "PROMOTED"
    second = train_main(["--data", data, "--out", str(out), "--models", "random_forest", *SMALL])
    assert second["status"] == "EVALUATED_NOT_PROMOTED"
    assert any("active model" in r for r in second["promotion"]["reasons"])
    assert Registry(out).active()["version"] == first["version"]


def _fake_version(reg: Registry, version: str, passed: bool):
    reg.version_dir(version)
    (reg.root / version / "model.joblib").write_bytes(b"")
    reg.save_candidate(version, {"version": version, "freq": "hourly", "promotion": {"passed": passed}})


def test_registry_promote_rollback_activate_deactivate(tmp_path):
    reg = Registry(tmp_path)
    _fake_version(reg, "random_forest-A", True)
    _fake_version(reg, "random_forest-B", True)
    _fake_version(reg, "random_forest-C", False)
    reg.promote("random_forest-A", "first")
    reg.promote("random_forest-B", "better")
    assert reg.active()["version"] == "random_forest-B"
    assert reg.rollback()["version"] == "random_forest-A"
    with pytest.raises(ValueError):
        reg.activate("random_forest-C")             # never passed the gate
    with pytest.raises(KeyError):
        reg.activate("does-not-exist")
    reg.deactivate()
    assert reg.active() is None
    actions = [h["action"] for h in reg.history()]
    assert actions == ["promote", "promote", "rollback", "deactivate"]


# ---- serving: hot reload, model forecasts, failures -----------------------------------------

def _settings(model_dir):
    return Settings(model_dir=model_dir, rules_file=ROOT / "advice" / "rules.yaml", groq_api_key=None,
                    groq_model="unused", llm_timeout_s=1)


def _readings(hours=24 * 8):
    df = synthetic_hourly(days=10, stations=("s1",)).tail(hours)
    return [{"ts": t.isoformat(), "aqi": int(a)} for t, a in zip(df["ts"], df["aqi"])]


def test_service_serves_promoted_model_and_picks_up_rollback_without_restart(tmp_path):
    out = tmp_path / "models"
    client = TestClient(create_app(_settings(out), use_llm=False))
    assert client.get("/model-info").json()["model_loaded"] is False

    report = train_main(["--data", _csv(tmp_path, synthetic_hourly(days=14)), "--out", str(out),
                         "--models", "random_forest", *SMALL])
    assert report["status"] == "PROMOTED"

    info = client.get("/model-info").json()                    # same app instance: hot reload
    assert info["model_loaded"] and info["version"] == report["version"]
    assert info["model_name"] == "random_forest"
    body = client.post("/forecast", json={"station_id": "s1", "readings": _readings()}).json()
    assert body["method"] in ("MODEL", "BASELINE_PERSISTENCE", "BASELINE_SAME_HOUR_YESTERDAY",
                              "BASELINE_SAME_HOUR_7D_MEAN")   # per-station fallback is allowed
    if body["method"] == "MODEL":
        assert body["model_version"] == report["version"]
    assert len(body["predictions"]) == 24

    Registry(out).deactivate()
    assert client.get("/model-info").json()["model_loaded"] is False
    assert client.post("/forecast", json={"station_id": "s1", "readings": _readings()}).json()["method"] \
        == "BASELINE_PERSISTENCE"


def test_prediction_failure_returns_503_not_a_fake_forecast(tmp_path):
    client = TestClient(create_app(_settings(tmp_path), use_llm=False))
    store: ModelStore = client.app.state.store

    class Broken:
        def predict(self, rows):
            raise RuntimeError("boom")

    store.refresh()   # no current.json: refresh() is a no-op from here on, so the injected model stays
    store.model, store.metadata = Broken(), {"version": "broken-1", "freq": "hourly"}
    r = client.post("/forecast", json={"station_id": "s1", "readings": _readings()})
    assert r.status_code == 503
    assert "prediction failed" in r.json()["detail"]


def test_rollback_never_reactivates_the_current_version(tmp_path):
    """Regression: re-activating a version recorded itself as 'previous'; rollback must skip that."""
    reg = Registry(tmp_path)
    _fake_version(reg, "random_forest-A", True)
    reg.promote("random_forest-A", "first")
    reg.activate("random_forest-A", "re-activation")
    with pytest.raises(ValueError):
        reg.rollback()
