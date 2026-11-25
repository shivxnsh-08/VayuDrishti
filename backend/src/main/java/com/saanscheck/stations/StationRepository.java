package com.saanscheck.stations;

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
 * Station registry. Plain SQL (not JPA) because the interesting queries are PostGIS ones.
 */
@Repository
public class StationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or refresh a station from the feed; coordinates are trusted from the latest record. */
    public void upsert(String id, String name, String city, String state, double lat, double lon, Instant seenAt) {
        jdbc.update("""
                INSERT INTO stations (id, name, city, state, geom, active, last_seen_at)
                VALUES (:id, :name, :city, :state,
                        ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, TRUE, :seen)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name, city = EXCLUDED.city, state = EXCLUDED.state,
                    geom = EXCLUDED.geom, active = TRUE,
                    last_seen_at = GREATEST(stations.last_seen_at, EXCLUDED.last_seen_at)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("name", name).addValue("city", city).addValue("state", state)
                .addValue("lat", lat).addValue("lon", lon).addValue("seen", Timestamp.from(seenAt)));
    }

    public boolean exists(String id) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM stations WHERE id = :id)",
                new MapSqlParameterSource("id", id), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<Station> findById(String id) {
        return jdbc.query("""
                        SELECT id, name, city, state, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon, active
                        FROM stations WHERE id = :id
                        """, new MapSqlParameterSource("id", id),
                (rs, i) -> new Station(rs.getString("id"), rs.getString("name"), rs.getString("city"),
                        rs.getString("state"), rs.getDouble("lat"), rs.getDouble("lon"), rs.getBoolean("active")))
                .stream().findFirst();
    }

    /**
     * Nearest active stations. {@code ORDER BY geom <-> point} is a KNN search that the GiST index
     * answers directly (index-ordered scan), so it does not compute the distance to every station.
     * ST_Distance on geography gives the exact spheroidal distance in metres for the response.
     */
    /** Raw row for forecast-station selection: distance, latest valid observation and valid hours since {@code since}. */
    public record CandidateRow(String id, String name, String city, String state, double distanceMeters,
                               Instant latestTs, Integer latestAqi, String category, int validHours) {
    }

    public List<CandidateRow> findNearestWithHistory(double lat, double lon, int limit, Instant since) {
        return jdbc.query("""
                        WITH p AS (SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS g)
                        SELECT s.id, s.name, s.city, s.state, ST_Distance(s.geom, p.g) AS distance_m,
                               latest.ts, latest.aqi, latest.category,
                               (SELECT count(*) FROM station_aqi h
                                 WHERE h.station_id = s.id AND h.ts >= :since AND h.aqi IS NOT NULL) AS valid_hours
                        FROM stations s CROSS JOIN p
                        LEFT JOIN LATERAL (SELECT a.ts, a.aqi, a.category FROM station_aqi a
                                           WHERE a.station_id = s.id AND a.aqi IS NOT NULL
                                           ORDER BY a.ts DESC LIMIT 1) latest ON true
                        WHERE s.active
                        ORDER BY s.geom <-> p.g
                        LIMIT :limit
                        """, new MapSqlParameterSource().addValue("lat", lat).addValue("lon", lon).addValue("limit", limit)
                        .addValue("since", java.sql.Timestamp.from(since)),
                (rs, i) -> new CandidateRow(rs.getString("id"), rs.getString("name"), rs.getString("city"),
                        rs.getString("state"), rs.getDouble("distance_m"),
                        rs.getTimestamp("ts") == null ? null : rs.getTimestamp("ts").toInstant(),
                        (Integer) rs.getObject("aqi"), rs.getString("category"), rs.getInt("valid_hours")));
    }

    public List<NearestStation> findNearest(double lat, double lon, int limit) {
        return jdbc.query("""
                        WITH p AS (SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS g)
                        SELECT s.id, s.name, s.city, s.state,
                               ST_Y(s.geom::geometry) AS lat, ST_X(s.geom::geometry) AS lon,
                               ST_Distance(s.geom, p.g) AS distance_m
                        FROM stations s, p
                        WHERE s.active
                        ORDER BY s.geom <-> p.g
                        LIMIT :limit
                        """, new MapSqlParameterSource().addValue("lat", lat).addValue("lon", lon).addValue("limit", limit),
                StationRepository::mapNearest);
    }

    /** Stations that at least one user is subscribed to; only these get forecasts generated on schedule. */
    public List<String> findSubscribedStationIds() {
        return jdbc.queryForList("SELECT DISTINCT station_id FROM profiles WHERE station_id IS NOT NULL",
                new MapSqlParameterSource(), String.class);
    }

    /**
     * Every active station with its latest hourly AQI (null when it has none yet), for the map and
     * dashboard. LATERAL + ORDER BY ts DESC LIMIT 1 uses the station_aqi primary key per station.
     */
    public List<StationSummary> findAllWithLatest() {
        return jdbc.query("""
                SELECT s.id, s.name, s.city, s.state,
                       ST_Y(s.geom::geometry) AS lat, ST_X(s.geom::geometry) AS lon,
                       a.ts, a.aqi, a.category, a.dominant_pollutant
                FROM stations s
                LEFT JOIN LATERAL (
                    SELECT ts, aqi, category, dominant_pollutant FROM station_aqi
                    WHERE station_id = s.id ORDER BY ts DESC LIMIT 1) a ON TRUE
                WHERE s.active
                ORDER BY s.state, s.city, s.name
                """, new MapSqlParameterSource(), (rs, i) -> {
            Timestamp ts = rs.getTimestamp("ts");
            return new StationSummary(rs.getString("id"), rs.getString("name"), rs.getString("city"),
                    rs.getString("state"), rs.getDouble("lat"), rs.getDouble("lon"),
                    ts == null ? null : ts.toInstant(), (Integer) rs.getObject("aqi"),
                    rs.getString("category"), rs.getString("dominant_pollutant"), false);
        });
    }

    /** Data gaps: active stations that did not appear in the feed since {@code since}. */
    public int countActiveNotSeenSince(Instant since) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM stations WHERE active AND (last_seen_at IS NULL OR last_seen_at < :since)
                """, new MapSqlParameterSource("since", Timestamp.from(since)), Integer.class);
        return n == null ? 0 : n;
    }

    public int countActive() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM stations WHERE active",
                new MapSqlParameterSource(), Integer.class);
        return n == null ? 0 : n;
    }

    private static NearestStation mapNearest(ResultSet rs, int i) throws SQLException {
        return new NearestStation(rs.getString("id"), rs.getString("name"), rs.getString("city"),
                rs.getString("state"), rs.getDouble("lat"), rs.getDouble("lon"), rs.getDouble("distance_m"));
    }
}
