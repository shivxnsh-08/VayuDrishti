"""Historical backfill: AQI from concentrations, unit handling, station matching, OpenAQ client.

OpenAQ responses below are shaped after the documented v3 schema (docs.openaq.org), not recorded:
no OpenAQ key was available when this was written. Re-record with a real key (VERIFY).
"""
from pathlib import Path

import httpx
import pandas as pd
import pytest
import yaml

from backfill import aqi
from backfill.openaq import OpenAQClient, Result, backfill_station, best_match
from backfill.units import normalize

ROOT = Path(__file__).resolve().parents[2]


def test_breakpoints_match_backend_config():
    backend = yaml.safe_load((ROOT / "backend/src/main/resources/aqi.yml").read_text(encoding="utf-8"))["saanscheck"]["aqi"]
    ours = aqi.config()
    assert set(ours["breakpoints"]) == set(backend["breakpoints"])
    for pollutant, rows in backend["breakpoints"].items():
        assert [(r["c-low"], r["c-high"], r["i-low"], r["i-high"]) for r in rows] == \
               [(r["c_low"], r["c_high"], r["i_low"], r["i_high"]) for r in ours["breakpoints"][pollutant]]
    assert ours["min_pollutants"] == backend["min-pollutants"]
    assert ours["required_any_of"] == backend["required-any-of"]


@pytest.mark.parametrize("pollutant,conc,expected", [
    ("PM25", 0, 0), ("PM25", 30, 50), ("PM25", 30.5, 50), ("PM25", 31, 51), ("PM25", 45.5, 76),
    ("PM25", 60, 100), ("PM25", 90, 200), ("PM25", 120, 300), ("PM25", 250, 400), ("PM25", 1000, 500),
    ("PM10", 100, 100), ("PM10", 101, 101), ("PM10", 250, 200), ("PM10", 350, 300), ("PM10", 430, 400),
    ("CO", 1.0, 50), ("CO", 2.0, 100), ("CO", 10, 200), ("O3", 168, 200), ("NO2", 180, 200),
])
def test_sub_index_boundaries_match_backend(pollutant, conc, expected):
    # Same expectations as AqiCalculatorTest / AqiBoundaryTest on the Java side.
    assert aqi.sub_index(pollutant, conc) == expected


def test_sub_index_rejects_negative_and_unknown():
    assert aqi.sub_index("PM25", -1) is None
    assert aqi.sub_index("XYZ", 10) is None


def _hours(pollutant, value, n, start="2026-11-01T00:00:00Z"):
    ts = pd.date_range(start, periods=n, freq="h")
    return pd.DataFrame({"ts": ts, "pollutant": pollutant, "value": value})


def test_hourly_aqi_needs_coverage_and_three_pollutants_including_pm():
    long = pd.concat([_hours("PM25", 100, 30), _hours("NO2", 50, 30), _hours("CO", 1.5, 30)])
    out = aqi.hourly_aqi(long)
    # 24-h averages need 16 valid hours: first AQI appears at hour index 15
    assert out["ts"].min() == pd.Timestamp("2026-11-01T15:00:00Z")
    assert (out["dominant_pollutant"] == "PM25").all()
    assert (out["aqi"] == aqi.sub_index("PM25", 100)).all()
    assert (out["pollutant_count"] == 3).all()

    no_pm = pd.concat([_hours("NO2", 50, 30), _hours("CO", 1.5, 30), _hours("O3", 40, 30)])
    assert aqi.hourly_aqi(no_pm).empty


def test_gaps_are_not_imputed():
    pm = _hours("PM25", 100, 30)
    pm = pm[(pm.index < 5) | (pm.index > 20)]           # long gap
    long = pd.concat([pm, _hours("NO2", 50, 30), _hours("CO", 1.5, 30)])
    out = aqi.hourly_aqi(long)
    # never 16 PM values inside any 24-h window -> no AQI at all
    assert out.empty


@pytest.mark.parametrize("param,unit,value,expected", [
    ("pm25", "µg/m³", 42.0, ("PM25", 42.0, None)),
    ("co", "µg/m³", 1500.0, ("CO", 1.5, None)),
    ("co", "mg/m³", 1.5, ("CO", 1.5, None)),
    ("no2", "ppb", 20.0, ("NO2", None, "unconvertible_unit:ppb")),
    ("o3", "ppm", 0.03, ("O3", None, "unconvertible_unit:ppm")),
    ("pm25", "µg/m³", -3.0, ("PM25", None, "invalid_value")),
    ("temperature", "c", 20.0, (None, None, "unsupported_parameter")),
])
def test_unit_normalisation(param, unit, value, expected):
    assert normalize(param, unit, value) == expected


def test_best_match_picks_nearest_fixed_location_within_radius():
    station = {"id": "ito", "latitude": 28.6286, "longitude": 77.2410}
    locs = [
        {"id": 1, "coordinates": {"latitude": 28.6300, "longitude": 77.2420}},        # ~180 m
        {"id": 2, "coordinates": {"latitude": 28.6287, "longitude": 77.2411}, "isMobile": True},
        {"id": 3, "coordinates": {"latitude": 28.7000, "longitude": 77.3000}},        # ~10 km
    ]
    loc, d = best_match(station, locs, 500)
    assert loc["id"] == 1 and 100 < d < 300
    assert best_match(station, locs[2:], 500) == (None, None)


# ---- client against a mocked transport ----------------------------------------------------

def _hour(ts_to, value, units="µg/m³", name="pm25"):
    return {"value": value, "parameter": {"name": name, "units": units},
            "period": {"datetimeTo": {"utc": ts_to}}}


def make_client(handler, **kw):
    sleeps = []
    http = httpx.Client(base_url="https://api.openaq.org/v3", transport=httpx.MockTransport(handler))
    c = OpenAQClient("test-key", http=http, min_interval_s=0, sleep=sleeps.append, **kw)
    return c, sleeps


def test_client_sends_key_header_paginates_and_retries_429():
    calls = []

    def handler(request: httpx.Request):
        calls.append(request)
        assert request.headers["X-API-Key"] == "test-key"
        if len(calls) == 1:
            return httpx.Response(429, headers={"x-ratelimit-reset": "7"})
        page = int(request.url.params["page"])
        n = 2 if page == 1 else 1
        return httpx.Response(200, json={"results": [_hour(f"2026-11-01T0{page}:00:00Z", 10)] * n})

    client, sleeps = make_client(handler, page_limit=2)
    hours = client.sensor_hours(99, "2026-11-01", "2026-11-02")
    assert len(hours) == 3                      # page 1 (2 results) + page 2 (1 result, last page)
    assert 7.0 in sleeps                        # honoured x-ratelimit-reset
    assert calls[-1].url.params["datetime_from"] == "2026-11-01"


def test_client_requires_key():
    with pytest.raises(ValueError):
        OpenAQClient("")


def test_backfill_station_end_to_end_with_skips():
    station = {"id": "ito", "latitude": 28.6286, "longitude": 77.2410}
    location = {"id": 501, "name": "ITO", "coordinates": {"latitude": 28.6287, "longitude": 77.2412},
                "provider": {"name": "CPCB"}, "licenses": [{"name": "CC BY 4.0"}],
                "sensors": [{"id": 1, "parameter": {"name": "pm25", "units": "µg/m³"}},
                            {"id": 2, "parameter": {"name": "no2", "units": "µg/m³"}},
                            {"id": 3, "parameter": {"name": "co", "units": "µg/m³"}},
                            {"id": 4, "parameter": {"name": "o3", "units": "ppb"}}]}
    ts = [f"2026-11-01T{h:02d}:00:00Z" for h in range(24)] + [f"2026-11-02T{h:02d}:00:00Z" for h in range(6)]
    values = {1: 100.0, 2: 50.0, 3: 1500.0, 4: 30.0}
    units = {1: "µg/m³", 2: "µg/m³", 3: "µg/m³", 4: "ppb"}

    def handler(request: httpx.Request):
        if request.url.path.endswith("/locations"):
            return httpx.Response(200, json={"results": [location]})
        sid = int(request.url.path.split("/")[-2])
        return httpx.Response(200, json={"results": [_hour(t, values[sid], units[sid]) for t in ts]})

    client, _ = make_client(handler)
    result = Result()
    backfill_station(client, station, "2026-11-01", "2026-11-03", 500, result)

    assert result.mapping[0]["matched"] and result.mapping[0]["provider"] == "CPCB"
    assert result.skipped["unconvertible_unit:ppb"] == 30
    assert result.aqi_rows, "expected AQI rows once 16 hours of coverage exist"
    first = result.aqi_rows[0]
    assert first["source"] == "openaq" and first["licence"] == "CC BY 4.0"
    assert first["aqi"] == aqi.sub_index("PM25", 100.0)
    assert len(result.aqi_rows) == len(ts) - 15
