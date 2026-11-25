-- Raw readings with quality flags for the data-quality audit (X1):
--   docker compose exec -T postgres psql -U saanscheck -d saanscheck -f - < evaluation/sql/export_readings.sql > readings.csv
COPY (
    SELECT station_id, pollutant, ts, value, quality
    FROM readings
    ORDER BY station_id, pollutant, ts
) TO STDOUT WITH CSV HEADER;
