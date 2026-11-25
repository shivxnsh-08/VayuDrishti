package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The alerts table is an outbox: an alert is recorded (PENDING) before any send is attempted.
 * Every state change is a single SQL statement, so each is atomic without long transactions.
 */
@Repository
public class AlertRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByDedupeKey(String key) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM alerts WHERE dedupe_key = :k)",
                new MapSqlParameterSource("k", key), Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    /** INSERT ... ON CONFLICT DO NOTHING: the unique dedupe key makes repeated evaluations harmless. */
    public Optional<Long> insertIfAbsent(NewAlert a) {
        return jdbc.queryForList("""
                INSERT INTO alerts (user_id, station_id, dedupe_key, kind, category, peak_aqi, window_start,
                                    message, status, attempts, created_at)
                VALUES (:uid, :sid, :key, :kind, :cat, :peak, :win, :msg, :status, 0, :created)
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("uid", a.userId()).addValue("sid", a.stationId()).addValue("key", a.dedupeKey())
                .addValue("kind", a.kind().name()).addValue("cat", a.category() == null ? null : a.category().name())
                .addValue("peak", a.peakAqi())
                .addValue("win", a.windowStart() == null ? null : Timestamp.from(a.windowStart()))
                .addValue("msg", a.message()).addValue("status", a.status().name())
                .addValue("created", Timestamp.from(a.createdAt())), Long.class).stream().findFirst();
    }

    /**
     * Last alert that counts for cooldown: queued, in flight or sent (not suppressed/failed, not notices).
     * Morning briefings are informational and never affect alert cooldown or escalation.
     */
    public Optional<AlertContext.LastAlert> lastActive(long userId, String stationId) {
        return jdbc.query("""
                SELECT category, created_at FROM alerts
                WHERE user_id = :uid AND station_id = :sid
                  AND kind NOT IN ('STALE_NOTICE', 'MORNING_BRIEFING', 'AQI_RECOVERY', 'AQI_STABILITY_REMINDER', 'TEST_MESSAGE')
                  AND status IN ('PENDING', 'SENDING', 'SENT')
                ORDER BY created_at DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("sid", stationId),
                (rs, i) -> new AlertContext.LastAlert(AqiCategory.valueOf(rs.getString("category")),
                        rs.getTimestamp("created_at").toInstant())).stream().findFirst();
    }

    /**
     * Atomically claims due PENDING alerts: marks them SENDING and increments attempts in one
     * statement. SKIP LOCKED lets several dispatcher instances run without double-claiming.
     */
    public List<ClaimedAlert> claimDue(Instant now, int limit) {
        return jdbc.query("""
                UPDATE alerts SET status = 'SENDING', attempts = attempts + 1, claimed_at = :now
                WHERE id IN (
                    SELECT id FROM alerts
                    WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                    ORDER BY id LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, user_id, message, attempts
                """, new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("limit", limit),
                (rs, i) -> new ClaimedAlert(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getString("message"), rs.getInt("attempts")));
    }

    public void markSent(long id, Instant at) {
        jdbc.update("UPDATE alerts SET status = 'SENT', sent_at = :at, last_error = NULL WHERE id = :id",
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", id));
    }

    public void markRetry(long id, Instant nextAttempt, String error) {
        jdbc.update("""
                UPDATE alerts SET status = 'PENDING', next_attempt_at = :next, last_error = :err WHERE id = :id
                """, new MapSqlParameterSource().addValue("next", Timestamp.from(nextAttempt))
                .addValue("err", error).addValue("id", id));
    }

    public void markFailed(long id, String error) {
        jdbc.update("UPDATE alerts SET status = 'FAILED', last_error = :err WHERE id = :id",
                new MapSqlParameterSource().addValue("err", error).addValue("id", id));
    }

    /**
     * Crash recovery. A row still SENDING after the timeout means the process died between
     * "send" and "mark sent": we cannot know whether Telegram delivered it, so we do NOT resend
     * (at-most-once for this ambiguous case) and mark it FAILED with an explanation.
     */
    public int failStuckSending(Instant claimedBefore) {
        return jdbc.update("""
                UPDATE alerts SET status = 'FAILED',
                       last_error = 'delivery outcome unknown (process stopped mid-send); not retried to avoid a duplicate'
                WHERE status = 'SENDING' AND claimed_at < :before
                """, new MapSqlParameterSource("before", Timestamp.from(claimedBefore)));
    }

    /**
     * Queues a morning briefing. The dedupe key ("brief:{user}:{local date}") is UNIQUE, so however often this
     * runs (scheduler ticks, restarts, several instances, settings changes) at most one exists per user per day.
     * Returns true if this call created it.
     */
    public boolean insertBriefing(long userId, String stationId, String dedupeKey, Integer aqi, String category,
                                  String message, Instant scheduledFor, String contextJson, Instant now) {
        return jdbc.update("""
                INSERT INTO alerts (user_id, station_id, dedupe_key, kind, category, peak_aqi, message, status, attempts,
                                    created_at, scheduled_for, context)
                VALUES (:uid, :sid, :key, 'MORNING_BRIEFING', :cat, :aqi, :msg, 'PENDING', 0, :created, :sched,
                        CAST(:ctx AS jsonb))
                ON CONFLICT (dedupe_key) DO NOTHING
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("sid", stationId)
                .addValue("key", dedupeKey).addValue("cat", category).addValue("aqi", aqi).addValue("msg", message)
                .addValue("created", Timestamp.from(now)).addValue("sched", Timestamp.from(scheduledFor))
                .addValue("ctx", contextJson)) == 1;
    }

    /**
     * Records an AQI event / reminder (PENDING for the dispatcher, or SUPPRESSED). The dedupe key is UNIQUE: one row
     * per user and observation (events) or per reminder interval (reminders). Returns true if this call created it.
     */
    public boolean insertEvent(long userId, String stationId, String dedupeKey, AlertKind kind, AqiCategory category,
                               int aqi, Integer previousAqi, Integer change, Instant observedAt, String message,
                               AlertStatus status, String note, Instant now) {
        return jdbc.update("""
                INSERT INTO alerts (user_id, station_id, dedupe_key, kind, category, peak_aqi, previous_aqi, aqi_change,
                                    observed_at, message, status, attempts, created_at, last_error)
                VALUES (:uid, :sid, :key, :kind, :cat, :aqi, :prev, :chg, :obs, :msg, :status, 0, :now, :note)
                ON CONFLICT (dedupe_key) DO NOTHING
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("sid", stationId)
                .addValue("key", dedupeKey).addValue("kind", kind.name()).addValue("cat", category.name())
                .addValue("aqi", aqi).addValue("prev", previousAqi).addValue("chg", change)
                .addValue("obs", Timestamp.from(observedAt)).addValue("msg", message).addValue("status", status.name())
                .addValue("now", Timestamp.from(now)).addValue("note", note)) == 1;
    }

    /** History record of a message sent directly (test message): exact text, outcome and error. */
    public void recordDirect(long userId, String stationId, AlertKind kind, String message, boolean sent, String error,
                             Instant now) {
        jdbc.update("""
                INSERT INTO alerts (user_id, station_id, dedupe_key, kind, message, status, attempts, created_at,
                                    sent_at, last_error)
                VALUES (:uid, :sid, :key, :kind, :msg, :status, 1, :now, :sentAt, :err)
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("sid", stationId)
                .addValue("key", "direct:" + kind.name() + ":u" + userId + ":" + java.util.UUID.randomUUID())
                .addValue("kind", kind.name()).addValue("msg", message).addValue("status", sent ? "SENT" : "FAILED")
                .addValue("now", Timestamp.from(now)).addValue("sentAt", sent ? Timestamp.from(now) : null)
                .addValue("err", error));
    }

    /** A morning briefing that could not be delivered in time is not sent late in the day: mark it FAILED. */
    public int expireBriefings(Instant scheduledBefore) {
        return jdbc.update("""
                UPDATE alerts SET status = 'FAILED',
                       last_error = COALESCE(last_error || '; ', '') || 'expired: not delivered within the briefing window'
                WHERE kind = 'MORNING_BRIEFING' AND status = 'PENDING' AND scheduled_for < :before
                """, new MapSqlParameterSource("before", Timestamp.from(scheduledBefore)));
    }

    public List<AlertView> findByUser(long userId, int limit) {
        return jdbc.query("""
                SELECT id, station_id, kind, category, peak_aqi, window_start, message, status, attempts,
                       created_at, sent_at, last_error, scheduled_for, previous_aqi, aqi_change, observed_at
                FROM alerts WHERE user_id = :uid ORDER BY created_at DESC, id DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("limit", limit),
                AlertRepository::mapView);
    }

    public long countByDedupeKeyPrefix(String prefix) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE dedupe_key LIKE :p",
                new MapSqlParameterSource("p", prefix + "%"), Long.class);
        return n == null ? 0 : n;
    }

    private static AlertView mapView(ResultSet rs, int i) throws SQLException {
        return new AlertView(rs.getLong("id"), rs.getString("station_id"), rs.getString("kind"),
                rs.getString("category"), (Integer) rs.getObject("peak_aqi"), ts(rs, "window_start"),
                rs.getString("message"), rs.getString("status"), rs.getInt("attempts"),
                ts(rs, "created_at"), ts(rs, "sent_at"), rs.getString("last_error"), ts(rs, "scheduled_for"),
                (Integer) rs.getObject("previous_aqi"), (Integer) rs.getObject("aqi_change"), ts(rs, "observed_at"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public record NewAlert(long userId, String stationId, String dedupeKey, AlertKind kind, AqiCategory category,
                           Integer peakAqi, Instant windowStart, String message, AlertStatus status,
                           Instant createdAt) {
    }

    public record ClaimedAlert(long id, long userId, String message, int attempts) {
    }

    public record AlertView(long id, String stationId, String kind, String category, Integer peakAqi,
                            Instant windowStart, String message, String status, int attempts,
                            Instant createdAt, Instant sentAt, String lastError,
                            Instant scheduledFor,                   // briefings only: when it was due
                            Integer previousAqi, Integer aqiChange, Instant observedAt) {   // AQI events
    }
}
