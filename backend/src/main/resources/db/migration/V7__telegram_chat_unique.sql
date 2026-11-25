-- Additive: one Telegram chat can be linked to at most one VayuDrishti account.
-- Enforced in the linking service as well; this index also covers concurrent link attempts.
-- (Checked before adding: no linked chats and no duplicates existed.)
CREATE UNIQUE INDEX IF NOT EXISTS ux_profiles_telegram_chat ON profiles (telegram_chat_id) WHERE telegram_chat_id IS NOT NULL;
