-- Additive: index station_aqi by time.
-- "Newest observation" (max(ts)) is evaluated every minute by the ingestion health monitor and on every
-- /ingestion/health request. The primary key starts with station_id, so without this index that query is a
-- full table scan that grows by ~13k rows/day. With it, it is a one-row backward index scan.
CREATE INDEX IF NOT EXISTS ix_station_aqi_ts ON station_aqi (ts);
