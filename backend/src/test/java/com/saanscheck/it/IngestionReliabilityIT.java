package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.saanscheck.ingestion.IngestionRun;
import com.saanscheck.ingestion.IngestionService;
import com.saanscheck.ingestion.IngestionTrigger;
import com.saanscheck.ingestion.StationIds;
import com.saanscheck.ingestion.health.IngestionHealth;
import com.saanscheck.ingestion.health.IngestionHealthMonitor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Ingestion reliability against real PostgreSQL + a WireMock data.gov.in: retries, Retry-After, permanent
 * errors, network faults, zero new data, old source data, restart recovery, overlap prevention, stuck
 * detection across gaps, health status and deduplicated notifications.
 */
class IngestionReliabilityIT extends AbstractIT {

    private static final String PATH = "/resource/test-resource";

    @Autowired
    IngestionService ingestion;
    @Autowired
    IngestionHealthMonitor monitor;
    @Autowired
    DataSource dataSource;

    private static String fixture() throws Exception {
        try (InputStream in = IngestionReliabilityIT.class.getResourceAsStream("/fixtures/datagov_page1.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE ingestion_health_notifications RESTART IDENTITY");
    }

    private Map<String, Object> runRow(long id) {
        return jdbc.queryForMap("SELECT * FROM ingestion_runs WHERE id = ?", id);
    }

    // ---- retries ---------------------------------------------------------------------------------

    @Test
    void transient5xxIsRetriedAndRecorded() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("s").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(502)).willSetStateTo("b"));
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("s").whenScenarioStateIs("b")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("s").whenScenarioStateIs("ok").willReturn(okJson(fixture())));

        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();

        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.retryCount()).isEqualTo(2);
        Map<String, Object> row = runRow(run.id());
        assertThat(row).containsEntry("trigger", "SCHEDULED").containsEntry("retry_count", 2).containsEntry("http_status", 200);
        assertThat(row.get("duration_ms")).isNotNull();
        assertThat(((Timestamp) row.get("latest_source_ts")).toInstant()).isEqualTo(Instant.parse("2026-11-20T04:30:00Z"));
    }

    @Test
    void rateLimitHonoursRetryAfter() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("r").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("ok"));
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("r").whenScenarioStateIs("ok").willReturn(okJson(fixture())));

        long t = System.nanoTime();
        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        long elapsedMs = (System.nanoTime() - t) / 1_000_000;

        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.retryCount()).isEqualTo(1);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(950);   // waited for Retry-After, not the 10 ms test backoff
    }

    @Test
    void authErrorIsNotRetried() {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403).withBody("{\"message\":\"Invalid key\"}")));
        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCategory()).isEqualTo("AUTH");
        assertThat(run.httpStatus()).isEqualTo(403);
        assertThat(run.retryCount()).isZero();
        WIREMOCK.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(run.error()).doesNotContain("test-key");
    }

    @Test
    void persistentConnectionFailureIsRetriedThenRecorded() {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCategory()).isEqualTo("NETWORK");
        assertThat(run.retryCount()).isEqualTo(3);                       // 4 attempts in total
        // 4 bounded application attempts. HttpURLConnection itself transparently re-sends an idempotent GET once
        // when the connection is reset mid-response, so the wire may see up to 2 requests per attempt - still bounded.
        int requests = WIREMOCK.findAll(getRequestedFor(urlPathEqualTo(PATH))).size();
        assertThat(requests).isBetween(4, 8);
        assertThat(runRow(run.id())).containsEntry("error_category", "NETWORK");
    }

    @Test
    void laterPageFailureGivesPartialRunWithCategory() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture().replace("\"total\": 10", "\"total\": 600"))));
        // page size 500 > 10 records -> treated as last page; completeness check marks PARTIAL
        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        assertThat(run.status()).isEqualTo("PARTIAL");
        assertThat(run.error()).contains("incomplete snapshot");
    }

    // ---- data integrity --------------------------------------------------------------------------

    @Test
    void zeroNewObservationsIsSuccessAndRejectionsHaveReasons() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));
        IngestionRun first = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        IngestionRun second = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();

        assertThat(first.inserted()).isEqualTo(9);
        assertThat(first.rejectionReasons()).containsEntry("UNKNOWN_STATION_NO_COORDINATES", 1).containsEntry("INVALID_VALUE", 1);
        assertThat(second.status()).isEqualTo("SUCCESS");
        assertThat(second.inserted()).isZero();
        assertThat(second.duplicates()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM readings", Integer.class)).isEqualTo(9);
    }

    @Test
    void oldSourceDataIsReportedStaleNeverAsFresh() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));   // observations at T0
        clock.set(T0.plus(Duration.ofHours(19)));                                    // the source keeps serving them
        ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        IngestionRun repeat = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        assertThat(repeat.status()).isEqualTo("SUCCESS");

        @SuppressWarnings("unchecked")
        Map<String, Object> health = http.getForObject("/api/v1/ingestion/health", Map.class);
        assertThat(health).containsEntry("status", "STALE").containsEntry("stationsFresh", 0);
        assertThat(health.get("reasons").toString()).contains("still returning old observations");
        // timestamps were stored as the source reported them, not moved forward
        assertThat(jdbc.queryForObject("SELECT max(ts) FROM station_aqi", Timestamp.class).toInstant()).isEqualTo(T0);
    }

    @Test
    void futureDatedRecordIsRejectedAndDoesNotLookFresh() {
        // T0 = 20-11-2026 10:00 IST. One record is dated the next day (a source/time-zone error).
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"total": 2, "count": 2, "records": [
                 {"station":"Future test","last_update":"20-11-2026 10:00:00","latitude":"28.6","longitude":"77.2","pollutant_id":"PM10","avg_value":"150"},
                 {"station":"Future test","last_update":"21-11-2026 10:00:00","latitude":"28.6","longitude":"77.2","pollutant_id":"PM2.5","avg_value":"90"}]}
                """)));
        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();

        assertThat(run.inserted()).isEqualTo(1);
        assertThat(run.rejectionReasons()).containsEntry("FUTURE_TIMESTAMP", 1);
        assertThat(run.latestSourceTs()).isEqualTo(T0);
        assertThat(jdbc.queryForObject("SELECT max(ts) FROM readings", Timestamp.class).toInstant()).isEqualTo(T0);
    }

    @Test
    void stuckDetectionIgnoresGapsAndLowValues() {
        String st = StationIds.slug("Stuck test");
        insertStation(st, "Stuck test", 28.6, 77.2, true);
        // 5 previous identical PM10 readings, contiguous hourly, value 150 (> stuck-min-value 50)
        for (int h = 1; h <= 5; h++) {
            jdbc.update("INSERT INTO readings (station_id, pollutant, ts, value, quality) VALUES (?,'PM10',?,150,'OK')", st,
                    Timestamp.from(T0.minus(Duration.ofHours(h))));
            jdbc.update("INSERT INTO readings (station_id, pollutant, ts, value, quality) VALUES (?,'NH3',?,5,'OK')", st,
                    Timestamp.from(T0.minus(Duration.ofHours(h))));
        }
        // the same 5 readings but BEFORE a 19 h outage
        for (int h = 1; h <= 5; h++) {
            jdbc.update("INSERT INTO readings (station_id, pollutant, ts, value, quality) VALUES (?,'SO2',?,120,'OK')", st,
                    Timestamp.from(T0.minus(Duration.ofHours(19 + h))));
        }
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"total": 3, "count": 3, "records": [
                 {"station":"Stuck test","last_update":"20-11-2026 10:00:00","latitude":"28.6","longitude":"77.2","pollutant_id":"PM10","avg_value":"150"},
                 {"station":"Stuck test","last_update":"20-11-2026 10:00:00","latitude":"28.6","longitude":"77.2","pollutant_id":"NH3","avg_value":"5"},
                 {"station":"Stuck test","last_update":"20-11-2026 10:00:00","latitude":"28.6","longitude":"77.2","pollutant_id":"SO2","avg_value":"120"}]}
                """)));
        IngestionRun run = ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();

        assertThat(run.stuck()).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT max(quality) FILTER (WHERE pollutant='PM10') pm10, max(quality) FILTER (WHERE pollutant='NH3') nh3,
                       max(quality) FILTER (WHERE pollutant='SO2') so2 FROM readings WHERE station_id = ? AND ts = ?
                """, st, Timestamp.from(T0)))
                .containsEntry("pm10", "STUCK")   // genuinely constant for 6 consecutive hours
                .containsEntry("nh3", "OK")       // low 24-h-average sub-index: normal
                .containsEntry("so2", "OK");      // previous identical values are from before an outage gap
    }

    // ---- restart / overlap -----------------------------------------------------------------------

    @Test
    void runOrphanedByARestartIsMarkedInterrupted() throws Exception {
        long orphan = jdbc.queryForObject("""
                INSERT INTO ingestion_runs (started_at, status, trigger, fetched, inserted, rejected, missing, stuck, duplicates)
                VALUES (?, 'RUNNING', 'SCHEDULED', 0, 0, 0, 0, 0, 0) RETURNING id
                """, Long.class, Timestamp.from(T0.minus(Duration.ofMinutes(20))));
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));

        IngestionRun run = ingestion.runOnce(IngestionTrigger.RECOVERY).orElseThrow();

        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(runRow(orphan)).containsEntry("status", "INTERRUPTED");
        assertThat((String) runRow(orphan).get("error")).contains("process stopped during the run");
        assertThat(runRow(run.id())).containsEntry("trigger", "RECOVERY");
    }

    @Test
    void overlappingRunIsSkippedNotDuplicated() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));
        // Another instance holds the ingestion lock (same key as IngestionLock), on its own session.
        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement ps = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, 0x494E4745L);
                ps.execute();
            }
            try {
                // run on another thread: the pool may hand this thread's next connection to the same session,
                // and advisory locks are re-entrant within a session
                Optional<IngestionRun> skipped = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> ingestion.runOnce(IngestionTrigger.SCHEDULED)).get();
                assertThat(skipped).isEmpty();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_runs", Integer.class)).isZero();
                WIREMOCK.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
            } finally {
                try (PreparedStatement ps = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, 0x494E4745L);
                    ps.execute();   // pooled connections keep their session: release explicitly
                }
            }
        }
        // lock released: the next run proceeds
        assertThat(ingestion.runOnce(IngestionTrigger.SCHEDULED)).isPresent();
    }

    // ---- health + notifications ------------------------------------------------------------------

    @Test
    void healthIsPublicAndUnknownBeforeAnyRun() {
        @SuppressWarnings("unchecked")
        Map<String, Object> h = http.getForObject("/api/v1/ingestion/health", Map.class);
        assertThat(h).containsEntry("status", "UNKNOWN");
    }

    @Test
    void problemIsNotifiedOnceAfterPersistingThenRecoveryIsNotified() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));
        clock.set(T0.plus(Duration.ofHours(5)));                         // data 5 h old -> STALE
        ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();

        assertThat(monitor.check().status()).isEqualTo(IngestionHealth.Status.STALE);
        assertThat(notifications()).isZero();                            // not yet persisted for notify-after
        clock.advance(Duration.ofMinutes(11));
        monitor.check();
        assertThat(notifications()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(15));
        monitor.check();
        monitor.check();
        assertThat(notifications()).isEqualTo(1);                        // same problem: no repeated alert
        assertThat(jdbc.queryForObject("SELECT kind FROM ingestion_health_notifications", String.class)).isEqualTo("PROBLEM");

        clock.set(T0.plus(Duration.ofMinutes(30)));                      // fresh again
        ingestion.runOnce(IngestionTrigger.SCHEDULED).orElseThrow();
        IngestionHealth recovered = monitor.check();
        assertThat(recovered.problem()).isFalse();
        assertThat(jdbc.queryForList("SELECT kind FROM ingestion_health_notifications ORDER BY id", String.class))
                .containsExactly("PROBLEM", "RECOVERY");
    }

    private int notifications() {
        return jdbc.queryForObject("SELECT count(*) FROM ingestion_health_notifications", Integer.class);
    }
}
