package com.saanscheck.ingestion;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReadingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Dedupe by primary key (station, pollutant, source timestamp): fetching the same snapshot
     * twice is a no-op. Returns true only when a new row was written.
     */
    public boolean insertIfAbsent(ParsedReading r) {
        int n = jdbc.update("""
                INSERT INTO readings (station_id, pollutant, ts, value, quality, raw)
                VALUES (:sid, :pol, :ts, :val, :q, CAST(:raw AS jsonb))
                ON CONFLICT (station_id, pollutant, ts) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("sid", r.stationId()).addValue("pol", r.pollutant())
                .addValue("ts", Timestamp.from(r.ts())).addValue("val", r.value())
                .addValue("q", r.quality().name()).addValue("raw", r.rawJson()));
        return n == 1;
    }

    /**
     * Most-recent-first values (OK or STUCK) with {@code since <= ts < before}. Used for stuck-sensor
     * detection so that readings from before an outage (a time gap) never count as "the same value for N hours".
     */
    public List<Double> recentValuesSince(String stationId, String pollutant, Instant since, Instant before, int limit) {
        return jdbc.queryForList("""
                SELECT value FROM readings
                WHERE station_id = :sid AND pollutant = :pol AND ts >= :since AND ts < :before
                  AND value IS NOT NULL AND quality IN ('OK', 'STUCK')
                ORDER BY ts DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("sid", stationId).addValue("pol", pollutant)
                .addValue("since", Timestamp.from(since)).addValue("before", Timestamp.from(before))
                .addValue("limit", limit), Double.class);
    }

    public Map<String, Long> countByQuality(Instant since) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT quality, count(*) AS n FROM readings WHERE ts >= :since GROUP BY quality ORDER BY quality
                """, new MapSqlParameterSource("since", Timestamp.from(since)),
                rs -> {
                    out.put(rs.getString("quality"), rs.getLong("n"));
                });
        return out;
    }

    public List<FlaggedSeries> flaggedSeries(Instant since, String quality, int limit) {
        return jdbc.query("""
                SELECT station_id, pollutant, count(*) AS n FROM readings
                WHERE ts >= :since AND quality = :q
                GROUP BY station_id, pollutant ORDER BY n DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("since", Timestamp.from(since))
                .addValue("q", quality).addValue("limit", limit),
                (rs, i) -> new FlaggedSeries(rs.getString("station_id"), rs.getString("pollutant"), rs.getLong("n")));
    }

    public record FlaggedSeries(String stationId, String pollutant, long count) {
    }
}
