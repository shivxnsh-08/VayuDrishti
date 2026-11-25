# VayuDrishti

**See the Air. Predict the Future.**

VayuDrishti is an AI-powered Air Quality Monitoring and Forecasting Platform. It ingests official
Indian air-quality data every 15 minutes, serves live station AQI on a map and API, forecasts the next
24 hours per monitoring station, and sends each user a personalised, conservative Telegram alert based
on their location and health profile (asthma, child, elderly, outdoor exerciser).

> General guidance only, not medical advice. VayuDrishti does not diagnose, prescribe or make
> health-outcome claims.

## Status

| Area | Status |
|---|---|
| Live ingestion (data.gov.in CPCB feed, polled every 15 min; the feed publishes hourly snapshots, typically covering 500+ stations) with data-quality flags | **Implemented, running** |
| Station map, current AQI, history, nearest-station search (PostGIS), Redis cache | **Implemented** |
| Accounts (JWT), health profiles, Telegram linking, alert engine with dedupe/cooldowns/quiet hours | **Implemented** |
| AQI event alerts on Telegram: threshold crossed, deterioration, recovery, and "still above your level" reminders at most every 8 h; quiet hours respected, Severe alerts may bypass them for users who opt in | **Implemented** |
| Daily Telegram morning briefing: station AQI, category, main pollutant, data age, personal health guidance and forecast outlook; default 07:00 IST, time and on/off set in the profile; at most one per user per day | **Implemented** |
| Dedicated admin account (`ADMIN_EMAIL` / `ADMIN_PASSWORD`) with an Admin Dashboard (user statistics, recent registrations) and read-only User Management (search, role/status filters, sorting, per-user details); admin APIs for ingestion runs and the ML lifecycle | **Implemented** |
| 24-hour forecast API with readiness checks (24 valid hourly observations, fresh data) | **Implemented** |
| Forecasting method in service today | **Persistence baseline** (last value carried forward). No trained model is deployed yet. |
| ML pipeline: Random Forest and LightGBM candidates, chronological train/validation/test, promotion gate vs baselines, model registry with rollback | **Implemented and tested; waiting for data.** Training needs ~28 days (672 h) of continuous hourly history; see [docs/ml-forecasting.md](docs/ml-forecasting.md) |
| Automated ML lifecycle: readiness monitoring, scheduled training, promotion, retraining, live monitoring and rollback | **Running.** Checks readiness every 6 h; first training starts automatically once 672 h of history exist |
| Historical backfill from OpenAQ | Implemented, **not yet run** (needs an OpenAQ API key) |
| Rule-based health advice (EN/HI), optional LLM phrasing with output validation | **Implemented** |
| Monitoring: Micrometer, Prometheus, Grafana dashboard | **Implemented** |
| 48-72 h or 7-day forecasting, weather features | **Planned**, not implemented |

## Technology

* **Backend:** Java 21, Spring Boot 3 (Web, Security with JWT, Data JPA/JDBC, Actuator, Scheduling), Resilience4j, Flyway
* **AI/ML service:** Python 3.11, FastAPI, pandas, scikit-learn (Random Forest), LightGBM, optional Groq LLM for wording
* **Data:** PostgreSQL 16 + PostGIS, Redis 7
* **Frontend:** React 18, TypeScript, Vite, Tailwind CSS, Recharts, Leaflet
* **Deployment and monitoring:** Docker Compose, GitHub Actions CI, Prometheus, Grafana

## Why

AQI apps show a number, but a parent of an asthmatic child, an elderly person and a morning
runner each need different advice, and they need it *before* the bad air arrives. Raw station
data is also messy: sensors go offline, freeze on one value or report impossible readings.

What is different here (and measurable): rule-based personalised alerts, a forecast measured
against simple baselines, alert-quality evaluation by replay, and the engineering around data
quality and failure handling.

## Architecture

```
 data.gov.in (real-time AQI)                         Telegram Bot API
        |                                                   ^
        v                                                   | send (retry, backoff, dedupe)
 +------------------------------+       +--------------------------------------------+
 | Ingestion (Spring @Scheduled)|       | Alert engine (Spring)                      |
 | fetch, validate, dedupe,     |       | rules x profile, cooldown, quiet hours,    |
 | quality flags, hourly AQI    |       | AQI events, morning briefing,              |
 |                              |       | outbox + dispatcher                        |
 +--------------+---------------+       +----------------------+---------------------+
                | JDBC                                         ^ forecasts / advice
                v                                              |
 +----------------------------------------------------------------------------------+
 | PostgreSQL + PostGIS: stations, readings, station_aqi, forecasts, users, alerts  |
 +-------+----------------------------------------------------------+---------------+
         ^                                                          | HTTP: timeouts, retry,
         | REST (JWT)                                               | circuit breaker
 +-------+-------------+                               +------------v---------------+
 | Public API (Spring) |------- Redis cache ---------->| Forecast + advice service  |
 | stations, AQI,      |                               | FastAPI. Live forecast:    |
 | profile, alerts,    |                               | persistence baseline;      |
 | admin (ADMIN role)  |                               | LightGBM once promoted.    |
 +---------------------+                               | Advice: rules decide;      |
                                                       | optional Groq phrasing     |
                                                       +----------------------------+
 Metrics: Micrometer -> Prometheus -> Grafana
```

| Folder | What |
|---|---|
| [frontend/](frontend/) | React + TypeScript UI: dashboard, live AQI map, forecast, alerts, profile; separate admin interface |
| [backend/](backend/) | Spring Boot 3 / Java 21: ingestion, PostGIS geo lookup, auth, profiles, alert engine, morning briefing, Telegram, admin APIs |
| [forecast-service/](forecast-service/) | FastAPI / Python 3.11: cleaning, features, baselines (persistence baseline serves forecasts today), LightGBM quantile model pipeline (not yet promoted), advice rules + LLM phrasing |
| [contracts/](contracts/) | Example payloads for the two internal endpoints, checked by both test suites |
| [evaluation/](evaluation/) | Backtest (X2-X5), alert replay (X6), data-quality audit (X1) |
| [monitoring/](monitoring/) | Prometheus config and Grafana dashboard (provisioned automatically) |
| [load/](load/) | k6 script for the with/without-cache latency comparison (X7) |
| [docs/](docs/) | Design decisions, API examples, degraded-mode demo |

## Run it

Requires Docker with Compose.

```bash
cp .env.example .env          # then set JWT_SECRET (and DATA_GOV_API_KEY, TELEGRAM_BOT_TOKEN, ADMIN_EMAIL/ADMIN_PASSWORD when you have them)
docker compose up --build
```

* Web app: http://localhost:5173
* API: http://localhost:8080 (see [docs/api.md](docs/api.md))
* Forecast service: http://localhost:8000/docs
* Grafana: http://localhost:3000 (dashboard "VayuDrishti"; sign in as admin, password `GRAFANA_ADMIN_PASSWORD`), Prometheus: http://localhost:9090

All ports, including the API (8080), are bound to localhost only: other devices on the network cannot reach the
API, `/actuator/prometheus` or the OpenAPI docs. Prometheus and nginx reach the backend over the compose network.

Architecture: [docs/architecture.md](docs/architecture.md). Start/stop, tests, troubleshooting and `.env` guide:
[docs/operations.md](docs/operations.md).

Ingestion status (scheduler, retries, freshness, alerts): `curl -s http://127.0.0.1:8080/api/v1/ingestion/health`
and the dashboard's "Data pipeline" card; see [docs/ingestion-reliability.md](docs/ingestion-reliability.md).
Services restart automatically (`restart: unless-stopped`); enable Docker Desktop's "Start when you sign in"
so they also come back after a reboot.

Without a data.gov.in key, ingestion records SKIPPED runs; without a Telegram token, alerts stay
PENDING; without a Groq key, advice uses the fixed templates. Without a trained model, forecasts use
the persistence baseline and `/model-info` says so.

### Train a model

Ingestion must run for a while first (it builds the hourly history). Then:

```bash
docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_station_aqi.sql > history.csv
cd forecast-service && pip install -r requirements-dev.txt
python train.py --data ../history.csv      # refuses until ~28 days of history; promotes only if it beats every baseline and the active model
docker compose restart forecast-service
```

Until enough hourly history exists, train and evaluate on the daily historical dataset
(`--freq daily`, see [evaluation/README.md](evaluation/README.md)).

### Tests

```bash
cd backend && ./mvnw verify              # unit tests + Testcontainers/WireMock integration tests (needs Docker)
cd forecast-service && pytest            # features, leakage, backtest split, advice validation, API, contracts
cd frontend && npm test                  # Vitest unit tests (AQI bands, city ranking, advice rules, retry logic)
```

## Results

**No measured results yet.** Fill this table only from `evaluation/results/*/summary.md` and
quote the dataset dates, station count, test period and hardware from `meta.json`.

| Metric | How measured | Result |
|---|---|---|
| 24 h forecast MAE vs baselines | rolling-origin backtest, per station (X2) | _TBD_ |
| Category accuracy | confusion matrix (X3) | _TBD_ |
| Feature-group ablation | X4 | _TBD_ |
| q10-q90 interval coverage (nominal 80%) | X5 | _TBD_ |
| Alert precision / recall / lead time | replay (X6) | _TBD_ |
| API p50 / p95 with vs without Redis | k6 (X7) | _TBD_ |
| Ingestion success rate, data-gap rate | admin endpoint / Grafana | _TBD_ |
| Degraded mode | [docs/degraded-mode-demo.md](docs/degraded-mode-demo.md) (X8) | _TBD_ |

## Data sources, licence and attribution

* Real-time AQI: data.gov.in, "Real time Air Quality Index from various locations" (Central
  Pollution Control Board), resource `3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69` (override with
  `DATA_GOV_RESOURCE_ID`). Field names, timestamp format and `NA` handling were checked against a real
  response on 2026-11-22 ([fixture notes](backend/src/test/resources/fixtures/README.md)). The feed holds
  only the current snapshot, updated hourly. data.gov.in rate limits are not documented in this project;
  none were hit in the first days of operation, and the client honours `Retry-After` on HTTP 429.
* Historical daily AQI of major cities (Ministry of Environment, Forest and Climate Change), via
  data.gov.in: optional, used only for cold-start evaluation. It is not bundled or loaded automatically;
  check its resource, columns and licence when you download it ([evaluation/README.md](evaluation/README.md)).
* Data licence: data.gov.in publishes datasets under the Government Open Data License - India
  (GODL-India), which permits reuse with attribution. The licence shown on each dataset's page applies;
  this project has not recorded a per-dataset check. Keep this attribution in the README and dashboard.
* The [MIT License](LICENSE) covers this project's source code only. It does not apply to the data above,
  which stays under its publishers' terms, or to third-party dependencies, which keep their own licences.

## Honest limitations

* The real-time feed is a snapshot; hourly history only exists from the day ingestion starts, and hours
  missed while the stack is down cannot be recovered from it (see [docs/ingestion-reliability.md](docs/ingestion-reliability.md)).
* Station coordinates come from the feed; stations without coordinates are rejected until added.
* AQI breakpoints, category bands, default alert thresholds and advice rules are configuration. Their
  status is tracked in [docs/aqi-verification.md](docs/aqi-verification.md): the feed's values were
  verified empirically to be sub-indices; category bands match a secondary source only; breakpoints, the
  minimum-pollutant rule and the advice rules have not yet been checked against a primary CPCB document.
* The forecast uses only past AQI and calendar features; no weather. It will miss sudden events
  (festival fireworks, dust storms, crop-fire plumes) that past values do not foreshadow.
* For a crash between "sent" and "marked sent", the alert is marked FAILED rather than resent
  (at-most-once). Rarely, an alert may be lost; a duplicate is never sent.
* Alerts are delivered only via Telegram.

## Privacy

Only coarse location is stored (coordinates rounded to 2 decimals, about 1 km), plus the Telegram
chat id; no phone numbers. `DELETE /api/v1/me` removes the account, profile, link codes and alert
history. Readings and station data contain no personal data. Retention: personal data is kept until
the user deletes the account (set a policy before any public launch).

## Decisions taken while scaffolding (spec section 19)

| Item | Choice | Change by |
|---|---|---|
| Build tool / package | Maven (wrapper included), `com.saanscheck` (pre-rename identifier, kept for compatibility) | see "Naming" below |
| Dashboard | Grafana only | add Streamlit later if wanted |
| Alert languages | English and Hindi | `language` in the profile |
| LLM | Groq, optional | `GROQ_API_KEY`, `GROQ_MODEL` |
| Focus area | configurable | `INGESTION_STATE_FILTER` |

See [docs/ml-forecasting.md](docs/ml-forecasting.md) for the ML pipeline, data readiness and model rollback, [docs/design-decisions.md](docs/design-decisions.md) for the reasoning behind each module, [docs/audit-2026-11-22.md](docs/audit-2026-11-22.md) for the audit fixes and measured test results, and [docs/aqi-verification.md](docs/aqi-verification.md) for what is and is not verified about the AQI standard.

## Naming

The project was renamed from SaansCheck to **VayuDrishti**. User-facing text uses the new name. Technical
identifiers that still contain `saanscheck` (Java package, `saanscheck.*` configuration keys, database name
and user, Compose project/volume names, metric names, JWT issuer, Redis key prefix, the applied Flyway
migration) were kept on purpose: renaming them would break the existing database, volumes, dashboards,
logged-in sessions or migration checksums, and gives users nothing.
