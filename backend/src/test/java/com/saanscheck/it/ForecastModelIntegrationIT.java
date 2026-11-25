package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.forecast.ForecastView;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Java <-> Python integration for trained models (the Python side is a WireMock stand-in that returns
 * the same JSON shape as forecast-service). Covers unknown stations, a model forecast passing through
 * intact (version, method, ranges, 24 points), storage of the batch, and service-side rejections.
 */
class ForecastModelIntegrationIT extends AbstractIT {

    @BeforeEach
    void seed() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        for (int h = 0; h < 30; h++) {
            insertAqi("ito", T0.minus(Duration.ofHours(h)), 150 + h, "MODERATE");
        }
    }

    private static String modelForecast(String version, boolean withRanges) {
        StringJoiner preds = new StringJoiner(",");
        for (int h = 1; h <= 24; h++) {
            Instant t = T0.plus(Duration.ofHours(h));
            preds.add(withRanges
                    ? "{\"target_ts\":\"%s\",\"predicted_aqi\":%d,\"lower\":%d,\"upper\":%d}".formatted(t, 160 + h, 140 + h, 185 + h)
                    : "{\"target_ts\":\"%s\",\"predicted_aqi\":%d,\"lower\":null,\"upper\":null}".formatted(t, 160 + h));
        }
        return """
                {"station_id":"ito","generated_at":"%s","origin_ts":"%s","model_version":"%s",
                 "method":"MODEL","predictions":[%s]}
                """.formatted(T0, T0, version, preds);
    }

    @Test
    void unknownStationIs404AndNeverReachesTheForecastService() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/stations/no-such-station/forecast", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/forecast")));
    }

    @Test
    void trainedModelForecastPassesThroughWithVersionMethodAndRanges() {
        String version = "random_forest-20261220T020000";
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(modelForecast(version, true))));

        ForecastView v = http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class);

        assertThat(v.status()).isEqualTo(ForecastView.Status.AVAILABLE);
        assertThat(v.method()).isEqualTo("MODEL");
        assertThat(v.modelVersion()).isEqualTo(version);
        assertThat(v.points()).hasSize(24);
        ForecastView.Point first = v.points().getFirst();
        assertThat(first.predictedAqi()).isEqualTo(161);
        assertThat(first.lower()).isEqualTo(141);
        assertThat(first.upper()).isEqualTo(186);
        assertThat(first.category()).isEqualTo("MODERATE");

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT count(*) AS n, min(model_version) AS ver, min(method) AS method FROM forecasts WHERE station_id = 'ito'");
        assertThat(stored).containsEntry("n", 24L).containsEntry("ver", version).containsEntry("method", "MODEL");

        // A second request within fresh-for reuses the stored batch instead of calling the service again.
        http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/forecast")));
    }

    @Test
    void missingRangesStayNullAndAreNotInvented() {
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(okJson(modelForecast("random_forest-x", false))));
        ForecastView v = http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class);
        assertThat(v.points()).allSatisfy(p -> {
            assertThat(p.lower()).isNull();
            assertThat(p.upper()).isNull();
        });
    }

    @Test
    void predictionFailure503IsReportedAsUnavailable() {
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"detail\":\"model prediction failed: RuntimeError\"}")));
        ForecastView v = http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class);
        assertThat(v.status()).isEqualTo(ForecastView.Status.UNAVAILABLE);
        assertThat(v.points()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM forecasts", Integer.class)).isZero();
    }

    @Test
    void invalidInputRejectedByServiceIsNotRetried() {
        WIREMOCK.stubFor(post(urlEqualTo("/forecast")).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"detail\":\"insufficient history: 3 valid hourly observations, need 24\"}")));
        assertThat(http.getForObject("/api/v1/stations/ito/forecast", ForecastView.class).status())
                .isEqualTo(ForecastView.Status.UNAVAILABLE);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/forecast")));
    }
}
