-- SaansCheck initial schema (spec section 5) plus a few columns the alert engine,
-- Telegram linking and the data-quality report need. Additions are marked "added".

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE stations (
    id            VARCHAR(64) PRIMARY KEY,
    name          TEXT        NOT NULL,
    city          TEXT,
    state         TEXT,
    geom          GEOGRAPHY(Point, 4326) NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    last_seen_at  TIMESTAMPTZ                          -- added: last time the feed mentioned the station
);
-- GiST index: used by the KNN operator (<->) in the nearest-station query.
CREATE INDEX ix_stations_geom ON stations USING GIST (geom);

-- One row per station, pollutant and source timestamp. The PK doubles as the dedupe key:
-- re-fetching the same snapshot inserts nothing (ON CONFLICT DO NOTHING).
CREATE TABLE readings (
    station_id VARCHAR(64)  NOT NULL REFERENCES stations (id),
    pollutant  VARCHAR(16)  NOT NULL,
    ts         TIMESTAMPTZ  NOT NULL,
    value      DOUBLE PRECISION,                    -- cleaned value; NULL when MISSING/INVALID
    quality    VARCHAR(16)  NOT NULL DEFAULT 'OK',  -- OK | MISSING | INVALID | STUCK
    raw        JSONB,                               -- untouched source record
    PRIMARY KEY (station_id, pollutant, ts)
);
CREATE INDEX ix_readings_ts ON readings (ts);

-- Derived overall AQI per station and hour.
CREATE TABLE station_aqi (
    station_id         VARCHAR(64) NOT NULL REFERENCES stations (id),
    ts                 TIMESTAMPTZ NOT NULL,
    aqi                INT,
    category           VARCHAR(16),
    dominant_pollutant VARCHAR(16),
    pollutant_count    INT,                         -- added: how many valid pollutants fed the AQI
    stale              BOOLEAN DEFAULT FALSE,
    PRIMARY KEY (station_id, ts)
);

CREATE TABLE forecasts (
    station_id    VARCHAR(64) NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL,
    target_ts     TIMESTAMPTZ NOT NULL,
    predicted_aqi INT,
    lower         INT,
    upper         INT,
    model_version VARCHAR(32),
    method        VARCHAR(32),                      -- added: MODEL or the baseline that was served
    PRIMARY KEY (station_id, generated_at, target_ts)
);
CREATE INDEX ix_forecasts_latest ON forecasts (station_id, generated_at DESC);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  DEFAULT now()
);

CREATE TABLE profiles (
    user_id                BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    health_profile         VARCHAR(16) NOT NULL,    -- GENERAL|ASTHMA|CHILD|ELDERLY|EXERCISER
    home_geom              GEOGRAPHY(Point, 4326),  -- coarse (rounded) location only
    home_city              TEXT,                    -- added: alternative to coordinates
    station_id             VARCHAR(64) REFERENCES stations (id),
    language               VARCHAR(8) DEFAULT 'en',
    alert_min_category     VARCHAR(16),             -- NULL = default for the health profile
    quiet_start            TIME,
    quiet_end              TIME,
    severe_overrides_quiet BOOLEAN NOT NULL DEFAULT FALSE,  -- added: opt-in for severe alerts in quiet hours
    telegram_chat_id       VARCHAR(64),
    updated_at             TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX ix_profiles_station ON profiles (station_id);

-- added: one-time codes a user sends to the bot to link their Telegram chat.
CREATE TABLE telegram_link_codes (
    code       VARCHAR(16) PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE alerts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES users (id) ON DELETE CASCADE,
    station_id      VARCHAR(64),
    dedupe_key      VARCHAR(200) UNIQUE NOT NULL,
    kind            VARCHAR(16),                    -- added: FORECAST | CURRENT | STALE_NOTICE
    category        VARCHAR(16),
    peak_aqi        INT,                            -- added
    window_start    TIMESTAMPTZ,
    message         TEXT,
    status          VARCHAR(16) NOT NULL,           -- PENDING|SENDING|SENT|FAILED|SUPPRESSED
    attempts        INT DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,                    -- added: retry backoff
    claimed_at      TIMESTAMPTZ,                    -- added: when a dispatcher moved it to SENDING
    last_error      TEXT,                           -- added
    created_at      TIMESTAMPTZ DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_alerts_due ON alerts (status, next_attempt_at);
CREATE INDEX ix_alerts_user ON alerts (user_id, created_at DESC);

CREATE TABLE ingestion_runs (
    id          BIGSERIAL PRIMARY KEY,
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    fetched     INT,
    inserted    INT,
    rejected    INT,
    missing     INT,                                -- added
    stuck       INT,                                -- added
    duplicates  INT,                                -- added
    status      VARCHAR(16),                        -- RUNNING|SUCCESS|PARTIAL|FAILED|SKIPPED
    error       TEXT
);
