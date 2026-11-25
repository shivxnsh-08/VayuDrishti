-- Additive: change-based AQI alerts (threshold crossing, deterioration, recovery, 8-hour reminders).

-- New notification kinds are longer than 16 characters (e.g. AQI_STABILITY_REMINDER): widen, never shrink.
ALTER TABLE alerts ALTER COLUMN kind TYPE VARCHAR(32);

-- History detail for AQI events: the reading before, the change, and when the reading was observed.
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS previous_aqi INT;
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS aqi_change INT;
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ;

-- Independent switch for AQI event alerts (the morning briefing has its own switch, V8).
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS aqi_alerts_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Per-user alert memory: which observation was processed last, what the user was last told (reference),
-- whether the AQI was at/above their threshold, and separate timestamps for events and stability reminders.
CREATE TABLE IF NOT EXISTS alert_state (
    user_id          BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    station_id       VARCHAR(64) NOT NULL,
    threshold        VARCHAR(16) NOT NULL,      -- effective alert category when the state was last updated
    last_obs_ts      TIMESTAMPTZ,               -- newest observation already processed (each one exactly once)
    last_obs_aqi     INT,                       -- that observation's AQI ("previous reading")
    ref_aqi          INT,                       -- AQI the user was last told about (or the silent baseline)
    ref_category     VARCHAR(16),
    ref_ts           TIMESTAMPTZ,
    above_threshold  BOOLEAN NOT NULL,
    last_event_at    TIMESTAMPTZ,               -- last threshold/deterioration/recovery notification
    last_reminder_at TIMESTAMPTZ,               -- last 8-hour stability reminder
    tracking_since   TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- Users who already received a current-AQI alert keep that as their reference, so the upgrade does not
-- re-announce an AQI they were already told about (their reminder clock starts at that alert).
INSERT INTO alert_state (user_id, station_id, threshold, last_obs_ts, last_obs_aqi, ref_aqi, ref_category, ref_ts,
                         above_threshold, last_event_at, last_reminder_at, tracking_since, updated_at)
SELECT DISTINCT ON (a.user_id) a.user_id, a.station_id, a.category, NULL, a.peak_aqi, a.peak_aqi, a.category,
       a.created_at, TRUE, a.created_at, NULL, a.created_at, now()
FROM alerts a
JOIN profiles p ON p.user_id = a.user_id AND p.station_id = a.station_id
WHERE a.kind = 'CURRENT' AND a.status = 'SENT' AND a.category IS NOT NULL AND a.peak_aqi IS NOT NULL
ORDER BY a.user_id, a.created_at DESC
ON CONFLICT (user_id) DO NOTHING;
