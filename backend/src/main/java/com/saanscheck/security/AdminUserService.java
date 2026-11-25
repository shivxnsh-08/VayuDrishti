package com.saanscheck.security;

import com.saanscheck.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only user management for the admin. Only safe fields are ever selected: no password hash, token, Telegram
 * chat id or coordinates. Sorting and filters use whitelisted columns (no user input is concatenated into SQL).
 *
 * <p>Status is derived from recorded sign-ins (V10): ACTIVE = signed in within 30 days, INACTIVE = last sign-in
 * longer ago, NO_LOGIN_RECORDED = no sign-in since tracking started. Names are not collected by VayuDrishti.
 */
@Service
public class AdminUserService {

    static final Duration ACTIVE_WINDOW = Duration.ofDays(30);
    private static final Map<String, String> SORT = Map.of("id", "u.id", "email", "u.email", "role", "u.role",
            "createdAt", "u.created_at", "lastLoginAt", "u.last_login_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AdminUserService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Status { ACTIVE, INACTIVE, NO_LOGIN_RECORDED }

    public record UserRow(long id, String name, String email, String role, Status status, Instant createdAt,
                          Instant lastLoginAt, String stationId, String stationName, boolean telegramLinked) {
    }

    public record Page(List<UserRow> items, long total, int page, int size) {
    }

    public record Stats(long totalUsers, long adminAccounts, long normalAccounts, long activeLast30Days,
                        long noLoginRecorded, long registeredLast7Days, long registeredLast30Days,
                        List<UserRow> recentRegistrations) {
    }

    public record Detail(UserRow account, String healthProfile, String language, String alertMinCategory,
                         LocalTime quietStart, LocalTime quietEnd, Boolean aqiAlertsEnabled,
                         Boolean morningBriefingEnabled, LocalTime morningBriefingTime, long notifications,
                         Instant lastNotificationAt) {
    }

    private static final String SELECT = """
            SELECT u.id, u.email, u.role, u.created_at, u.last_login_at, p.station_id, s.name AS station_name,
                   (p.telegram_chat_id IS NOT NULL) AS telegram_linked
            FROM users u LEFT JOIN profiles p ON p.user_id = u.id LEFT JOIN stations s ON s.id = p.station_id
            """;

    public Page list(String search, String role, Status status, String sort, String dir, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("active", Timestamp.from(now.minus(ACTIVE_WINDOW)))
                .addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        List<String> where = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.add("lower(u.email) LIKE :q ESCAPE '\\'");
            params.addValue("q", "%" + search.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (role != null && !role.isBlank()) {
            if (!role.equals("ADMIN") && !role.equals("USER")) {
                throw new IllegalArgumentException("role must be ADMIN or USER");
            }
            where.add("u.role = :role");
            params.addValue("role", role);
        }
        if (status != null) {
            where.add(switch (status) {
                case ACTIVE -> "u.last_login_at >= :active";
                case INACTIVE -> "u.last_login_at < :active";
                case NO_LOGIN_RECORDED -> "u.last_login_at IS NULL";
            });
        }
        String filter = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        String column = SORT.getOrDefault(sort == null ? "createdAt" : sort, "u.created_at");
        String direction = "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";
        Long total = jdbc.queryForObject("SELECT count(*) FROM users u" + filter, params, Long.class);
        List<UserRow> items = jdbc.query(SELECT + filter + " ORDER BY " + column + " " + direction
                + " NULLS LAST, u.id " + direction + " LIMIT :limit OFFSET :offset", params, (rs, i) -> row(rs, now));
        return new Page(items, total == null ? 0 : total, safePage, safeSize);
    }

    public Stats stats() {
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("active", Timestamp.from(now.minus(ACTIVE_WINDOW)))
                .addValue("d7", Timestamp.from(now.minus(Duration.ofDays(7))))
                .addValue("d30", Timestamp.from(now.minus(Duration.ofDays(30))));
        return jdbc.queryForObject("""
                SELECT count(*) total, count(*) FILTER (WHERE role = 'ADMIN') admins,
                       count(*) FILTER (WHERE role <> 'ADMIN') normal,
                       count(*) FILTER (WHERE last_login_at >= :active) active,
                       count(*) FILTER (WHERE last_login_at IS NULL) no_login,
                       count(*) FILTER (WHERE created_at >= :d7) reg7, count(*) FILTER (WHERE created_at >= :d30) reg30
                FROM users
                """, p, (rs, i) -> new Stats(rs.getLong("total"), rs.getLong("admins"), rs.getLong("normal"),
                rs.getLong("active"), rs.getLong("no_login"), rs.getLong("reg7"), rs.getLong("reg30"),
                list(null, null, null, "createdAt", "desc", 0, 5).items()));
    }

    public Detail detail(long id) {
        Instant now = clock.instant();
        UserRow account = jdbc.query(SELECT + " WHERE u.id = :id", new MapSqlParameterSource("id", id),
                (rs, i) -> row(rs, now)).stream().findFirst().orElseThrow(() -> new NotFoundException("User not found"));
        return jdbc.query("""
                SELECT p.health_profile, p.language, p.alert_min_category, p.quiet_start, p.quiet_end,
                       p.aqi_alerts_enabled, p.morning_briefing_enabled, p.morning_briefing_time,
                       (SELECT count(*) FROM alerts a WHERE a.user_id = :id) AS notifications,
                       (SELECT max(created_at) FROM alerts a WHERE a.user_id = :id) AS last_notification
                FROM (SELECT 1) one LEFT JOIN profiles p ON p.user_id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> new Detail(account, rs.getString("health_profile"),
                rs.getString("language"), rs.getString("alert_min_category"), time(rs, "quiet_start"),
                time(rs, "quiet_end"), (Boolean) rs.getObject("aqi_alerts_enabled"),
                (Boolean) rs.getObject("morning_briefing_enabled"), time(rs, "morning_briefing_time"),
                rs.getLong("notifications"), inst(rs, "last_notification"))).getFirst();
    }

    private static UserRow row(ResultSet rs, Instant now) throws SQLException {
        Instant last = inst(rs, "last_login_at");
        Status status = last == null ? Status.NO_LOGIN_RECORDED
                : last.isAfter(now.minus(ACTIVE_WINDOW)) ? Status.ACTIVE : Status.INACTIVE;
        return new UserRow(rs.getLong("id"), null, rs.getString("email"), rs.getString("role"), status,
                inst(rs, "created_at"), last, rs.getString("station_id"), rs.getString("station_name"),
                rs.getBoolean("telegram_linked"));
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static LocalTime time(ResultSet rs, String col) throws SQLException {
        java.sql.Time t = rs.getTime(col);
        return t == null ? null : t.toLocalTime();
    }
}
