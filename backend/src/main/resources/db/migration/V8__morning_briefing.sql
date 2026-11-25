-- Additive: daily morning AQI briefing (Telegram).
-- Per-user preference: on by default for everyone (only users with a linked Telegram chat and a saved
-- station receive one), at 07:00 in the alert time zone (Asia/Kolkata).
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS morning_briefing_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS morning_briefing_time TIME NOT NULL DEFAULT '07:00';

-- Briefings are stored in the existing notification history (alerts, kind = MORNING_BRIEFING) with the exact
-- message sent. These two columns are only used by briefings: when it was due, and the data it was built from.
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS scheduled_for TIMESTAMPTZ;
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS context JSONB;
