package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.alerts.AlertDispatcher;
import com.saanscheck.alerts.AlertEvaluator;
import com.saanscheck.forecast.ForecastView;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Delivery failure classification and retry limits (Priority 3 of the audit). A short Telegram
 * read timeout lets the "outcome unknown" path run quickly.
 */
@TestPropertySource(properties = {"saanscheck.telegram.read-timeout=500ms", "saanscheck.alerts.max-attempts=3"})
class AlertDeliveryIT extends AbstractIT {

    private static final String SEND = "/botTEST-TOKEN/sendMessage";

    @Autowired
    AlertEvaluator evaluator;
    @Autowired
    AlertDispatcher dispatcher;

    @BeforeEach
    void seed() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        for (int h = 30; h >= 0; h--) {
            insertAqi("ito", T0.minus(Duration.ofHours(h)), 260, "POOR");   // current AQI already POOR
        }
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (1, 'u@test.local', 'x')");
        jdbc.update("""
                INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id)
                VALUES (1, 'GENERAL', 'ito', 'en', '12345')
                """);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(503)));
        WIREMOCK.stubFor(post(urlEqualTo("/advice")).willReturn(aResponse().withStatus(500)));
    }

    private Map<String, Object> onlyAlert() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts", Integer.class)).isEqualTo(1);
        return jdbc.queryForMap("SELECT * FROM alerts");
    }

    @Test
    void blockedBotIsPermanentAndNotRetried() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(403)
                .withBody("{\"ok\":false,\"description\":\"Forbidden: bot was blocked by the user\"}")));
        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        clock.advance(Duration.ofHours(1));
        dispatcher.dispatchDue();

        assertThat(onlyAlert()).containsEntry("status", "FAILED").containsEntry("attempts", 1);
        assertThat((String) onlyAlert().get("last_error")).startsWith("PERMANENT").contains("blocked");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void telegramFloodControlRetryAfterIsHonoured() {
        // 429 with retry_after=600 s: the next attempt must wait 10 min, not the 1-min exponential backoff.
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(429)
                .withBody("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 600\","
                        + "\"parameters\":{\"retry_after\":600}}")));
        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        assertThat(onlyAlert()).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(((java.sql.Timestamp) onlyAlert().get("next_attempt_at")).toInstant())
                .isEqualTo(T0.plus(Duration.ofSeconds(600)));

        clock.advance(Duration.ofMinutes(2));
        dispatcher.dispatchDue();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));             // still waiting

        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        clock.advance(Duration.ofMinutes(9));
        dispatcher.dispatchDue();
        assertThat(onlyAlert()).containsEntry("status", "SENT");
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo(SEND)));             // exactly one resend: no duplicate
    }

    @Test
    void currentAlertStatesStationAqiCategoryAndObservationTime() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        String msg = (String) onlyAlert().get("message");
        // T0 = 2026-11-20 04:30 UTC = 10:00 IST; the current reading is the genuine stored observation.
        assertThat(onlyAlert()).containsEntry("kind", "AQI_THRESHOLD_CROSSED");
        assertThat(com.saanscheck.telegram.TelegramHtml.isValid(msg)).isTrue();
        assertThat(msg).contains("<b>Monitoring station:</b> ITO").contains("<b>Current AQI:</b> <b>260</b>")
                .contains("<b>Category:</b> Poor").contains("<b>Observation time:</b> 20 Nov 2026, 10:00 IST")
                .contains("<b>Data age:</b> 0 min");
        assertThat(msg).doesNotContain(" now.");
    }

    @Test
    void retryableFailuresStopAfterMaxAttempts() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(502)));
        evaluator.evaluateAll();
        for (int i = 0; i < 6; i++) {
            dispatcher.dispatchDue();
            clock.advance(Duration.ofMinutes(10));   // past every backoff (1, 2, 4 min)
        }
        assertThat(onlyAlert()).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        WIREMOCK.verify(3, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void readTimeoutIsAmbiguousAndNotRetried() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\":true}").withFixedDelay(1500)));
        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        clock.advance(Duration.ofHours(1));
        dispatcher.dispatchDue();

        assertThat(onlyAlert()).containsEntry("status", "FAILED");
        assertThat((String) onlyAlert().get("last_error")).startsWith("UNKNOWN");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void forecastAndAdviceErrorsDegradeInsteadOfFailingTheAlert() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\":true}")));
        assertThat(http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class).status())
                .isEqualTo(ForecastView.Status.UNAVAILABLE);

        evaluator.evaluateAll();
        dispatcher.dispatchDue();

        Map<String, Object> alert = onlyAlert();
        assertThat(alert).containsEntry("status", "SENT").containsEntry("kind", "AQI_THRESHOLD_CROSSED");
        assertThat((String) alert.get("message")).contains("temporarily unavailable");
    }

    @Test
    void forecastServiceRejection422IsNotRetried() {
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json").withBody("{\"detail\":\"insufficient history\"}")));
        assertThat(http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class).status())
                .isEqualTo(ForecastView.Status.UNAVAILABLE);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/forecast")));
    }
}
