-- Additive: record successful sign-ins so the admin's User Management can show "last login" and activity.
-- Existing accounts start with NULL ("no sign-in recorded since tracking began"); nothing else changes.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;
