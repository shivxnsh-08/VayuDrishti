package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * V4 migration + admin lifecycle endpoint. Rows are inserted the way the forecast service's lifecycle
 * manager writes them (same tables, same JSON columns).
 */
class MlLifecycleIT extends AbstractIT {

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE ml_training_runs, ml_lifecycle_events, ml_lifecycle_state RESTART IDENTITY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminSeesRunsEventsStateAndModelInfo() {
        jdbc.update("""
                INSERT INTO ml_training_runs (trigger, status, attempt, finished_at, dataset_fingerprint, observations,
                                              stations, model_version, model_name, metrics, reasons)
                VALUES ('INITIAL', 'NOT_PROMOTED', 1, now(), 'abc123', 376000, 560, 'random_forest-20261220T020000',
                        'random_forest', '{"model_mae": 31.2, "baselines": {"persistence": {"mae": 30.1}}}'::jsonb,
                        'does not beat the persistence baseline')
                """);
        jdbc.update("INSERT INTO ml_lifecycle_events (event_type, message, detail) VALUES ('READINESS', 'readiness not met', '{\"span_hours\": 6}'::jsonb)");
        jdbc.update("INSERT INTO ml_lifecycle_state (key, value) VALUES ('next_readiness_check', '{\"at\": \"2026-11-22T22:00:00+00:00\"}'::jsonb)");
        WIREMOCK.stubFor(get(urlEqualTo("/model-info")).willReturn(okJson(
                "{\"model_loaded\": false, \"serving\": \"BASELINE_PERSISTENCE\", \"lifecycle\": {\"forecasting_method\": \"BASELINE_PERSISTENCE\"}}")));

        String admin = register("admin@test.local");
        var r = exchange(HttpMethod.GET, "/api/v1/admin/ml/lifecycle", admin, null, Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = r.getBody();
        List<Map<String, Object>> runs = (List<Map<String, Object>>) body.get("trainingRuns");
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst()).containsEntry("status", "NOT_PROMOTED").containsEntry("trigger", "INITIAL")
                .containsEntry("reasons", "does not beat the persistence baseline");
        assertThat((Map<String, Object>) runs.getFirst().get("metrics")).containsEntry("model_mae", 31.2);
        assertThat((List<Map<String, Object>>) body.get("events")).extracting(e -> e.get("eventType")).containsExactly("READINESS");
        assertThat((Map<String, Object>) body.get("state")).containsKey("next_readiness_check");
        assertThat(body).containsEntry("forecastServiceAvailable", true);
    }

    @Test
    void worksWhenForecastServiceIsDownAndIsAdminOnly() {
        WIREMOCK.stubFor(get(urlEqualTo("/model-info")).willReturn(aResponse().withStatus(503)));
        String admin = register("admin@test.local");
        var r = exchange(HttpMethod.GET, "/api/v1/admin/ml/lifecycle", admin, null, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")   // TestRestTemplate returns a raw Map for Map.class
        Map<String, Object> body = r.getBody();
        assertThat(body).containsEntry("forecastServiceAvailable", false);

        String user = register("user@test.local");
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ml/lifecycle", user, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/v1/admin/ml/lifecycle", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
