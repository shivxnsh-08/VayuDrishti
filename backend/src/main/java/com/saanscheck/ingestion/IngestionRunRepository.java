package com.saanscheck.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistent ingestion history. Survives restarts; every run, including failures, gets a row. */
@Repository
public class IngestionRunRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public IngestionRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(Instant startedAt, String trigger) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ingestion_runs (started_at, status, trigger, fetched, inserted, rejected, missing, stuck,
                                            duplicates, retry_count)
                VALUES (:t, 'RUNNING', :trigger, 0, 0, 0, 0, 0, 0, 0) RETURNING id
                """, new MapSqlParameterSource("t", Timestamp.from(startedAt)).addValue("trigger", trigger), Long.class);
        return id == null ? -1 : id;
    }

    public void finish(long id, IngestionStats s, String status, String error, Instant startedAt, Instant finishedAt) {
        jdbc.update("""
                UPDATE ingestion_runs SET finished_at = :f, fetched = :fe, inserted = :in, rejected = :re,
                    missing = :mi, stuck = :st, duplicates = :du, payload_duplicates = :pd,
                    expected = :ex, status = :status, error = :err, duration_ms = :dur, retry_count = :rc,
                    error_category = :cat, http_status = :http, latest_source_ts = :lts,
                    rejection_reasons = CAST(:rr AS jsonb)
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("f", Timestamp.from(finishedAt)).addValue("fe", s.fetched).addValue("in", s.inserted)
                .addValue("re", s.rejected).addValue("mi", s.missing).addValue("st", s.stuck)
                .addValue("du", s.duplicates).addValue("pd", s.payloadDuplicates)
                .addValue("ex", s.expected < 0 ? null : s.expected).addValue("status", status).addValue("err", error)
                .addValue("dur", finishedAt.toEpochMilli() - startedAt.toEpochMilli()).addValue("rc", s.retries)
                .addValue("cat", s.errorCategory == null ? null : s.errorCategory.name())
                .addValue("http", s.httpStatus)
                .addValue("lts", s.latestSourceTs == null ? null : Timestamp.from(s.latestSourceTs))
                .addValue("rr", s.rejectionReasons.isEmpty() ? null : toJson(s.rejectionReasons))
                .addValue("id", id));
    }

    /**
     * Marks RUNNING rows as INTERRUPTED. Only call while holding the ingestion lock: then no other run is
     * in progress anywhere, so any RUNNING row belongs to a process that died (restart, crash, OOM).
     */
    public int markInterrupted(Instant at) {
        return jdbc.update("""
                UPDATE ingestion_runs SET status = 'INTERRUPTED', finished_at = :at,
                       duration_ms = (EXTRACT(EPOCH FROM (CAST(:at AS timestamptz) - started_at)) * 1000)::bigint,
                       error = 'process stopped during the run (restart or crash); records already stored are kept'
                WHERE status = 'RUNNING'
                """, new MapSqlParameterSource("at", Timestamp.from(at)));
    }

    public List<IngestionRun> recent(int limit) {
        return jdbc.query("SELECT * FROM ingestion_runs ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource("limit", limit), IngestionRunRepository::map);
    }

    public Optional<IngestionRun> lastSuccessful() {
        return jdbc.query("""
                SELECT * FROM ingestion_runs WHERE status IN ('SUCCESS', 'PARTIAL') ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource(), IngestionRunRepository::map).stream().findFirst();
    }

    public Optional<Instant> lastSuccessfulFetch() {
        return jdbc.query("""
                SELECT finished_at FROM ingestion_runs WHERE fetched > 0 ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource(), (rs, i) -> toInstant(rs.getTimestamp(1))).stream().findFirst();
    }

    public double successRate(Instant since) {
        Double r = jdbc.queryForObject("""
                SELECT COALESCE(avg(CASE WHEN status = 'SUCCESS' THEN 1.0 ELSE 0.0 END), 0)
                FROM ingestion_runs WHERE started_at >= :since AND status <> 'RUNNING'
                """, new MapSqlParameterSource("since", Timestamp.from(since)), Double.class);
        return r == null ? 0 : r;
    }

    private static IngestionRun map(ResultSet rs, int i) throws SQLException {
        return new IngestionRun(
                rs.getLong("id"),
                toInstant(rs.getTimestamp("started_at")), toInstant(rs.getTimestamp("finished_at")),
                rs.getInt("fetched"), rs.getInt("inserted"), rs.getInt("rejected"), rs.getInt("missing"),
                rs.getInt("stuck"), rs.getInt("duplicates"), rs.getInt("payload_duplicates"),
                (Integer) rs.getObject("expected"), rs.getString("status"), rs.getString("error"),
                rs.getString("trigger"), (Long) rs.getObject("duration_ms"), (Integer) rs.getObject("retry_count"),
                rs.getString("error_category"), (Integer) rs.getObject("http_status"),
                toInstant(rs.getTimestamp("latest_source_ts")), fromJson(rs.getString("rejection_reasons")));
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String toJson(Map<String, Integer> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Integer> fromJson(String s) {
        if (s == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(s, new TypeReference<Map<String, Integer>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
