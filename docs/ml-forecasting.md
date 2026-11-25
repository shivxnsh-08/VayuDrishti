# ML forecasting: approach, status and runbook

Status on 2026-11-22: **pipeline and automated lifecycle implemented and running; no model trained on real data yet** because only
6 hourly observations exist per station. The API serves the persistence baseline and says so.

## Data readiness (audited 2026-11-22 16:26 UTC)

| Item | Value |
|---|---|
| Stations | 561 active |
| Hourly history | 6 feed hours (2026-11-22 10:30 to 15:30 UTC); ingestion started that day |
| Valid hours per station | 6 (484 stations), 5 (40), 1-4 (7); none has 24 |
| Pollutants | PM2.5, PM10, NO2, SO2, O3, CO, NH3 at all stations (NH3/SO2 often stuck or missing) |
| Weather features | none |
| `train.py` on the real export | `INSUFFICIENT_DATA`: "history spans 6 h; need 672 h ... about 27.8 more days of continuous data" |

**Why 672 hours:** the features look back up to 167 h (lag) and 7 days (same-hour mean), so 168 h of warm-up;
then at least 168 h to train, 168 h to select the model and calibrate intervals, and 168 h of untouched test.
The 24-observation rule only decides whether the API may *serve* a forecast; it says nothing about training.

**When:** with uninterrupted ingestion, about 2026-12-20. Sooner if the OpenAQ backfill supplies real history
(`forecast-service/backfill/`, needs `OPENAQ_API_KEY`; trial it on a few stations first).

## Approach

* **Direct multi-horizon:** one model, `horizon` (1..24 h) is a feature. Uses every row for all horizons and
  avoids feeding errors back in (recursive). Limitation: horizons are capped at 24 h, because the
  same-hour-yesterday/7-day features need `horizon <= 24` to stay leak-free.
* **Features** (all known at the origin time): AQI lags 0-167 h, rolling mean/max (3, 6, 24, 168 h), hour, weekday,
  month, Indian season (target time, IST), same hour yesterday, same-hour 7-day mean, station. A test corrupts
  future values and checks that features do not change.
* **Candidates:** Random Forest (scikit-learn; native missing-value handling) and LightGBM. XGBoost was not added:
  LightGBM already covers gradient boosting, so a second boosting library is not justified.
* **Baselines:** persistence, same hour yesterday, same-hour 7-day mean, scored on exactly the same rows.
* **Intervals:** Random Forest uses split-conformal intervals (per-horizon 10th/90th percentile of validation
  residuals); LightGBM uses quantile regression. In both cases the coverage is **measured on the test window**
  and stored with the model. Nothing is reported as a "confidence percentage".

## Evaluation and promotion (`forecast-service/train.py`)

```
[ warm-up | train | validation | test ]   chronological, by origin time; never shuffled
```
* train targets end before validation starts; validation targets end before test starts
* candidates are selected on **validation** MAE; the winner is evaluated **once** on test
* reported: MAE, RMSE, AQI-category accuracy overall and at horizons 1, 3, 6, 12, 24 h; per-station table;
  interval coverage
* **promotion gate:** test MAE must beat persistence, the best baseline, **and** the currently active model.
  Otherwise the active model (or the persistence baseline) stays.
* stations where a baseline beat the model on test are served that baseline (`use_baseline`)
* on promotion the model is refit on all data with the same settings (intervals keep their validation calibration)

Exit codes: `0` promoted, `2` not enough data, `3` evaluated but not promoted. Each run writes
`models/artifacts/last_training_report.json`.

## Model management (`python -m models.registry`)

| Command | Effect |
|---|---|
| `list` | versions, model type, gate result, test MAE; `*` marks the active one |
| `rollback` | re-activate the version that was active before the current one |
| `activate <version>` | activate any version that passed the gate (others are refused) |
| `deactivate` | serve the persistence baseline |

Artifacts: `models/artifacts/<version>/{model.joblib, metadata.json, eval_*.csv}`, `current.json` (active),
`history.json` (every promotion/rollback). The service re-reads `current.json` on each request, so these
take effect without a restart; a model that fails to load leaves the previous state in service and is
reported in `/model-info` (`load_error`).

## Automated lifecycle (no manual steps for routine operation)

Runs inside the forecast service as a background thread (`LIFECYCLE_ENABLED=true`, database via `PG*` variables).
Code: `forecast-service/lifecycle/` (`manager.py` loop, `policy.py` rules, `monitor.py`, `job.py`, `repository.py`).
Records: `ml_training_runs`, `ml_lifecycle_events`, `ml_lifecycle_state` (Flyway V4; owned by the backend).

| Step | When | What |
|---|---|---|
| Recovery | every tick (5 min) | a `RUNNING` run whose Postgres advisory lock is free was orphaned by a restart -> `INTERRUPTED` |
| Safety | every tick | active model fails to load, or >= 50% of >= 20 model predictions failed in 60 min -> previous model, else persistence |
| Readiness + training | every 6 h | load real `station_aqi` history (60 days), apply the 672 h gate (repeated snapshots do not add hours), then `policy.decide_training`; start `python -m lifecycle.job` in a separate process |
| Monitoring | every 1 h | score the active model on data newer than its training data (rolling 168 h) vs persistence on the same rows |

**Training decision:** not ready -> nothing recorded as a run (one readiness event when the state changes). Same
dataset fingerprint as the last completed run -> skip. Failed/interrupted -> retry after 1 h, 2 h, 4 h ... (max 24 h);
after 3 failures on the same data, wait for new data. Never trained -> `INITIAL`. Afterwards retrain when >= 168 h
have passed **and** >= 72 new hours exist (`RETRAIN_NEW_DATA`), or after 720 h with any new data (`RETRAIN_MAX_INTERVAL`).

**Promotion:** only inside `train.py`, through the unchanged gate (beat persistence, the best baseline and the
active model on the untouched test window). The persistence baseline serves until then and while training runs;
the new model is picked up on the next request without a restart. Per-station baseline fallback is preserved.

**Automatic rollback (all conditions):** >= 2,000 scored rows, >= 10 stations, >= 48 h of post-training data, and
model MAE > persistence MAE x 1.05 on **2 consecutive** hourly checks. Then the previous model is re-activated only
if it beats persistence on the same window; otherwise the model is deactivated and persistence serves. Less
evidence never triggers anything.

**Concurrency and restarts:** one training job at a time (advisory lock held by the job's own connection; released by
Postgres if the process dies). Training runs in a child process, so predictions are never blocked. Schedule times
and the degradation streak are stored in the database and survive restarts. Registry writes are atomic.

**Monitoring it:** `GET /api/v1/model-info` (public: method, readiness, next check, last successful training);
`GET /api/v1/admin/ml/lifecycle` (ADMIN: state, training runs with metrics and gate reasons, events incl.
promotions and rollbacks); the forecast service's `GET /lifecycle` (internal). Manual overrides remain available:
`python -m models.registry list|rollback|activate|deactivate`.

**Tuning:** every threshold above is a `LIFECYCLE_*` environment variable (see `lifecycle/config.py`).

## Integration (unchanged contract)

React -> Spring Boot -> FastAPI. `GET /api/v1/stations/{id}/forecast` returns `method` (`MODEL` or
`BASELINE_*`), `modelVersion` (e.g. `random_forest-20261220T020000`, so the model type is visible),
24 hourly points and the interval bounds when the model provides them. Unknown station: 404. Too little or
stale data: `INSUFFICIENT_DATA` / `STALE_DATA` without calling Python. Python 422 (invalid input) is not
retried; Python 503 ("model prediction failed") and outages become `UNAVAILABLE`. A fake forecast is never
substituted. `GET /api/v1/model-info` adds model name, splits, per-horizon test metrics and interval method.

## Runbook (once enough data exists)

```bash
docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_station_aqi.sql > history.csv
docker compose cp history.csv forecast-service:/tmp/history.csv
docker compose exec forecast-service python train.py --data /tmp/history.csv     # writes to the mounted artifacts dir
docker compose exec forecast-service python -m models.registry list
```

## Not done yet (deliberately)

* **PM2.5/PM10 lag features**: data exists in `readings`, but using them at inference needs a Java->Python contract
  change, and there is no data yet to show they help. Add as an ablation (X4) once the first model is trained.
* **48-72 h horizons**: needs a different seasonal-feature design and enough history to evaluate; 7-day
  forecasting is not implemented and is not claimed.
* **Weather features**: none available in the system.
* **Frontend**: `/model-info` keys for a Random Forest are `random_forest_mae` etc.; the current dashboard only
  labels `lgbm_*` metrics, so it will show baselines but not the RF score until the frontend is updated.
