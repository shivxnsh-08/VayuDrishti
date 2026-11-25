# Design decisions

Short notes for each module: what was chosen, why, and what was traded away. Each section ends
with the interview question it answers.

## 1. Schema, stations and ingestion

* **Readings keyed by (station, pollutant, source timestamp).** The primary key is the dedupe key:
  re-fetching an unchanged snapshot is `INSERT ... ON CONFLICT DO NOTHING`, so hourly runs, retries
  and manual triggers are all idempotent.
* **Raw and cleaned values stored separately.** `readings.raw` keeps the untouched JSON record;
  `value` holds the cleaned number (NULL unless usable); `quality` is OK, MISSING, INVALID or STUCK.
  The data-quality report counts flags instead of silently dropping rows.
* **Stuck-sensor detection**: identical value for `stuck-hours` (6) consecutive readings. Cheap,
  explainable, and it catches the common frozen-sensor failure.
* **Hourly AQI** = max of valid pollutant sub-indices, with the CPCB minimum-pollutant rule (at least 3,
  including PM2.5 or PM10). Bands and breakpoints live in `aqi.yml`, never in code.
* **Hours are bucketed in IST**, not UTC (IST is UTC+5:30, so UTC truncation would shift every hour by 30 min).
* **The payload mapper is the only class that knows the API's field names**; they come from
  `application.yml`. An API change means editing config, not code.
* **Retries only where they help**: network errors, 5xx and 429 are retried with exponential backoff;
  a 403 (bad key) fails immediately. Page 1 failing fails the run; later pages failing make it PARTIAL.
* **The API key is redacted** from exception messages before they reach logs or the database.

*"How do you handle messy sensor data?"*

## 2. Public API, auth, profiles

* **Spring's own resource-server JWT support** (HS256, secret from `JWT_SECRET`), instead of a hand-written
  filter: less security code of our own to get wrong. Stateless; roles in a `roles` claim.
* Login returns the same error for an unknown email and a wrong password (no account enumeration).
* **Coarse location only**: coordinates are rounded to 2 decimals before storage. If the user gives
  coordinates but no station, the nearest station is assigned.
* JPA is used only for `users` (plain CRUD); everything geo or time-series uses SQL via JdbcTemplate.

## 3. PostGIS nearest station and Redis cache

* `geography(Point, 4326)` stores lat/lon on the spheroid, so `ST_Distance` gives metres directly.
* `ORDER BY geom <-> point LIMIT n` is a **KNN search served by the GiST index** (an index-ordered scan,
  not "compute every distance and sort"). `StationGeoIT` checks the plan uses `ix_stations_geom`.
* **Cache**: nearest-station results (1 h TTL, key rounded to about 1 km) and latest AQI (5 min TTL,
  evicted after every ingestion run). The stale flag is computed after the cache read, so caching can
  never hide stale data.
* **Redis failures become cache misses** (`CacheErrorHandler`), with 500 ms timeouts, so a Redis outage
  makes the API slower, not broken. Redis is excluded from the health status for the same reason.

*"Why PostGIS for nearest-station, and what index does the query use?"*

## 4. Forecasting

* **Baselines first**: persistence, same hour yesterday, same-hour 7-day mean. They are columns of the
  same feature rows, so every method is scored on exactly the same rows.
* **Direct multi-horizon model**: one LightGBM with `horizon` as a feature, rather than 24 models
  (less data per model) or a recursive model (feeds its own errors back in).
* **Quantile regression** (q10/q50/q90). q50 is the point forecast (it minimises absolute error, matching MAE).
  Separate quantile models can cross, so predictions are sorted per row. Coverage is measured (X5), not assumed.
* **No leakage by construction**: every feature is `shift(k >= 0)` from the origin; seasonal features read the
  target's hour on previous days, which is always at or before the origin because horizon <= 24.
  `test_features.py` corrupts the future and checks features do not change.
* **Rolling-origin backtest**: each fold trains only on rows whose *target* time is at or before the first
  test origin. Never shuffled.
* **Promotion gate**: a new model is promoted only if it beats the current one on the most recent held-out window.
* **Honesty**: per station, if the best baseline beats the model on the holdout, the API serves that baseline
  there and `/model-info` lists it.
* **Gaps**: only gaps of 3 hours or less are forward-filled; longer gaps stay missing and are flagged.

*"Why a time-based split, and how did you prevent data leakage?"* / *"Where does your forecast fail?"*

## 5. Java forecast client and resilience

* Timeouts (1 s connect, 3 s read), retry (2 attempts, backoff) and a circuit breaker (Resilience4j).
* `ForecastService` converts every failure into `status: UNAVAILABLE`. The current-AQI endpoint does not touch
  the forecast path at all, so it keeps working when the forecast service is down.
* Forecast batches are stored; a batch younger than 90 minutes is reused, so many users at one station cost one call.

*"What happens when the forecast service is down? Show it."* (see `degraded-mode-demo.md`)

## 6. Alert engine and Telegram

* **Pure decision function** (`AlertDecisionEngine`): context in, decision out, no I/O. It is unit tested
  and the same policy is replayed over history for X6.
* **Order of rules**: stale data, then threshold, then cooldown/escalation, then quiet hours.
* **Dedupe key** = user + station + category + window start. The window is the 6-hour block containing the
  first predicted crossing, so a forecast that moves by an hour is still the same episode. A unique constraint
  makes repeated evaluations harmless; a worse category gives a new key (escalation).
* **Outbox + dispatcher**: evaluation writes PENDING rows; a separate dispatcher sends. A Telegram outage
  never blocks evaluation, and alerts stay visible as PENDING in the user's history.
* **Idempotency across crashes ("record intent, claim, send, mark")**: a single `UPDATE ... RETURNING` with
  `FOR UPDATE SKIP LOCKED` moves PENDING to SENDING. A crash after sending leaves SENDING; recovery marks it
  FAILED ("outcome unknown") instead of resending. That is at-most-once: a lost alert is possible in a rare
  crash window, a duplicate is not. The alert is re-evaluated minutes later anyway.
* **Failure classification**: connection refused, 5xx or 429 means not delivered, so retry with exponential
  backoff. 400/403 (bot blocked) is permanent. A read timeout is *unknown*, so it is not retried.
* **Account linking** uses a one-time code consumed by `DELETE ... RETURNING` (single use even under races);
  no phone numbers are collected.

*"How does your alert engine avoid sending duplicates after a crash?"*

## 7. Advice layer

* **Rules decide, the LLM only rewords.** `rules.yaml` maps (category x profile x trend) to action codes,
  each with a fixed English and Hindi sentence. The LLM receives only the category label, system numbers,
  the chosen sentences and the language.
* **Validation**: reject any number not supplied or present in the chosen sentences (Devanagari digits are
  normalised first), any medication/dosing/diagnosis wording, empty or overlong text. On rejection or any LLM
  error, the template sentences are used.
* **The disclaimer is appended by code**, never left to the model.
* The alert headline (station, AQI, time) is formatted in Java, so the key numbers are always the system's.

*"Why use rules for advice and the LLM only for wording?"*

## 8. Observability

Micrometer metrics: `saanscheck.ingestion.runs{status}`, `.rejected`, `.missing`, `.stuck`, `.gaps`;
`saanscheck.api.latency{endpoint,cache}` (histogram); `saanscheck.forecast.calls/failures/latency`;
`saanscheck.alerts.created/sent/failed/suppressed/retried`; gauge `saanscheck.stations.stale` (refreshed by a
scheduled query so a slow database never stalls a Prometheus scrape). The Grafana dashboard is provisioned from
`monitoring/grafana/dashboards/saanscheck.json`.

## Items to VERIFY before reporting results

| Item | Where |
|---|---|
| data.gov.in resource id, field names, timestamp format, page size, rate limits, filter syntax | `application.yml` (`saanscheck.ingestion`) and the test fixture |
| Whether feed values are sub-indices or concentrations | `aqi.yml` `values-are-sub-indices` |
| AQI bands and breakpoints | `aqi.yml`, `forecast-service/config/aqi_bands.yaml` |
| Default thresholds per health profile | `application.yml` `saanscheck.alerts.default-min-category` |
| Advice rules and sentences | `forecast-service/advice/rules.yaml` |
| Groq SDK interface and model id | `forecast-service/advice/phrasing.py`, `GROQ_MODEL` |
| Library versions | `backend/pom.xml`, `forecast-service/requirements.txt` |
| Dataset licences | README "Data sources" |
