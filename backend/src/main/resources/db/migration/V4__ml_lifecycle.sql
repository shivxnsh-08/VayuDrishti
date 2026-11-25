-- Automated ML lifecycle bookkeeping. Schema owned by the backend (Flyway); rows are written by the
-- forecast service's lifecycle manager and read by the backend's admin API. Additive only.

-- One row per training attempt (never for a mere readiness check that failed).
CREATE TABLE ml_training_runs (
    id                  BIGSERIAL PRIMARY KEY,
    trigger             VARCHAR(32)  NOT NULL,   -- INITIAL | RETRAIN_NEW_DATA | RETRAIN_MAX_INTERVAL | RETRY | MANUAL
    status              VARCHAR(32)  NOT NULL,   -- RUNNING | PROMOTED | NOT_PROMOTED | INSUFFICIENT_DATA | FAILED | INTERRUPTED
    attempt             INT          NOT NULL DEFAULT 1,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    dataset_fingerprint VARCHAR(64),
    data_first_ts       TIMESTAMPTZ,
    data_last_ts        TIMESTAMPTZ,
    observations        INT,
    stations            INT,
    model_version       VARCHAR(64),
    model_name          VARCHAR(32),
    metrics             JSONB,                   -- test-window metrics as computed by train.py (null if not evaluated)
    reasons             TEXT,                    -- promotion-gate reasons or readiness reason
    error               TEXT
);
CREATE INDEX ix_ml_training_runs_started ON ml_training_runs (started_at DESC);

-- Readiness changes, promotions, rollbacks, deactivations, monitoring results, load errors.
CREATE TABLE ml_lifecycle_events (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type VARCHAR(32) NOT NULL,
    message    TEXT        NOT NULL,
    detail     JSONB
);
CREATE INDEX ix_ml_lifecycle_events_at ON ml_lifecycle_events (at DESC);

-- Small key/value state that must survive restarts (next check times, degradation streak, last readiness).
CREATE TABLE ml_lifecycle_state (
    key        VARCHAR(64) PRIMARY KEY,
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
