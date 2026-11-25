-- Historical hourly AQI backfilled from an external archive (e.g. OpenAQ). Deliberately separate
-- from station_aqi: live rows are CPCB sub-indices from the official feed, these are computed by us
-- from concentrations (method column), so they must never be silently mixed with live data.
CREATE TABLE historical_station_aqi (
    station_id         VARCHAR(64) NOT NULL REFERENCES stations (id),
    ts                 TIMESTAMPTZ NOT NULL,
    aqi                INT         NOT NULL,
    dominant_pollutant VARCHAR(16),
    pollutant_count    INT,
    source             VARCHAR(32) NOT NULL,      -- e.g. openaq
    source_location_id VARCHAR(64),
    provider           TEXT,
    licence            TEXT,
    method             VARCHAR(64) NOT NULL,      -- how the AQI was derived
    loaded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (station_id, ts, source)
);
