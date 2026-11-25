-- Load <out>_aqi.csv from the backfill into historical_station_aqi.
-- Idempotent: re-loading the same file inserts nothing (primary key station_id, ts, source).
-- Rows for stations that do not exist in the stations table are dropped.
--   docker compose cp history_openaq_aqi.csv postgres:/tmp/hist.csv
--   docker compose exec -T postgres psql -U saanscheck -d saanscheck -v ON_ERROR_STOP=1 -f - < evaluation/sql/load_historical_aqi.sql
BEGIN;
CREATE TEMP TABLE hist_stage (
    station_id TEXT, ts TIMESTAMPTZ, aqi INT, dominant_pollutant TEXT, pollutant_count INT,
    source TEXT, source_location_id TEXT, provider TEXT, licence TEXT, method TEXT
) ON COMMIT DROP;
COPY hist_stage FROM '/tmp/hist.csv' WITH CSV HEADER;
INSERT INTO historical_station_aqi (station_id, ts, aqi, dominant_pollutant, pollutant_count, source,
                                    source_location_id, provider, licence, method)
SELECT s.station_id, s.ts, s.aqi, s.dominant_pollutant, s.pollutant_count, s.source,
       s.source_location_id, s.provider, s.licence, s.method
FROM hist_stage s JOIN stations st ON st.id = s.station_id
ON CONFLICT (station_id, ts, source) DO NOTHING;
SELECT count(*) AS rows_now FROM historical_station_aqi;
COMMIT;
