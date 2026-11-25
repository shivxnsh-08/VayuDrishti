# VayuDrishti frontend

A demonstration layer for the backend and forecasting service: React 18 + TypeScript + Vite + Tailwind,
Recharts for charts, Leaflet (OpenStreetMap tiles) for the station map. It shows real backend data only
and states plainly when data is missing, stale or insufficient.

| Section | Backend endpoints |
|---|---|
| Dashboard (stats, city rankings, health advice for your station) | `GET /api/v1/stations`, `GET /api/v1/model-info`, `GET /api/v1/me/profile` |
| Live AQI Map (state/city/category/station filters) | `GET /api/v1/stations` |
| Forecast (readiness, model status, summary, hourly strip, chart, advice) | `GET /api/v1/stations/{id}/aqi/current`, `/aqi/history`, `/forecast`, `/stations/nearest`, `/model-info` |
| Alerts | `GET /api/v1/me/alerts` (signed in) |
| Profile | `POST /api/v1/auth/login|register`, `GET/PUT /api/v1/me/profile`, `POST /api/v1/me/telegram/link`, `DELETE /api/v1/me` |

## Run

```bash
# with Docker (served by nginx, which proxies /api to the backend)
docker compose up -d --build --no-deps frontend   # http://localhost:5173 (--no-deps: leave other services alone)

# or for development (Vite proxies /api to http://localhost:8080)
cd frontend && npm install && npm run dev  # http://localhost:5173
```

Run one or the other: both use port 5173. `npm run build` type-checks and builds to `dist/`.

## Notes

- City rankings are computed in the browser from real station data (mean AQI of each city's stations
  reporting now, grouped by city and state). Health advice uses the backend's own rules: `src/lib/advice-rules.json`
  is generated from `forecast-service/advice/rules.yaml` by `python scripts/sync_advice_rules.py` (re-run after
  editing the rules). Neither is AI-generated. Proposed backend endpoints: `docs/frontend-api-gaps.md`.
- Dashboard and map data refresh every 5 minutes (ingestion runs every 15).

- The browser only talks to `/api` on its own origin (Vite proxy in dev, nginx in Docker), so the backend
  needs no CORS settings and the internal forecast service is never exposed.
- The JWT is kept in `localStorage` for convenience in this demo; a production deployment would prefer an
  HttpOnly cookie.
- AQI category colours are for display only; categories always come from the backend configuration.
