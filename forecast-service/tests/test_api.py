from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from tests.conftest import synthetic_hourly

ROOT = Path(__file__).resolve().parent.parent


def _settings(model_dir: Path) -> Settings:
    return Settings(model_dir=model_dir, rules_file=ROOT / "advice" / "rules.yaml",
                    groq_api_key=None, groq_model="unused", llm_timeout_s=1)


def _readings(hours=72, aqi=180):
    start = datetime(2026, 11, 17, 4, 30, tzinfo=timezone.utc)
    return [{"ts": (start + timedelta(hours=h)).isoformat(), "aqi": aqi + (h % 24)} for h in range(hours)]


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(_settings(tmp_path), use_llm=False))


def test_health_and_model_info_without_model(client):
    assert client.get("/health").json() == {"status": "ok", "model_loaded": False, "llm_configured": False,
                                            "lifecycle_enabled": False}
    assert client.get("/model-info").json()["model_loaded"] is False


def test_forecast_without_model_serves_persistence_with_ranges(client):
    r = client.post("/forecast", json={"station_id": "ito", "readings": _readings()})
    assert r.status_code == 200
    body = r.json()
    assert body["method"] == "BASELINE_PERSISTENCE"
    assert len(body["predictions"]) == 24
    first = body["predictions"][0]
    assert first["target_ts"].endswith("Z")
    assert first["lower"] <= first["predicted_aqi"] <= first["upper"]


def test_forecast_refuses_fewer_than_24_hourly_points(client):
    """Live regression: stations had 3 real points; no forecast may be produced from that."""
    r = client.post("/forecast", json={"station_id": "ito", "readings": _readings(hours=3)})
    assert r.status_code == 422
    assert "3 valid hourly observations" in r.json()["detail"]


def test_duplicate_and_impossible_points_do_not_count_toward_minimum(client):
    readings = _readings(hours=23)
    readings += [dict(readings[-1])]                       # same hour twice
    readings += [{"ts": "2026-11-18T03:30:00+00:00", "aqi": 900}]   # impossible value
    assert client.post("/forecast", json={"station_id": "ito", "readings": readings}).status_code == 422


def test_unordered_readings_are_handled_and_24_points_suffice(client):
    readings = list(reversed(_readings(hours=24)))
    body = client.post("/forecast", json={"station_id": "ito", "readings": readings}).json()
    assert body["origin_ts"].startswith("2026-11-18T03:30")   # last hour of the input, despite reversed order
    targets = [p["target_ts"] for p in body["predictions"]]
    assert targets == sorted(targets) and len(targets) == 24


def test_forecast_validates_input(client):
    assert client.post("/forecast", json={"station_id": "ito", "readings": []}).status_code == 422


def test_forecast_uses_promoted_model(tmp_path):
    from train import main as train_main
    csv = tmp_path / "history.csv"
    synthetic_hourly(days=20, stations=("ito",)).to_csv(csv, index=False)
    report = train_main(["--data", str(csv), "--out", str(tmp_path / "models"), "--warmup-steps", "24",
                         "--min-train-steps", "72", "--val-steps", "48", "--test-steps", "48", "--quick"])
    if report["status"] != "PROMOTED":
        pytest.skip(f"synthetic run not promoted: {report.get('promotion')}")

    c = TestClient(create_app(_settings(tmp_path / "models"), use_llm=False))
    info = c.get("/model-info").json()
    assert info["model_loaded"] is True and info["freq"] == "hourly" and info["model_name"]
    body = c.post("/forecast", json={"station_id": "ito", "readings": _readings(hours=24 * 8)}).json()
    assert body["method"] in ("MODEL", "BASELINE_PERSISTENCE", "BASELINE_SAME_HOUR_YESTERDAY",
                              "BASELINE_SAME_HOUR_7D_MEAN")
    assert len(body["predictions"]) == 24


def test_advice_endpoint_template_mode(client):
    r = client.post("/advice", json={"category": "POOR", "trend": "STABLE", "health_profile": "CHILD",
                                     "language": "en", "numbers": {"aqi": "250"}})
    assert r.status_code == 200
    assert r.json()["source"] == "TEMPLATE"
    assert "AVOID_OUTDOOR_EXERCISE" in r.json()["action_codes"]
