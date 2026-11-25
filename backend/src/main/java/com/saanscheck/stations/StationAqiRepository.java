package com.saanscheck.stations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Derived hourly AQI per station: writes from ingestion, reads for API, forecasting and alerts. */
@Repository
public class StationAqiRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StationAqiRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(StationAqi row) {
        jdbc.update("""
                INSERT INTO station_aqi (station_id, ts, aqi, category, dominant_pollutant, pollutant_count, stale)
                VALUES (:sid, :ts, :aqi, :cat, :dom, :cnt, FALSE)
                ON CONFLICT (station_id, ts) DO UPDATE SET
                    aqi = EXCLUDED.aqi, category = EXCLUDED.category,
                    dominant_pollutant = EXCLUDED.dominant_pollutant, pollutant_count = EXCLUDED.pollutant_count
                """, new MapSqlParameterSource()
                .addValue("sid", row.stationId()).addValue("ts", Timestamp.from(row.ts()))
                .addValue("aqi", row.aqi()).addValue("cat", row.category())
                .addValue("dom", row.dominantPollutant()).addValue("cnt", row.pollutantCount()));
    }

    public Optional<StationAqi> findLatest(String stationId) {
        return jdbc.query("""
                SELECT station_id, ts, aqi, category, dominant_pollutant, pollutant_count
                FROM station_aqi WHERE station_id = :sid ORDER BY ts DESC LIMIT 1
                """, new MapSqlParameterSource("sid", stationId), StationAqiRepository::map).stream().findFirst();
    }

    /** Ascending series used as forecast input and for alert replay. */
    public List<StationAqi> findSeries(String stationId, Instant from) {
        return jdbc.query("""
                SELECT station_id, ts, aqi, category, dominant_pollutant, pollutant_count
                FROM station_aqi WHERE station_id = :sid AND ts >= :from AND aqi IS NOT NULL ORDER BY ts
                """, new MapSqlParameterSource().addValue("sid", stationId).addValue("from", Timestamp.from(from)),
                StationAqiRepository::map);
    }

    /** History page with the non-OK reading flags for each hour. */
    public List<AqiHistoryPoint> findHistory(String stationId, Instant from, Instant to, int limit, int offset) {
        return jdbc.query("""
                SELECT a.ts, a.aqi, a.category, a.dominant_pollutant, a.pollutant_count,
                       (SELECT string_agg(DISTINCT r.pollutant || ':' || r.quality, ',')
                          FROM readings r
                         WHERE r.station_id = a.station_id
                           AND r.ts >= a.ts AND r.ts < a.ts + INTERVAL '1 hour'
                           AND r.quality <> 'OK') AS flags
                FROM station_aqi a
                WHERE a.station_id = :sid AND a.ts >= :from AND a.ts < :to
                ORDER BY a.ts DESC
                LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("sid", stationId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("limit", limit).addValue("offset", offset), (rs, i) -> {
            String flags = rs.getString("flags");
            return new AqiHistoryPoint(rs.getTimestamp("ts").toInstant(), (Integer) rs.getObject("aqi"),
                    rs.getString("category"), rs.getString("dominant_pollutant"),
                    (Integer) rs.getObject("pollutant_count"),
                    flags == null ? List.of() : Arrays.asList(flags.split(",")));
        });
    }

    public long countHistory(String stationId, Instant from, Instant to) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM station_aqi WHERE station_id = :sid AND ts >= :from AND ts < :to
                """, new MapSqlParameterSource().addValue("sid", stationId)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)), Long.class);
        return n == null ? 0 : n;
    }

    /** Newest stored observation hour across all stations (null if none). */
    public Instant latestObservationTs() {
        Timestamp t = jdbc.queryForObject("SELECT max(ts) FROM station_aqi WHERE aqi IS NOT NULL",
                new MapSqlParameterSource(), Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    /** Active stations whose latest AQI is older than the cutoff (or that have none at all). */
    public int countStaleStations(Instant cutoff) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM stations s
                WHERE s.active AND NOT EXISTS (
                    SELECT 1 FROM station_aqi a WHERE a.station_id = s.id AND a.ts >= :cutoff)
                """, new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Hours without an AQI value per station, over the last {@code windowHours} feed hours.
     * Slots are anchored on the newest stored hour (feed hours are IST hours, i.e. :30 UTC) and a
     * station is only expected from its first observation onwards, so new stations are not penalised.
     * A slot can be empty because the station did not report or because too few pollutants were valid.
     */
    public List<MissingHours> missingHours(int windowHours, int limit) {
        return jdbc.query("""
                WITH latest AS (SELECT max(ts) AS t FROM station_aqi),
                     slots AS (
                         SELECT generate_series((SELECT t FROM latest) - make_interval(hours => :n - 1),
                                                (SELECT t FROM latest), INTERVAL '1 hour') AS ts),
                     first_seen AS (SELECT station_id, min(ts) AS first_ts FROM station_aqi GROUP BY station_id)
                SELECT f.station_id, count(*) AS missing
                FROM first_seen f
                JOIN stations st ON st.id = f.station_id AND st.active
                JOIN slots s ON s.ts >= f.first_ts
                LEFT JOIN station_aqi a ON a.station_id = f.station_id AND a.ts = s.ts
                WHERE a.station_id IS NULL
                GROUP BY f.station_id
                ORDER BY missing DESC, f.station_id
                LIMIT :limit
                """, new MapSqlParameterSource().addValue("n", windowHours).addValue("limit", limit),
                (rs, i) -> new MissingHours(rs.getString("station_id"), rs.getInt("missing")));
    }

    public record MissingHours(String stationId, int missingHours) {
    }

    private static StationAqi map(ResultSet rs, int i) throws SQLException {
        return new StationAqi(rs.getString("station_id"), rs.getTimestamp("ts").toInstant(),
                (Integer) rs.getObject("aqi"), rs.getString("category"), rs.getString("dominant_pollutant"),
                (Integer) rs.getObject("pollutant_count"));
    }
}
