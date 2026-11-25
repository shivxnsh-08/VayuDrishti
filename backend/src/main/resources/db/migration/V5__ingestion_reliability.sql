-- Ingestion reliability and observability. Additive only.

ALTER TABLE ingestion_runs ADD COLUMN trigger           VARCHAR(16);   -- SCHEDULED | RECOVERY | MANUAL
ALTER TABLE ingestion_runs ADD COLUMN duration_ms       BIGINT;
ALTER TABLE ingestion_runs ADD COLUMN retry_count       INT;           -- HTTP retries performed during the run
ALTER TABLE ingestion_runs ADD COLUMN error_category    VARCHAR(32);   -- NETWORK | TIMEOUT | DNS | TLS | RATE_LIMITED | SOURCE_5XX | AUTH | CLIENT_4XX | PARSE | DATABASE | CONFIG | UNKNOWN
ALTER TABLE ingestion_runs ADD COLUMN http_status       INT;           -- last HTTP status from the source (null if no response)
ALTER TABLE ingestion_runs ADD COLUMN latest_source_ts  TIMESTAMPTZ;   -- newest observation timestamp in the source response
ALTER TABLE ingestion_runs ADD COLUMN rejection_reasons JSONB;         -- {"UNKNOWN_STATION_NO_COORDINATES": 2, ...}

-- Pre-existing rows were all started by the scheduler or the admin trigger; leave trigger NULL (unknown).

-- Ingestion-health alerts sent (or attempted), for deduplication across restarts.
CREATE TABLE ingestion_health_notifications (
    id              BIGSERIAL PRIMARY KEY,
    status          VARCHAR(32) NOT NULL,       -- health status that was announced
    previous_status VARCHAR(32),
    kind            VARCHAR(16) NOT NULL,       -- PROBLEM | RECOVERY
    message         TEXT        NOT NULL,
    recipients      INT         NOT NULL DEFAULT 0,
    notified_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ingestion_health_notifications_at ON ingestion_health_notifications (notified_at DESC);
