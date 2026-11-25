package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.saanscheck.ingestion.IngestionRun;
import com.saanscheck.ingestion.IngestionService;
import com.saanscheck.stations.CurrentAqiView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Parsing, validation, dedupe and error handling against a recorded payload (no live API). */
class IngestionIT extends AbstractIT {

    private static final String PATH = "/resource/test-resource";

    @Autowired
    IngestionService ingestion;

    private static String fixture() throws IOException {
        try (InputStream in = IngestionIT.class.getResourceAsStream("/fixtures/datagov_page1.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ingestsValidatesAndDerivesAqi() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));

        IngestionRun run = ingestion.runOnce();

        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.fetched()).isEqualTo(10);
        assertThat(run.inserted()).isEqualTo(9);   // unlocated station never stored
        assertThat(run.rejected()).isEqualTo(2);   // unlocated station + negative SO2
        assertThat(run.missing()).isEqualTo(1);    // CO "NA"
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stations", Integer.class)).isEqualTo(2);

        Map<String, Object> anand = jdbc.queryForMap(
                "SELECT aqi, category, dominant_pollutant FROM station_aqi WHERE station_id = 'anand-vihar-delhi-dpcc'");
        assertThat(anand).containsEntry("aqi", 345).containsEntry("category", "VERY_POOR")
                .containsEntry("dominant_pollutant", "PM25");
        Map<String, Object> ito = jdbc.queryForMap("SELECT aqi, category FROM station_aqi WHERE station_id = 'ito-delhi-cpcb'");
        assertThat(ito).containsEntry("aqi", 180).containsEntry("category", "MODERATE");

        // raw payload and cleaned value stored separately
        Map<String, Object> so2 = jdbc.queryForMap(
                "SELECT value, quality, raw->>'avg_value' AS raw_value FROM readings WHERE pollutant = 'SO2'");
        assertThat(so2.get("value")).isNull();
        assertThat(so2).containsEntry("quality", "INVALID").containsEntry("raw_value", "-5");

        WIREMOCK.verify(getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void realSnapshotIngestsCompletelyAndRepeatedSnapshotChangesNothing() throws Exception {
        String real;
        try (InputStream in = IngestionIT.class.getResourceAsStream("/fixtures/datagov_real_20261122_sample.json")) {
            real = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(real)));
        clock.set(java.time.Instant.parse("2026-11-22T13:05:00Z"));

        IngestionRun first = ingestion.runOnce();
        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(first.expected()).isEqualTo(28);
        assertThat(first.inserted()).isEqualTo(28);
        assertThat(first.missing()).isEqualTo(2);
        assertThat(first.payloadDuplicates()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stations", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT DISTINCT ts FROM station_aqi", java.sql.Timestamp.class))
                .extracting(java.sql.Timestamp::toInstant).containsExactly(java.time.Instant.parse("2026-11-22T12:30:00Z"));
        Integer aqiRows = jdbc.queryForObject("SELECT count(*) FROM station_aqi", Integer.class);

        // The feed has not moved on yet (the 15-minute poll re-reads the same hour).
        clock.advance(Duration.ofMinutes(15));
        IngestionRun repeat = ingestion.runOnce();
        assertThat(repeat.status()).isEqualTo("SUCCESS");
        assertThat(repeat.inserted()).isZero();
        assertThat(repeat.duplicates()).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM readings", Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM station_aqi", Integer.class)).isEqualTo(aqiRows);
    }

    @Test
    void reFetchingSameSnapshotIsDeduplicated() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));
        ingestion.runOnce();
        IngestionRun second = ingestion.runOnce();

        assertThat(second.inserted()).isZero();
        assertThat(second.duplicates()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM readings", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM station_aqi", Integer.class)).isEqualTo(2);
    }

    @Test
    void retriesTransientServerErrors() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(fixture())));

        assertThat(ingestion.runOnce().status()).isEqualTo("SUCCESS");
    }

    @Test
    void failedRunIsRecordedWithoutLeakingApiKey() {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403).withBody("{\"message\":\"Invalid key\"}")));

        IngestionRun run = ingestion.runOnce();

        assertThat(run.status()).isEqualTo("FAILED");
        String stored = jdbc.queryForObject("SELECT error FROM ingestion_runs WHERE id = ?", String.class, run.id());
        assertThat(stored).doesNotContain("test-key");
    }

    @Test
    void currentAqiIsServedWithStaleFlag() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture())));
        clock.set(T0.plus(Duration.ofMinutes(20)));
        ingestion.runOnce();

        CurrentAqiView fresh = http.getForObject("/api/v1/stations/ito-delhi-cpcb/aqi/current", CurrentAqiView.class);
        assertThat(fresh.aqi()).isEqualTo(180);
        assertThat(fresh.stale()).isFalse();

        clock.advance(Duration.ofHours(5));   // data source "down": no new runs
        CurrentAqiView stale = http.getForObject("/api/v1/stations/ito-delhi-cpcb/aqi/current", CurrentAqiView.class);
        assertThat(stale.aqi()).isEqualTo(180);   // last known value still served
        assertThat(stale.stale()).isTrue();
    }
}
