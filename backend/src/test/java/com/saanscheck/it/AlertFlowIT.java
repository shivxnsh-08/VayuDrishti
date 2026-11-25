package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.alerts.AlertDispatcher;
import com.saanscheck.alerts.AlertEvaluator;
import com.saanscheck.alerts.AlertRepository;
import com.saanscheck.forecast.ForecastView;
import com.saanscheck.stations.CurrentAqiView;
import com.saanscheck.telegram.TelegramClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end alert pipeline against WireMock stand-ins: idempotency (repeated evaluation and a
 * simulated crash between send and save) and degraded modes (forecast, advice, Telegram down).
 */
class AlertFlowIT extends AbstractIT {

    private static final String SEND = "/botTEST-TOKEN/sendMessage";
    private static final String ADVICE_JSON = """
            {"message": "Avoid outdoor exercise. General guidance only, not medical advice.",
             "action_codes": ["AVOID_OUTDOOR_EXERCISE"], "source": "TEMPLATE"}
            """;

    @Autowired
    AlertEvaluator evaluator;
    @Autowired
    AlertDispatcher dispatcher;
    @Autowired
    AlertRepository alerts;
    @Autowired
    TelegramClient telegram;

    @BeforeEach
    void seed() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (1, 'u@test.local', 'x')");
        jdbc.update("""
                INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id)
                VALUES (1, 'GENERAL', 'ito', 'en', '12345')
                """);
        WIREMOCK.stubFor(post(urlEqualTo("/advice")).willReturn(okJson(ADVICE_JSON)));
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\": true}")));
    }

    private void seedHistory(int currentAqi) {
        for (int h = 30; h >= 1; h--) {
            insertAqi("ito", T0.minus(Duration.ofHours(h)), 140, "MODERATE");
        }
        insertAqi("ito", T0, currentAqi, currentAqi > 200 ? "POOR" : "MODERATE");
    }

    private static String forecastJson(int... aqis) {
        StringJoiner preds = new StringJoiner(",");
        for (int h = 0; h < aqis.length; h++) {
            Instant t = T0.plus(Duration.ofHours(h + 1));
            preds.add("{\"target_ts\":\"%s\",\"predicted_aqi\":%d,\"lower\":%d,\"upper\":%d}"
                    .formatted(t, aqis[h], aqis[h] - 20, aqis[h] + 20));
        }
        return """
                {"station_id":"ito","generated_at":"%s","origin_ts":"%s","model_version":"test-v1",
                 "method":"MODEL","predictions":[%s]}
                """.formatted(T0, T0, preds);
    }

    private Map<String, Object> onlyAlert() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts", Integer.class)).isEqualTo(1);
        return jdbc.queryForMap("SELECT * FROM alerts");
    }

    @Test
    void repeatedEvaluationProducesExactlyOneAlertAndOneMessage() {
        seedHistory(150);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(forecastJson(170, 240, 320, 300))));

        evaluator.evaluateAll();
        clock.advance(Duration.ofMinutes(10));
        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();

        Map<String, Object> alert = onlyAlert();
        assertThat(alert).containsEntry("status", "SENT").containsEntry("kind", "FORECAST")
                .containsEntry("category", "VERY_POOR").containsEntry("peak_aqi", 320);
        assertThat((String) alert.get("message")).contains("ITO").contains("320").contains("not medical advice");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void crashBetweenSendAndSaveDoesNotResend() {
        seedHistory(150);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(forecastJson(170, 240, 320))));
        evaluator.evaluateAll();

        // Simulate a dispatcher that claimed the alert, sent it, then died before marking it SENT.
        var claimed = alerts.claimDue(clock.instant(), 10);
        assertThat(claimed).hasSize(1);
        telegram.sendMessage("12345", claimed.getFirst().message());

        // After restart: recovery runs, evaluation runs again.
        clock.advance(Duration.ofMinutes(10));
        dispatcher.dispatchDue();
        evaluator.evaluateAll();
        dispatcher.dispatchDue();

        Map<String, Object> alert = onlyAlert();
        assertThat(alert).containsEntry("status", "FAILED");
        assertThat((String) alert.get("last_error")).contains("outcome unknown");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));   // the user saw exactly one message
    }

    @Test
    void telegramDownKeepsAlertPendingAndRetriesWithBackoff() {
        seedHistory(150);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(forecastJson(320))));
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(503)));

        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        Map<String, Object> afterFailure = onlyAlert();
        assertThat(afterFailure).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(((Timestamp) afterFailure.get("next_attempt_at")).toInstant()).isAfter(clock.instant());

        dispatcher.dispatchDue();   // still inside backoff: no new attempt
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));

        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\": true}")));
        clock.advance(Duration.ofMinutes(2));
        dispatcher.dispatchDue();
        assertThat(onlyAlert()).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void forecastServiceDownStillServesCurrentAqiAndAlertsOnCurrent() {
        seedHistory(260);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(500)));

        ForecastView fv = http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class);
        assertThat(fv.status()).isEqualTo(ForecastView.Status.UNAVAILABLE);
        CurrentAqiView current = http.getForObject("/api/v1/stations/ito/aqi/current", CurrentAqiView.class);
        assertThat(current.aqi()).isEqualTo(260);

        evaluator.evaluateAll();
        assertThat(onlyAlert()).containsEntry("kind", "AQI_THRESHOLD_CROSSED").containsEntry("category", "POOR");
    }

    @Test
    void adviceServiceDownFallsBackToTemplate() {
        seedHistory(150);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(forecastJson(320))));
        WIREMOCK.stubFor(post(urlEqualTo("/advice")).willReturn(aResponse().withStatus(500)));

        evaluator.evaluateAll();
        assertThat((String) onlyAlert().get("message"))
                .contains("temporarily unavailable").contains("not medical advice");
    }

    @Test
    void staleStationGetsOneNoticePerDayInsteadOfForecastAlert() {
        seedHistory(400);
        clock.advance(Duration.ofHours(5));
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(forecastJson(450))));

        evaluator.evaluateAll();
        evaluator.evaluateAll();
        assertThat(onlyAlert()).containsEntry("kind", "STALE_NOTICE");
    }
}
