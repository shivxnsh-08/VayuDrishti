-- Hourly AQI history for training/backtesting (run with psql):
--   docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_station_aqi.sql > history.csv
COPY (
    SELECT station_id, ts, aqi
    FROM station_aqi
    WHERE aqi IS NOT NULL
    ORDER BY station_id, ts
) TO STDOUT WITH CSV HEADER;
