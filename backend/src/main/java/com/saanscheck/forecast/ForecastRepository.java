package com.saanscheck.forecast;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/** Every forecast batch is stored: needed for the API cache, the alert engine and later evaluation. */
@Repository
public class ForecastRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ForecastRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveBatch(String stationId, Instant generatedAt, String modelVersion, String method,
                          List<ForecastDtos.Prediction> predictions) {
        SqlParameterSource[] batch = predictions.stream().map(p -> new MapSqlParameterSource()
                .addValue("sid", stationId).addValue("gen", Timestamp.from(generatedAt))
                .addValue("target", Timestamp.from(p.targetTs())).addValue("pred", p.predictedAqi())
                .addValue("lo", p.lower()).addValue("hi", p.upper())
                .addValue("ver", truncate(modelVersion, 32)).addValue("method", truncate(method, 32)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO forecasts (station_id, generated_at, target_ts, predicted_aqi, lower, upper, model_version, method)
                VALUES (:sid, :gen, :target, :pred, :lo, :hi, :ver, :method)
                ON CONFLICT (station_id, generated_at, target_ts) DO NOTHING
                """, batch);
    }

    public Optional<Batch> findLatestBatch(String stationId) {
        List<Row> rows = jdbc.query("""
                SELECT generated_at, target_ts, predicted_aqi, lower, upper, model_version, method
                FROM forecasts
                WHERE station_id = :sid
                  AND generated_at = (SELECT max(generated_at) FROM forecasts WHERE station_id = :sid)
                ORDER BY target_ts
                """, new MapSqlParameterSource("sid", stationId), (rs, i) -> new Row(
                rs.getTimestamp("generated_at").toInstant(), rs.getTimestamp("target_ts").toInstant(),
                rs.getInt("predicted_aqi"), (Integer) rs.getObject("lower"), (Integer) rs.getObject("upper"),
                rs.getString("model_version"), rs.getString("method")));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row first = rows.getFirst();
        return Optional.of(new Batch(first.generatedAt(), first.modelVersion(), first.method(),
                rows.stream().map(r -> new ForecastDtos.Prediction(r.targetTs(), r.predicted(), r.lower(), r.upper()))
                        .toList()));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public record Batch(Instant generatedAt, String modelVersion, String method,
                        List<ForecastDtos.Prediction> predictions) {
    }

    private record Row(Instant generatedAt, Instant targetTs, int predicted, Integer lower, Integer upper,
                       String modelVersion, String method) {
    }
}
