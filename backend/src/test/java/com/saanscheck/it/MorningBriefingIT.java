package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.alerts.AlertDispatcher;
import com.saanscheck.alerts.AlertRepository;
import com.saanscheck.briefing.MorningBriefingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Morning briefing end to end against real PostgreSQL with MOCKED Telegram, forecast and advice services
 * (WireMock; no real Telegram message is sent). Times: the default delivery time 07:00 Asia/Kolkata = 01:30 UTC.
 */
class MorningBriefingIT extends AbstractIT {

    private static final String SEND = "/botTEST-TOKEN/sendMessage";
    private static final Instant DAY1_0700_IST = Instant.parse("2026-11-20T01:30:00Z");

    @Autowired
    MorningBriefingService briefings;
    @Autowired
    AlertDispatcher dispatcher;
    @Autowired
    AlertRepository alertRepo;

    private String token;
    private long userId;

    @BeforeEach
    void seed() throws Exception {
        insertStation("ito", "ITO, Delhi - CPCB", 28.6286, 77.2410, true);
        // 25 genuine hourly observations up to 06:00 IST (00:30 UTC) on day 1, AQI 180 (Moderate).
        for (int h = 0; h <= 24; h++) {
            insertAqi("ito", Instant.parse("2026-11-20T00:30:00Z").minus(Duration.ofHours(h)), 180, "MODERATE");
        }
        token = register("briefing@test.local");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'briefing@test.local'", Long.class);
        saveProfile(Map.of("healthProfile", "ASTHMA", "stationId", "ito", "language", "en"));
        jdbc.update("UPDATE profiles SET telegram_chat_id = '555' WHERE user_id = ?", userId);

        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\":true}")));
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(503)));
        WIREMOCK.stubFor(post(urlEqualTo("/advice")).willReturn(okJson(
                Files.readString(Path.of("..", "contracts", "advice_response.example.json")))));
    }

    private void saveProfile(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        http.exchange("/api/v1/me/profile", HttpMethod.PUT, new HttpEntity<>(body, h), Map.class);
    }

    private List<Map<String, Object>> briefingRows() {
        return jdbc.queryForList("SELECT * FROM alerts WHERE kind = 'MORNING_BRIEFING' ORDER BY id");
    }

    @Test
    void nothingBeforeTheDeliveryTime() {
        clock.set(DAY1_0700_IST.minus(Duration.ofMinutes(1)));
        assertThat(briefings.queueDue()).isZero();
        assertThat(briefingRows()).isEmpty();
    }

    @Test
    void dueAtSevenIstWithGenuineDataAndTheExactTextIsWhatTelegramReceives() {
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));        // 07:05 IST
        assertThat(briefings.queueDue()).isEqualTo(1);

        Map<String, Object> row = briefingRows().getFirst();
        String message = (String) row.get("message");
        assertThat(((Timestamp) row.get("scheduled_for")).toInstant()).isEqualTo(DAY1_0700_IST);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("station_id", "ito").containsEntry("peak_aqi", 180);
        assertThat(message)
                .startsWith("🌅 <b>Good Morning | VayuDrishti</b>\n<i>Friday 20 November</i>")
                .contains("📍 <b>Your monitoring station</b>\nITO, Delhi - CPCB\nDelhi")
                .contains("AQI: <b>180</b>\n🟠 Category: Moderate\nMain pollutant: PM2.5")
                .contains("🕒 Observed: 20 Nov 06:00 IST\n🕐 Data age: 1 h 5 min ago")
                .contains("<b>Health guidance for you (asthma)</b>")
                .contains("🔮 <b>AQI Forecast</b>\nToday's forecast: not available")
                .contains("<a href=\"http://localhost:5173/forecast?station=ito\">Open Your Dashboard</a>")
                .doesNotContain("STALE");
        assertThat(com.saanscheck.telegram.TelegramHtml.isValid(message)).isTrue();
        assertThat(row.get("context").toString()).contains("\"aqi\": 180").contains("\"stale\": false");

        dispatcher.dispatchDue();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(SEND))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("555")))
                .withRequestBody(matchingJsonPath("$.text", equalTo(message))));       // history == what was sent
        Map<String, Object> sent = briefingRows().getFirst();
        assertThat(sent).containsEntry("status", "SENT");
        assertThat(sent.get("sent_at")).isNotNull();
    }

    @Test
    void exactlyOnePerUserPerLocalDayAcrossTicksRestartsAndSettingChanges() {
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(1)));
        briefings.queueDue();
        briefings.queueDue();                                       // next scheduler tick
        clock.advance(Duration.ofMinutes(30));
        briefings.queueDue();                                       // e.g. after a backend restart
        saveProfile(Map.of("healthProfile", "ASTHMA", "stationId", "ito", "language", "en",
                "morningBriefingTime", "08:00"));                  // moved later after today's was queued
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(31 + 60)));
        briefings.queueDue();
        assertThat(briefingRows()).hasSize(1);

        clock.set(Instant.parse("2026-11-21T02:31:00Z"));          // next day 08:01 IST (new time)
        briefings.queueDue();
        assertThat(briefingRows()).hasSize(2);
        assertThat(briefingRows()).extracting(r -> r.get("dedupe_key"))
                .containsExactly("brief:" + userId + ":2026-11-20", "brief:" + userId + ":2026-11-21");
    }

    @Test
    void missedBriefingIsSentWithinTheCatchUpWindowButNotLater() {
        clock.set(DAY1_0700_IST.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(1)));   // 10:01 IST: too late
        assertThat(briefings.queueDue()).isZero();
        clock.set(DAY1_0700_IST.plus(Duration.ofHours(2)));                             // 09:00 IST: catch-up
        assertThat(briefings.queueDue()).isEqualTo(1);
    }

    @Test
    void disabledBriefingIsNeverQueuedAndSettingsAreReturned() {
        saveProfile(Map.of("healthProfile", "ASTHMA", "stationId", "ito", "language", "en", "morningBriefingEnabled", false));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        Map<String, Object> view = http.exchange("/api/v1/me/profile", HttpMethod.GET, new HttpEntity<>(h),
                new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();
        assertThat(view).containsEntry("morningBriefingEnabled", false).containsEntry("morningBriefingTime", "07:00:00");

        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        assertThat(briefings.queueDue()).isZero();
    }

    @Test
    void unlinkedOrStationlessUsersGetNothing() {
        jdbc.update("UPDATE profiles SET telegram_chat_id = NULL WHERE user_id = ?", userId);
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        assertThat(briefings.queueDue()).isZero();
    }

    @Test
    void staleReadingIsLabelledStaleAndGetsNoAdvice() {
        jdbc.update("DELETE FROM station_aqi WHERE station_id = 'ito' AND ts > ?", Timestamp.from(Instant.parse("2026-11-19T19:30:00Z")));
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));       // latest reading 01:00 IST = 6 h 5 min old
        briefings.queueDue();
        String message = (String) briefingRows().getFirst().get("message");
        assertThat(message).contains("Observed: 20 Nov 01:00 IST\n🕐 Data age: 6 h 5 min ago")
                .contains("STALE - it does not show the current air quality")
                .doesNotContain("Health guidance for you");
        assertThat(com.saanscheck.telegram.TelegramHtml.isValid(message)).isTrue();
        assertThat(briefingRows().getFirst().get("context").toString()).contains("\"stale\": true");
    }

    @Test
    void stationWithoutAnyReadingGetsAnHonestNoDataMessage() {
        insertStation("empty", "Empty Station", 28.7, 77.1, true);
        saveProfile(Map.of("healthProfile", "GENERAL", "stationId", "empty", "language", "en"));
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        briefings.queueDue();
        Map<String, Object> row = briefingRows().getFirst();
        assertThat((String) row.get("message")).contains("No valid AQI reading is available").doesNotContain("Latest AQI");
        assertThat(row.get("peak_aqi")).isNull();
    }

    @Test
    void availableForecastIsLabelledAsABaselineNotAnMlModel() {
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson("""
                {"station_id":"ito","generated_at":"2026-11-20T01:30:00Z","origin_ts":"2026-11-20T00:30:00Z",
                 "model_version":"baseline","method":"BASELINE_PERSISTENCE","predictions":[
                 {"target_ts":"2026-11-20T03:30:00Z","predicted_aqi":180,"lower":170,"upper":190},
                 {"target_ts":"2026-11-20T09:30:00Z","predicted_aqi":210,"lower":190,"upper":230},
                 {"target_ts":"2026-11-20T20:30:00Z","predicted_aqi":260,"lower":230,"upper":290}]}""")));
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        briefings.queueDue();
        String message = (String) briefingRows().getFirst().get("message");
        // 20:30 UTC is 02:00 IST on 21 Nov - tomorrow, so it is not part of "today".
        assertThat(message).contains("Today's forecast (baseline estimate, not an ML model): up to <b>210</b> (poor) around 15:00 IST, lowest 180.")
                .doesNotContain("ML model baseline");
    }

    @Test
    void telegramFailureIsRetriedOnceDeliveredAndNeverDuplicated() {
        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(502)));
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        briefings.queueDue();
        dispatcher.dispatchDue();
        assertThat(briefingRows().getFirst()).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((String) briefingRows().getFirst().get("last_error")).contains("502");

        WIREMOCK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"ok\":true}")));
        clock.advance(Duration.ofMinutes(5));
        briefings.queueDue();                                       // must not create a second one
        dispatcher.dispatchDue();
        assertThat(briefingRows()).hasSize(1);
        assertThat(briefingRows().getFirst()).containsEntry("status", "SENT").containsEntry("attempts", 2);
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void undeliverableBriefingExpiresInsteadOfArrivingInTheAfternoon() {
        WIREMOCK.stubFor(post(urlPathEqualTo(SEND)).willReturn(aResponse().withStatus(502)));
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        briefings.queueDue();
        clock.set(DAY1_0700_IST.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(1)));
        briefings.queueDue();                                       // expiry runs on every tick
        assertThat(briefingRows().getFirst()).containsEntry("status", "FAILED");
        assertThat((String) briefingRows().getFirst().get("last_error")).contains("expired");
        int calls = WIREMOCK.findAll(postRequestedFor(urlEqualTo(SEND))).size();
        dispatcher.dispatchDue();
        assertThat(WIREMOCK.findAll(postRequestedFor(urlEqualTo(SEND)))).hasSize(calls);   // nothing sent late
    }

    @Test
    void briefingsNeverCountAsAlertsForCooldown() {
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        briefings.queueDue();
        dispatcher.dispatchDue();
        assertThat(alertRepo.lastActive(userId, "ito")).isEmpty();   // alert cooldown/escalation unaffected
    }

    @Test
    void historyShowsTheBriefingWithScheduledAndSentTimes() {
        clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
        briefings.queueDue();
        dispatcher.dispatchDue();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        List<Map<String, Object>> history = http.exchange("/api/v1/me/alerts", HttpMethod.GET, new HttpEntity<>(h),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody();
        assertThat(history).hasSize(1);
        assertThat(history.getFirst()).containsEntry("kind", "MORNING_BRIEFING").containsEntry("status", "SENT")
                .containsEntry("scheduledFor", "2026-11-20T01:30:00Z").containsKey("sentAt");
        assertThat((String) history.getFirst().get("message")).startsWith("🌅 <b>Good Morning | VayuDrishti</b>");
    }

    @Test
    void scheduleDoesNotDependOnTheServerTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            clock.set(DAY1_0700_IST.plus(Duration.ofMinutes(5)));
            briefings.queueDue();
            assertThat(((Timestamp) briefingRows().getFirst().get("scheduled_for")).toInstant()).isEqualTo(DAY1_0700_IST);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
