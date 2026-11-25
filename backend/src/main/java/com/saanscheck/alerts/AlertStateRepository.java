package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Per-user alert memory ({@code alert_state}, V9). */
@Repository
public class AlertStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads and row-locks the state for the current transaction (concurrent evaluators serialise per user). */
    public Optional<AqiEventRules.State> findForUpdate(long userId) {
        return jdbc.query("SELECT * FROM alert_state WHERE user_id = :uid FOR UPDATE",
                new MapSqlParameterSource("uid", userId), AlertStateRepository::map).stream().findFirst();
    }

    public void save(long userId, AqiEventRules.State s, Instant now) {
        jdbc.update("""
                INSERT INTO alert_state (user_id, station_id, threshold, last_obs_ts, last_obs_aqi, ref_aqi, ref_category,
                                         ref_ts, above_threshold, last_event_at, last_reminder_at, tracking_since, updated_at)
                VALUES (:uid, :sid, :thr, :lastTs, :lastAqi, :refAqi, :refCat, :refTs, :above, :evt, :rem, :since, :now)
                ON CONFLICT (user_id) DO UPDATE SET
                    station_id = EXCLUDED.station_id, threshold = EXCLUDED.threshold, last_obs_ts = EXCLUDED.last_obs_ts,
                    last_obs_aqi = EXCLUDED.last_obs_aqi, ref_aqi = EXCLUDED.ref_aqi, ref_category = EXCLUDED.ref_category,
                    ref_ts = EXCLUDED.ref_ts, above_threshold = EXCLUDED.above_threshold,
                    last_event_at = EXCLUDED.last_event_at, last_reminder_at = EXCLUDED.last_reminder_at,
                    tracking_since = EXCLUDED.tracking_since, updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("sid", s.stationId())
                .addValue("thr", s.threshold().name()).addValue("lastTs", ts(s.lastObsTs()))
                .addValue("lastAqi", s.lastObsAqi()).addValue("refAqi", s.refAqi())
                .addValue("refCat", s.refCategory().name()).addValue("refTs", ts(s.refTs()))
                .addValue("above", s.above()).addValue("evt", ts(s.lastEventAt())).addValue("rem", ts(s.lastReminderAt()))
                .addValue("since", ts(s.trackingSince())).addValue("now", ts(now)));
    }

    /** Forget the memory (alerts switched off): switching them on again starts with a fresh baseline. */
    public void delete(long userId) {
        jdbc.update("DELETE FROM alert_state WHERE user_id = :uid", new MapSqlParameterSource("uid", userId));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static AqiEventRules.State map(ResultSet rs, int i) throws SQLException {
        return new AqiEventRules.State(rs.getString("station_id"), AqiCategory.valueOf(rs.getString("threshold")),
                inst(rs, "last_obs_ts"), (Integer) rs.getObject("last_obs_aqi"), rs.getInt("ref_aqi"),
                AqiCategory.valueOf(rs.getString("ref_category")), inst(rs, "ref_ts"), rs.getBoolean("above_threshold"),
                inst(rs, "last_event_at"), inst(rs, "last_reminder_at"), inst(rs, "tracking_since"));
    }
}
