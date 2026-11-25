-- Backfilled history in the training CSV format (station_id, ts, aqi). Train on it separately from
-- live data, or label it, because its AQI is computed from concentrations (see method column).
--   docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_historical_aqi.sql > history_backfill.csv
COPY (
    SELECT station_id, ts, aqi FROM historical_station_aqi ORDER BY station_id, ts
) TO STDOUT WITH CSV HEADER;
