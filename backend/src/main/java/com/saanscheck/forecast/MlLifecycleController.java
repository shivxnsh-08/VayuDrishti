package com.saanscheck.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN view of the automated ML lifecycle (protected by the /api/v1/admin/** rule in SecurityConfig).
 * Reads the backend-owned ml_* tables directly, so it keeps working while the forecast service is down,
 * and adds the forecast service's live /model-info when it is reachable. Read-only.
 */
@RestController
@RequestMapping("/api/v1/admin/ml")
@Validated
public class MlLifecycleController {

    private final JdbcTemplate jdbc;
    private final ForecastClient forecastClient;
    private final ObjectMapper json;

    public MlLifecycleController(JdbcTemplate jdbc, ForecastClient forecastClient, ObjectMapper json) {
        this.jdbc = jdbc;
        this.forecastClient = forecastClient;
        this.json = json;
    }

    @GetMapping("/lifecycle")
    public Map<String, Object> lifecycle(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int runs,
                                         @RequestParam(defaultValue = "50") @Min(1) @Max(500) int events) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, JsonNode> state = new LinkedHashMap<>();
        jdbc.query("SELECT key, value::text AS value FROM ml_lifecycle_state ORDER BY key",
                rs -> {
                    state.put(rs.getString("key"), parse(rs.getString("value")));
                });
        out.put("state", state);
        out.put("trainingRuns", jdbc.query("""
                SELECT id, trigger, status, attempt, started_at, finished_at, dataset_fingerprint, data_first_ts,
                       data_last_ts, observations, stations, model_version, model_name, metrics::text AS metrics,
                       reasons, error
                FROM ml_training_runs ORDER BY id DESC LIMIT ?
                """, this::run, runs));
        out.put("events", jdbc.query("""
                SELECT id, at, event_type, message, detail::text AS detail
                FROM ml_lifecycle_events ORDER BY id DESC LIMIT ?
                """, (rs, i) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", rs.getLong("id"));
            e.put("at", ts(rs, "at"));
            e.put("eventType", rs.getString("event_type"));
            e.put("message", rs.getString("message"));
            e.put("detail", parse(rs.getString("detail")));
            return e;
        }, events));
        try {
            out.put("modelInfo", forecastClient.modelInfo());
            out.put("forecastServiceAvailable", true);
        } catch (RuntimeException e) {
            out.put("modelInfo", null);
            out.put("forecastServiceAvailable", false);
        }
        return out;
    }

    private Map<String, Object> run(ResultSet rs, int i) throws SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", rs.getLong("id"));
        r.put("trigger", rs.getString("trigger"));
        r.put("status", rs.getString("status"));
        r.put("attempt", rs.getInt("attempt"));
        r.put("startedAt", ts(rs, "started_at"));
        r.put("finishedAt", ts(rs, "finished_at"));
        r.put("datasetFingerprint", rs.getString("dataset_fingerprint"));
        r.put("dataFirstTs", ts(rs, "data_first_ts"));
        r.put("dataLastTs", ts(rs, "data_last_ts"));
        r.put("observations", rs.getObject("observations"));
        r.put("stations", rs.getObject("stations"));
        r.put("modelVersion", rs.getString("model_version"));
        r.put("modelName", rs.getString("model_name"));
        r.put("metrics", parse(rs.getString("metrics")));
        r.put("reasons", rs.getString("reasons"));
        r.put("error", rs.getString("error"));
        return r;
    }

    private JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(text);
        }
    }

    private static String ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant().toString();
    }
}
