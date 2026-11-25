-- Station list for the historical backfill (python -m backfill.openaq --stations stations.csv ...):
--   docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_stations.sql > stations.csv
COPY (
    SELECT id, name, ST_Y(geom::geometry) AS latitude, ST_X(geom::geometry) AS longitude
    FROM stations WHERE active ORDER BY id
) TO STDOUT WITH CSV HEADER;
