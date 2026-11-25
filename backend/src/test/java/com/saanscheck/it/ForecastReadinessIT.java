package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saanscheck.forecast.ForecastView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The forecast service is called only with enough fresh, valid, ascending hourly observations.
 * Live regression: stations had 3 hourly points and must get INSUFFICIENT_DATA, never a padded forecast.
 */
class ForecastReadinessIT extends AbstractIT {

    private static final String FORECAST_JSON = """
            {"station_id":"ito","generated_at":"2026-11-20T04:30:00Z","origin_ts":"2026-11-20T04:30:00Z",
             "model_version":"baseline","method":"BASELINE_PERSISTENCE",
             "predictions":[{"target_ts":"2026-11-20T05:30:00Z","predicted_aqi":150,"lower":140,"upper":160}]}
            """;

    @BeforeEach
    void seed() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(FORECAST_JSON)));
    }

    /** Inserts hourly points ending at T0, oldest first in time but inserted newest-first. */
    private void seedHours(int count) {
        for (int h = 0; h < count; h++) {
            insertAqi("ito", T0.minus(Duration.ofHours(h)), 140 + h, "MODERATE");
        }
    }

    private ForecastView forecast() {
        return http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class);
    }

    @Test
    void threeObservationsAreInsufficientAndServiceIsNotCalled() {
        seedHours(3);
        ForecastView v = forecast();
        assertThat(v.status()).isEqualTo(ForecastView.Status.INSUFFICIENT_DATA);
        assertThat(v.message()).contains("Only 3 hourly points");
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/forecast")));
    }

    @Test
    void twentyThreeIsStillInsufficientTwentyFourIsEnough() {
        seedHours(23);
        assertThat(forecast().status()).isEqualTo(ForecastView.Status.INSUFFICIENT_DATA);

        insertAqi("ito", T0.minus(Duration.ofHours(23)), 170, "MODERATE");
        assertThat(forecast().status()).isEqualTo(ForecastView.Status.AVAILABLE);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/forecast")));
    }

    @Test
    void nullAqiRowsDoNotCount() {
        seedHours(23);
        jdbc.update("INSERT INTO station_aqi (station_id, ts, aqi) VALUES ('ito', ?, NULL)",
                java.sql.Timestamp.from(T0.minus(Duration.ofHours(30))));
        assertThat(forecast().status()).isEqualTo(ForecastView.Status.INSUFFICIENT_DATA);
    }

    @Test
    void staleDataIsNotForecast() {
        seedHours(30);
        clock.advance(Duration.ofHours(4));
        ForecastView v = forecast();
        assertThat(v.status()).isEqualTo(ForecastView.Status.STALE_DATA);
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/forecast")));
    }

    @Test
    void requestCarriesAscendingDistinctHourlyReadings() throws Exception {
        seedHours(30);
        forecast();

        var requests = WIREMOCK.findAll(postRequestedFor(urlEqualTo("/forecast")));
        assertThat(requests).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(requests.getFirst().getBodyAsString());
        List<Instant> ts = new ArrayList<>();
        body.get("readings").forEach(r -> ts.add(Instant.parse(r.get("ts").asText())));
        assertThat(ts).hasSize(30).isSorted().doesNotHaveDuplicates();
        assertThat(ts.getLast()).isEqualTo(T0);
        assertThat(body.get("horizon_hours").asInt()).isEqualTo(24);
    }
}
