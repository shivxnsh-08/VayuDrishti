"""Historical hourly backfill from OpenAQ v3 (real measurements; nothing synthesised).

    export OPENAQ_API_KEY=...   # your own key, from explore.openaq.org; never commit it
    python -m backfill.openaq --stations stations.csv --from 2026-09-01 --to 2026-11-22 --out history_openaq

Inputs : stations.csv with id, latitude, longitude (evaluation/sql/export_stations.sql).
Outputs: <out>_aqi.csv     station_id, ts, aqi, category-free fields, source, location id, provider, licence
         <out>_mapping.csv station -> OpenAQ location match (review before loading)
         <out>_summary.json counts, including skipped values by reason
Load with evaluation/sql/load_historical_aqi.sql into historical_station_aqi (separate from live data).

API facts used (docs.openaq.org, checked 2026-11-22): header X-API-Key; GET /v3/locations with
coordinates=lat,lon & radius (max 25,000 m); GET /v3/sensors/{id}/hours with datetime_from,
datetime_to, limit, page; free tier 60 requests/min and 2,000/hour, HTTP 429 when exceeded,
x-ratelimit-reset header. VERIFY: maximum page size, India/CPCB coverage, each location's licence.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from collections import Counter
from dataclasses import dataclass, field

import httpx
import pandas as pd

from backfill.aqi import hourly_aqi
from backfill.units import PARAMETERS, normalize

BASE_URL = "https://api.openaq.org/v3"


class OpenAQClient:
    """Paced, retrying client. Pacing keeps us under 60 requests/minute; 429 and 5xx are retried
    after ``x-ratelimit-reset`` seconds (or exponential backoff)."""

    def __init__(self, api_key: str, http: httpx.Client | None = None, min_interval_s: float = 1.1,
                 max_retries: int = 5, sleep=time.sleep, page_limit: int = 1000):
        if not api_key:
            raise ValueError("OPENAQ_API_KEY is not set")
        self.http = http or httpx.Client(base_url=BASE_URL, timeout=30)
        self.headers = {"X-API-Key": api_key, "Accept": "application/json"}
        self.min_interval_s = min_interval_s
        self.max_retries = max_retries
        self.sleep = sleep
        self.page_limit = page_limit
        self._last = 0.0
        self.requests = 0

    def _get(self, path: str, params: dict) -> dict:
        for attempt in range(self.max_retries + 1):
            wait = self.min_interval_s - (time.monotonic() - self._last)
            if wait > 0:
                self.sleep(wait)
            self._last = time.monotonic()
            self.requests += 1
            r = self.http.get(path, params=params, headers=self.headers)
            if r.status_code == 429 or r.status_code >= 500:
                if attempt == self.max_retries:
                    r.raise_for_status()
                reset = r.headers.get("x-ratelimit-reset")
                self.sleep(float(reset) if reset and reset.replace(".", "", 1).isdigit() else 2 ** attempt)
                continue
            r.raise_for_status()
            return r.json()
        raise RuntimeError("unreachable")

    def locations_near(self, lat: float, lon: float, radius_m: int) -> list[dict]:
        body = self._get("/locations", {"coordinates": f"{lat},{lon}", "radius": radius_m, "limit": 100})
        return body.get("results", [])

    def sensor_hours(self, sensor_id: int, dt_from: str, dt_to: str) -> list[dict]:
        out, page = [], 1
        while True:
            body = self._get(f"/sensors/{sensor_id}/hours", {"datetime_from": dt_from, "datetime_to": dt_to,
                                                             "limit": self.page_limit, "page": page})
            results = body.get("results", [])
            out.extend(results)
            if len(results) < self.page_limit:
                return out
            page += 1


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def best_match(station: dict, locations: list[dict], max_distance_m: float) -> tuple[dict | None, float | None]:
    """Nearest non-mobile OpenAQ location within max_distance_m."""
    best, best_d = None, None
    for loc in locations:
        c = loc.get("coordinates") or {}
        if loc.get("isMobile") or c.get("latitude") is None:
            continue
        d = haversine_m(station["latitude"], station["longitude"], c["latitude"], c["longitude"])
        if d <= max_distance_m and (best_d is None or d < best_d):
            best, best_d = loc, d
    return best, best_d


def _period_end_utc(h: dict) -> str | None:
    """Hour label = end of the averaging period, in UTC. VERIFY against the live feed's convention."""
    period = h.get("period") or {}
    for node in (period.get("datetimeTo"), h.get("datetimeTo")):
        if isinstance(node, dict) and node.get("utc"):
            return node["utc"]
    return None


@dataclass
class Result:
    aqi_rows: list = field(default_factory=list)
    mapping: list = field(default_factory=list)
    skipped: Counter = field(default_factory=Counter)


def backfill_station(client: OpenAQClient, station: dict, dt_from: str, dt_to: str,
                     max_distance_m: float, result: Result) -> None:
    locations = client.locations_near(station["latitude"], station["longitude"], int(max_distance_m))
    loc, dist = best_match(station, locations, max_distance_m)
    if loc is None:
        result.mapping.append({"station_id": station["id"], "matched": False})
        return
    licences = ";".join(sorted({(l or {}).get("name", "?") for l in loc.get("licenses") or []})) or "unknown"
    provider = (loc.get("provider") or {}).get("name")
    result.mapping.append({"station_id": station["id"], "matched": True, "openaq_location_id": loc.get("id"),
                           "openaq_name": loc.get("name"), "provider": provider, "distance_m": round(dist, 1),
                           "licences": licences})
    rows = []
    for sensor in loc.get("sensors") or []:
        param = ((sensor.get("parameter") or {}).get("name") or "").lower()
        if param not in PARAMETERS:
            continue
        for h in client.sensor_hours(sensor["id"], dt_from, dt_to):
            unit = (h.get("parameter") or {}).get("units") or (sensor.get("parameter") or {}).get("units")
            pollutant, value, reason = normalize(param, unit, h.get("value"))
            ts = _period_end_utc(h)
            if reason or ts is None:
                result.skipped[reason or "no_timestamp"] += 1
                continue
            rows.append({"ts": pd.to_datetime(ts, utc=True), "pollutant": pollutant, "value": value})
    if not rows:
        return
    aqi = hourly_aqi(pd.DataFrame(rows))
    for r in aqi.itertuples():
        result.aqi_rows.append({"station_id": station["id"], "ts": r.ts.isoformat(), "aqi": r.aqi,
                                "dominant_pollutant": r.dominant_pollutant, "pollutant_count": r.pollutant_count,
                                "source": "openaq", "source_location_id": loc.get("id"), "provider": provider,
                                "licence": licences, "method": "cpcb_avg_from_concentrations_v1"})


def main(argv=None) -> dict:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--stations", required=True)
    p.add_argument("--from", dest="dt_from", required=True)
    p.add_argument("--to", dest="dt_to", required=True)
    p.add_argument("--out", required=True, help="output path prefix")
    p.add_argument("--max-distance-m", type=float, default=500.0)
    p.add_argument("--limit-stations", type=int, help="process only the first N stations (trial runs)")
    args = p.parse_args(argv)

    stations = pd.read_csv(args.stations)
    if args.limit_stations:
        stations = stations.head(args.limit_stations)
    client = OpenAQClient(os.getenv("OPENAQ_API_KEY", ""))
    result = Result()
    for st in stations.to_dict("records"):
        try:
            backfill_station(client, st, args.dt_from, args.dt_to, args.max_distance_m, result)
        except httpx.HTTPError as e:   # one failing station must not abort the backfill
            result.skipped[f"station_error:{type(e).__name__}"] += 1
            result.mapping.append({"station_id": st["id"], "matched": None, "error": str(e)[:200]})

    pd.DataFrame(result.aqi_rows).to_csv(f"{args.out}_aqi.csv", index=False)
    pd.DataFrame(result.mapping).to_csv(f"{args.out}_mapping.csv", index=False)
    summary = {"stations": len(stations), "matched": sum(1 for m in result.mapping if m.get("matched")),
               "aqi_rows": len(result.aqi_rows), "skipped": dict(result.skipped), "requests": client.requests,
               "window": [args.dt_from, args.dt_to], "max_distance_m": args.max_distance_m}
    with open(f"{args.out}_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))
    return summary


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
