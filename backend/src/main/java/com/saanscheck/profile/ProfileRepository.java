package com.saanscheck.profile;

import com.saanscheck.aqi.AqiCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Types;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC rather than JPA because of the PostGIS home_geom column. */
@Repository
public class ProfileRepository {

    private static final String SELECT = """
            SELECT user_id, health_profile,
                   ST_Y(home_geom::geometry) AS lat, ST_X(home_geom::geometry) AS lon, home_city,
                   station_id, language, alert_min_category, quiet_start, quiet_end,
                   severe_overrides_quiet, telegram_chat_id
            FROM profiles
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Profile> find(long userId) {
        return jdbc.query(SELECT + " WHERE user_id = :uid", new MapSqlParameterSource("uid", userId),
                ProfileRepository::map).stream().findFirst();
    }

    /** Users who can receive alerts: a station chosen and Telegram linked. */
    public List<Profile> findAlertable() {
        // Administrator accounts never receive Telegram notifications.
        return jdbc.query(SELECT + " WHERE station_id IS NOT NULL AND telegram_chat_id IS NOT NULL AND aqi_alerts_enabled"
                        + " AND user_id IN (SELECT id FROM users WHERE role <> 'ADMIN')",
                new MapSqlParameterSource(), ProfileRepository::map);
    }

    public Optional<String> findChatId(long userId) {
        // The single place a chat id is read for delivery (alerts, briefings, test messages): never for an admin.
        return jdbc.queryForList("SELECT telegram_chat_id FROM profiles WHERE user_id = :uid AND telegram_chat_id IS NOT NULL"
                        + " AND user_id IN (SELECT id FROM users WHERE role <> 'ADMIN')",
                new MapSqlParameterSource("uid", userId), String.class).stream().findFirst();
    }

    /** Upsert everything except the Telegram chat id, which only the linking flow may change. */
    public void upsert(Profile p) {
        jdbc.update("""
                INSERT INTO profiles (user_id, health_profile, home_geom, home_city, station_id, language,
                                      alert_min_category, quiet_start, quiet_end, severe_overrides_quiet, updated_at)
                VALUES (:uid, :hp,
                        CASE WHEN CAST(:lat AS double precision) IS NULL OR CAST(:lon AS double precision) IS NULL
                             THEN NULL
                             ELSE ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                                          CAST(:lat AS double precision)), 4326)::geography END,
                        :city, :sid, :lang, :minCat, :qs, :qe, :sev, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    health_profile = EXCLUDED.health_profile, home_geom = EXCLUDED.home_geom,
                    home_city = EXCLUDED.home_city, station_id = EXCLUDED.station_id,
                    language = EXCLUDED.language, alert_min_category = EXCLUDED.alert_min_category,
                    quiet_start = EXCLUDED.quiet_start, quiet_end = EXCLUDED.quiet_end,
                    severe_overrides_quiet = EXCLUDED.severe_overrides_quiet, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("uid", p.userId()).addValue("hp", p.healthProfile().name())
                .addValue("lat", p.latitude(), Types.DOUBLE).addValue("lon", p.longitude(), Types.DOUBLE)
                .addValue("city", p.homeCity()).addValue("sid", p.stationId()).addValue("lang", p.language())
                .addValue("minCat", p.alertMinCategory() == null ? null : p.alertMinCategory().name())
                .addValue("qs", p.quietStart() == null ? null : Time.valueOf(p.quietStart()))
                .addValue("qe", p.quietEnd() == null ? null : Time.valueOf(p.quietEnd()))
                .addValue("sev", p.severeOverridesQuiet()));
    }

    /** Links a chat. If the user has no profile yet, a minimal GENERAL profile is created. */
    /** Morning briefing preference (columns added in V8; defaults: on, 07:00). */
    public record BriefingSettings(boolean enabled, LocalTime time) {
        public static final BriefingSettings DEFAULT = new BriefingSettings(true, LocalTime.of(7, 0));
    }

    public BriefingSettings briefingSettings(long userId) {
        return jdbc.query("SELECT morning_briefing_enabled, morning_briefing_time FROM profiles WHERE user_id = :uid",
                new MapSqlParameterSource("uid", userId),
                (rs, i) -> new BriefingSettings(rs.getBoolean(1), rs.getTime(2).toLocalTime()))
                .stream().findFirst().orElse(BriefingSettings.DEFAULT);
    }

    /** Updates only the given fields (null = unchanged). The profile row must exist. */
    /** AQI event alerts switch (V9; default on). Independent of the morning briefing. */
    public boolean aqiAlertsEnabled(long userId) {
        return jdbc.queryForList("SELECT aqi_alerts_enabled FROM profiles WHERE user_id = :uid",
                new MapSqlParameterSource("uid", userId), Boolean.class).stream().findFirst().orElse(true);
    }

    public void setAqiAlertsEnabled(long userId, boolean enabled) {
        jdbc.update("UPDATE profiles SET aqi_alerts_enabled = :en WHERE user_id = :uid",
                new MapSqlParameterSource().addValue("uid", userId).addValue("en", enabled));
    }

    /** Users who should get a morning briefing: enabled, Telegram linked and a station saved. */
    public List<BriefingCandidate> briefingCandidates() {
        return jdbc.query("""
                SELECT user_id, morning_briefing_time FROM profiles
                WHERE morning_briefing_enabled AND telegram_chat_id IS NOT NULL AND station_id IS NOT NULL
                  AND user_id IN (SELECT id FROM users WHERE role <> 'ADMIN')
                ORDER BY user_id
                """, new MapSqlParameterSource(),
                (rs, i) -> new BriefingCandidate(rs.getLong(1), rs.getTime(2).toLocalTime()));
    }

    public record BriefingCandidate(long userId, LocalTime time) {
    }

    public void setBriefingSettings(long userId, Boolean enabled, LocalTime time) {
        jdbc.update("""
                UPDATE profiles SET morning_briefing_enabled = COALESCE(:en, morning_briefing_enabled),
                                    morning_briefing_time = COALESCE(CAST(:t AS time), morning_briefing_time)
                WHERE user_id = :uid
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("en", enabled)
                .addValue("t", time == null ? null : Time.valueOf(time), Types.TIME));
    }

    /** The account a Telegram chat is linked to, if any (at most one: unique index V7). */
    public Optional<Long> findUserIdByChatId(String chatId) {
        return jdbc.queryForList("SELECT user_id FROM profiles WHERE telegram_chat_id = :chat",
                new MapSqlParameterSource("chat", chatId), Long.class).stream().findFirst();
    }

    /** Unlink by account. Returns true if a chat was linked. */
    public boolean clearTelegramChat(long userId) {
        return jdbc.update("""
                UPDATE profiles SET telegram_chat_id = NULL, updated_at = now()
                WHERE user_id = :uid AND telegram_chat_id IS NOT NULL
                """, new MapSqlParameterSource("uid", userId)) > 0;
    }

    /** Unlink by chat (the bot's /unlink). Returns true if the chat was linked. */
    public boolean clearTelegramChatByChatId(String chatId) {
        return jdbc.update("UPDATE profiles SET telegram_chat_id = NULL, updated_at = now() WHERE telegram_chat_id = :chat",
                new MapSqlParameterSource("chat", chatId)) > 0;
    }

    public void setTelegramChatId(long userId, String chatId) {
        int n = jdbc.update("UPDATE profiles SET telegram_chat_id = :chat, updated_at = now() WHERE user_id = :uid",
                new MapSqlParameterSource().addValue("chat", chatId).addValue("uid", userId));
        if (n == 0) {
            jdbc.update("""
                    INSERT INTO profiles (user_id, health_profile, telegram_chat_id) VALUES (:uid, 'GENERAL', :chat)
                    """, new MapSqlParameterSource().addValue("chat", chatId).addValue("uid", userId));
        }
    }

    private static Profile map(ResultSet rs, int i) throws SQLException {
        String minCat = rs.getString("alert_min_category");
        return new Profile(rs.getLong("user_id"), HealthProfile.valueOf(rs.getString("health_profile")),
                (Double) rs.getObject("lat"), (Double) rs.getObject("lon"), rs.getString("home_city"),
                rs.getString("station_id"), rs.getString("language"),
                minCat == null ? null : AqiCategory.valueOf(minCat),
                toLocal(rs.getTime("quiet_start")), toLocal(rs.getTime("quiet_end")),
                rs.getBoolean("severe_overrides_quiet"), rs.getString("telegram_chat_id"));
    }

    private static LocalTime toLocal(Time t) {
        return t == null ? null : t.toLocalTime();
    }
}
