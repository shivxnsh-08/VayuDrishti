# Evaluation

Scripts that produce the numbers for the README results table (spec section 13).
Run them from the repository root with the forecast-service dependencies installed
(`pip install -r forecast-service/requirements-dev.txt`).

| # | What | Command |
|---|------|---------|
| X1 | Data-quality audit | `python evaluation/data_quality_audit.py --readings readings.csv` |
| X2, X3, X5 | Baselines vs LightGBM, category accuracy, confusion matrix, interval coverage | `python evaluation/run_backtest.py --data history.csv --freq hourly` |
| X4 | Feature-group ablation | `python evaluation/run_backtest.py --data history.csv --ablate calendar` (also `seasonal`, `rolling`, `station`) |
| X6 | Alert replay: precision, recall, lead time | `python evaluation/alert_replay.py --predictions evaluation/results/<run>/predictions.csv --threshold POOR` |
| X7 | API latency with/without Redis | see `load/README.md` |
| X8 | Degraded mode | see `docs/degraded-mode-demo.md` |

## Getting the data

* **Hourly history** (accumulates once ingestion runs):
  `docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_station_aqi.sql > history.csv`
* **Readings for X1**: same, with `sql/export_readings.sql`.
* **Daily cold-start data**: download the day-wise city AQI dataset from data.gov.in (VERIFY the
  resource, licence and column names) and pass its columns explicitly, for example
  `--freq daily --station-col <city column> --ts-col <date column> --aqi-col <AQI column> --dayfirst`.

## Rules for reporting

* Every result folder has a `meta.json` with data dates, station count, test period and hardware. Quote it.
* Report stations where the model loses to a baseline (`per_station.csv`); the API serves the baseline there.
* `results/` is where real outputs go. Nothing in this repository is a measured result yet.
