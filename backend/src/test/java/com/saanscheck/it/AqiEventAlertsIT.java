package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.alerts.AlertDispatcher;
import com.saanscheck.alerts.AlertEvaluator;
import com.saanscheck.briefing.MorningBriefingService;
import com.saanscheck.telegram.TelegramHtml;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Change-based AQI alerts end to end: real PostgreSQL, the real evaluator and dispatcher, MOCKED Telegram and
 * advice (WireMock; no real Telegram message is sent). Alert level: MODERATE. T0 = 20 Nov 10:00 IST.
 */
class AqiEventAlertsIT extends AbstractIT {

    private static final String SEND = "/botTEST-TOKEN/sendMessage";

    @Autowired
    AlertEvaluator evaluator;
    @Autowired
    AlertDispatcher dispatcher;
    @Autowired
    MorningBriefingService briefings;

    @BeforeEach
    void seed() {
        insertStation("ito", "ITO, Delhi - CPCB", 28.6286, 77.2410, true);
        jdbc.execute("TRUNCATE alert_state");
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (1, 'u@test.local', 'x')");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('users', 'id'), 1000)", Long.class);   // API users get new ids
        jdbc.update("""
                INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id, alert_min_category)
                VALUES (1, 'GENERAL', 'ito', 'en', '555', 'MODERATE')
                """);
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\":true}")));
        WIREMOCK.stubFor(post(urlEqualTo("/advice")).willReturn(okJson(
                "{\"message\":\"Limit outdoor exertion. General guidance only, not medical advice.\",\"action_codes\":[],\"source\":\"TEMPLATE\"}")));
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(503)));
    }

    /** A genuine-looking hourly observation at T0 + h hours, then the clock 5 min after it. */
    private void observe(int hour, int aqi, String category) {
        Instant ts = T0.plus(Duration.ofHours(hour));
        jdbc.update("""
                INSERT INTO station_aqi (station_id, ts, aqi, category, dominant_pollutant, pollutant_count)
                VALUES ('ito', ?, ?, ?, 'PM25', 5)
                """, Timestamp.from(ts), aqi, category);
        clock.set(ts.plus(Duration.ofMinutes(5)));
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT * FROM alerts WHERE kind LIKE 'AQI_%' ORDER BY id");
    }

    private List<String> kinds() {
        return events().stream().map(r -> r.get("kind") + ":" + r.get("status")).toList();
    }

    @Test
    void belowThenCrossingThenDeteriorationWithHistoryAndHtmlOnTheWire() {
        observe(0, 90, "SATISFACTORY");
        evaluator.evaluateAll();
        assertThat(events()).isEmpty();                                   // silent baseline below the level

        observe(1, 160, "MODERATE");
        evaluator.evaluateAll();
        evaluator.evaluateAll();                                          // same observation: nothing new
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING");
        Map<String, Object> crossed = events().getFirst();
        assertThat(crossed).containsEntry("peak_aqi", 160).containsEntry("previous_aqi", 90).containsEntry("aqi_change", 70)
                .containsEntry("category", "MODERATE").containsEntry("station_id", "ito");
        assertThat(((Timestamp) crossed.get("observed_at")).toInstant()).isEqualTo(T0.plus(Duration.ofHours(1)));

        dispatcher.dispatchDue();
        String message = (String) crossed.get("message");
        assertThat(TelegramHtml.isValid(message)).isTrue();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND))
                .withRequestBody(matchingJsonPath("$.parse_mode", equalTo("HTML")))
                .withRequestBody(matchingJsonPath("$.text", equalTo(message))));      // history == what was sent

        observe(2, 190, "MODERATE");                                      // +30 vs. what the user was told
        evaluator.evaluateAll();
        Map<String, Object> worse = events().getLast();
        assertThat(worse).containsEntry("kind", "AQI_DETERIORATION").containsEntry("previous_aqi", 160)
                .containsEntry("aqi_change", 30);
        assertThat((String) worse.get("message")).contains("Air Quality Has Deteriorated").contains("+30 points");
    }

    @Test
    void categoryWorseningSmallStepsAndRecoveries() {
        observe(0, 190, "MODERATE");
        evaluator.evaluateAll();                                          // first reading already above: crossing
        observe(1, 205, "POOR");                                          // +15 but a worse category
        evaluator.evaluateAll();
        observe(2, 212, "POOR");                                          // +7: no event
        evaluator.evaluateAll();
        observe(3, 170, "MODERATE");                                      // better category
        evaluator.evaluateAll();
        observe(4, 95, "SATISFACTORY");                                   // below the level
        evaluator.evaluateAll();
        observe(5, 60, "SATISFACTORY");                                   // change below the level: silent
        evaluator.evaluateAll();
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING", "AQI_DETERIORATION:PENDING",
                "AQI_RECOVERY:PENDING", "AQI_RECOVERY:PENDING");
        assertThat((String) events().getLast().get("message")).contains("Air Quality Is Back Below Your Alert Level");
    }

    @Test
    void stabilityRemindersEveryEightHoursOnlyWhileAbove() {
        observe(0, 160, "MODERATE");
        evaluator.evaluateAll();                                          // crossing at 10:05 IST
        for (int h = 1; h <= 7; h++) {
            observe(h, 160 + (h % 3), "MODERATE");                        // stable
            evaluator.evaluateAll();
        }
        clock.set(T0.plus(Duration.ofHours(8)).plus(Duration.ofMinutes(4)));   // 7 h 59 min after the crossing
        evaluator.evaluateAll();
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING");

        observe(8, 162, "MODERATE");                                      // 8 h after the crossing
        evaluator.evaluateAll();
        evaluator.evaluateAll();                                          // restart / next tick: no duplicate
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING", "AQI_STABILITY_REMINDER:PENDING");
        assertThat((String) events().getLast().get("message")).contains("AQI REMINDER");

        for (int h = 9; h <= 16; h++) {
            observe(h, 161, "MODERATE");
            evaluator.evaluateAll();
        }
        assertThat(kinds()).filteredOn(k -> k.startsWith("AQI_STABILITY_REMINDER")).hasSize(2);   // 8 h after the first

        observe(17, 80, "SATISFACTORY");                                  // below: recovery, then no reminders
        evaluator.evaluateAll();
        for (int h = 18; h <= 30; h++) {
            observe(h, 82, "SATISFACTORY");
            evaluator.evaluateAll();
        }
        assertThat(kinds()).filteredOn(k -> k.startsWith("AQI_STABILITY_REMINDER")).hasSize(2);
        assertThat(kinds().getLast()).isEqualTo("AQI_RECOVERY:PENDING");
    }

    @Test
    void significantEventResetsTheReminderTimer() {
        observe(0, 160, "MODERATE");
        evaluator.evaluateAll();
        observe(6, 230, "POOR");                                          // deterioration 6 h in
        evaluator.evaluateAll();
        observe(9, 235, "POOR");                                          // 9 h after crossing, 3 h after the event
        evaluator.evaluateAll();
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING", "AQI_DETERIORATION:PENDING");
        observe(14, 236, "POOR");                                         // 8 h after the deterioration
        evaluator.evaluateAll();
        assertThat(kinds().getLast()).isEqualTo("AQI_STABILITY_REMINDER:PENDING");
    }

    @Test
    void twoSchedulerInstancesNeverDuplicateAnEvent() throws Exception {
        observe(0, 90, "SATISFACTORY");
        evaluator.evaluateAll();
        observe(1, 260, "POOR");
        CountDownLatch go = new CountDownLatch(1);
        Runnable run = () -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            evaluator.evaluateAll();
        };
        CompletableFuture<Void> a = CompletableFuture.runAsync(run);
        CompletableFuture<Void> b = CompletableFuture.runAsync(run);
        go.countDown();
        CompletableFuture.allOf(a, b).get();
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING");
    }

    @Test
    void quietHoursSuppressRoutineEventsAndTheChangeIsAnnouncedAfterwards() {
        observe(0, 120, "MODERATE");
        evaluator.evaluateAll();
        jdbc.update("UPDATE profiles SET quiet_start = '09:00', quiet_end = '12:00' WHERE user_id = 1");   // 10:00-11:05 IST
        observe(1, 240, "POOR");
        evaluator.evaluateAll();
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:PENDING", "AQI_DETERIORATION:SUPPRESSED");
        assertThat((String) events().getLast().get("last_error")).contains("quiet hours");
        dispatcher.dispatchDue();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND)));          // only the earlier crossing

        observe(2, 245, "POOR");                                          // 12:05 IST: quiet hours over
        evaluator.evaluateAll();
        Map<String, Object> after = events().getLast();
        assertThat(after).containsEntry("kind", "AQI_DETERIORATION").containsEntry("status", "PENDING")
                .containsEntry("previous_aqi", 120);                      // measured from what the user was last told
    }

    @Test
    void criticalAlertBypassesQuietHoursOnlyWhenTheUserOptedIn() {
        observe(0, 350, "VERY_POOR");
        evaluator.evaluateAll();
        jdbc.update("UPDATE profiles SET quiet_start = '09:00', quiet_end = '23:00' WHERE user_id = 1");
        observe(1, 430, "SEVERE");
        evaluator.evaluateAll();
        assertThat(kinds().getLast()).isEqualTo("AQI_DETERIORATION:SUPPRESSED");           // not opted in

        jdbc.update("UPDATE profiles SET severe_overrides_quiet = TRUE WHERE user_id = 1");
        observe(2, 440, "SEVERE");
        evaluator.evaluateAll();
        assertThat(kinds().getLast()).isEqualTo("AQI_DETERIORATION:PENDING");             // critical + opted in
    }

    @Test
    void staleObservationIsNeverAnAqiEvent() {
        observe(0, 90, "SATISFACTORY");
        evaluator.evaluateAll();
        observe(1, 400, "SEVERE");
        clock.advance(Duration.ofHours(4));                               // the reading is now 4 h old
        evaluator.evaluateAll();
        assertThat(events()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE kind = 'STALE_NOTICE'", Integer.class))
                .isEqualTo(1);                                            // separate stale-data notice instead
    }

    @Test
    void disabledEventAlertsSendNothingAndTheBriefingIsIndependent() {
        jdbc.update("UPDATE profiles SET aqi_alerts_enabled = FALSE WHERE user_id = 1");
        observe(0, 300, "VERY_POOR");
        evaluator.evaluateAll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts", Integer.class)).isZero();

        clock.set(Instant.parse("2026-11-20T01:35:00Z"));                 // 07:05 IST: briefing still due
        jdbc.update("INSERT INTO station_aqi (station_id, ts, aqi, category, dominant_pollutant, pollutant_count) "
                + "VALUES ('ito', '2026-11-20T01:30:00Z', 150, 'MODERATE', 'PM25', 5)");
        briefings.queueDue();
        assertThat(jdbc.queryForList("SELECT kind FROM alerts", String.class)).containsExactly("MORNING_BRIEFING");

        jdbc.update("UPDATE profiles SET aqi_alerts_enabled = TRUE, morning_briefing_enabled = FALSE WHERE user_id = 1");
        clock.set(Instant.parse("2026-11-21T01:35:00Z"));
        briefings.queueDue();                                             // briefing off: none for day 2
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE kind = 'MORNING_BRIEFING'", Integer.class)).isEqualTo(1);
    }

    @Test
    void settingsPersistThroughTheProfileApiAndDisablingResetsTheMemory() {
        String token = register("settings@test.local");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        http.exchange("/api/v1/me/profile", HttpMethod.PUT, new HttpEntity<>(Map.of("healthProfile", "GENERAL",
                "stationId", "ito", "language", "en", "aqiAlertsEnabled", false, "morningBriefingEnabled", true,
                "morningBriefingTime", "06:45"), h), Map.class);
        Map<String, Object> view = http.exchange("/api/v1/me/profile", HttpMethod.GET, new HttpEntity<>(h),
                new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();
        assertThat(view).containsEntry("aqiAlertsEnabled", false).containsEntry("morningBriefingEnabled", true)
                .containsEntry("morningBriefingTime", "06:45:00");
        assertThat(jdbc.queryForObject("SELECT aqi_alerts_enabled FROM profiles p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = 'settings@test.local'", Boolean.class)).isFalse();
    }

    @Test
    void rateLimitedDeliveryWaitsForRetryAfterAndSendsOnce() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(429)
                .withBody("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":300}}")));
        observe(0, 210, "POOR");
        evaluator.evaluateAll();
        dispatcher.dispatchDue();
        Map<String, Object> pending = events().getFirst();
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(((Timestamp) pending.get("next_attempt_at")).toInstant()).isEqualTo(clock.instant().plusSeconds(300));

        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\":true}")));
        clock.advance(Duration.ofMinutes(6));
        evaluator.evaluateAll();                                          // must not create a second event
        dispatcher.dispatchDue();
        assertThat(kinds()).containsExactly("AQI_THRESHOLD_CROSSED:SENT");
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void historyApiShowsTypeAqiChangeObservationAndDelivery() {
        String token = register("history@test.local");
        long uid = jdbc.queryForObject("SELECT id FROM users WHERE email = 'history@test.local'", Long.class);
        jdbc.update("INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id, alert_min_category) "
                + "VALUES (?, 'GENERAL', 'ito', 'en', '777', 'MODERATE')", uid);
        observe(0, 90, "SATISFACTORY");
        evaluator.evaluateAll();
        observe(1, 180, "MODERATE");
        evaluator.evaluateAll();
        dispatcher.dispatchDue();

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        List<Map<String, Object>> history = http.exchange("/api/v1/me/alerts", HttpMethod.GET, new HttpEntity<>(h),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody();
        assertThat(history).hasSize(1);
        assertThat(history.getFirst()).containsEntry("kind", "AQI_THRESHOLD_CROSSED").containsEntry("status", "SENT")
                .containsEntry("peakAqi", 180).containsEntry("previousAqi", 90).containsEntry("aqiChange", 90)
                .containsEntry("category", "MODERATE").containsEntry("observedAt", T0.plus(Duration.ofHours(1)).toString())
                .containsKey("sentAt");
    }
}
