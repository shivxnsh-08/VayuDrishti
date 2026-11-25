package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Nearest stations with freshness and forecast readiness (the Forecast page's automatic station selection). */
class ForecastCandidatesIT extends AbstractIT {

    private List<Map<String, Object>> candidates(String query) {
        return http.exchange("/api/v1/stations/nearest/forecast-candidates?" + query, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody();
    }

    @Test
    void ordersByDistanceAndExplainsWhyCloserStationsAreUnsuitable() {
        insertStation("near-stale", "Near Stale", 28.6100, 77.2100, true);    // closest
        insertStation("mid-short", "Mid Short History", 28.6300, 77.2300, true);
        insertStation("far-ready", "Far Ready", 28.7000, 77.3000, true);
        insertStation("inactive", "Inactive", 28.6101, 77.2101, false);
        insertAqi("near-stale", T0.minus(Duration.ofHours(10)), 150, "MODERATE");   // stale (> 3 h)
        for (int h = 0; h < 10; h++) {
            insertAqi("mid-short", T0.minus(Duration.ofHours(h)), 120, "MODERATE");
        }
        for (int h = 0; h < 30; h++) {
            insertAqi("far-ready", T0.minus(Duration.ofHours(h)), 210, "POOR");
        }

        List<Map<String, Object>> c = candidates("lat=28.6099&lon=77.2099&limit=10");

        assertThat(c).extracting(m -> m.get("id")).containsExactly("near-stale", "mid-short", "far-ready");   // inactive excluded
        assertThat(c.get(0)).containsEntry("stale", true).containsEntry("forecastReady", false);
        assertThat((String) c.get(0).get("reason")).contains("10 h old (stale)");
        assertThat(c.get(1)).containsEntry("stale", false).containsEntry("validHours", 10).containsEntry("forecastReady", false);
        assertThat((String) c.get(1).get("reason")).contains("only 10 of 24");
        assertThat(c.get(2)).containsEntry("forecastReady", true).containsEntry("validHours", 30)
                .containsEntry("requiredHours", 24).containsEntry("reason", null);
        assertThat((Double) c.get(2).get("distanceKm")).isBetween(12.0, 14.0);
        assertThat(c.get(0).toString()).doesNotContain("28.6099").doesNotContain("77.2099");   // location not echoed
    }

    @Test
    void stationWithoutObservationsIsReportedAsSuch() {
        insertStation("new", "New Station", 28.61, 77.21, true);
        Map<String, Object> only = candidates("lat=28.61&lon=77.21").getFirst();
        assertThat(only).containsEntry("forecastReady", false).containsEntry("stale", true).containsEntry("validHours", 0);
        assertThat((String) only.get("reason")).isEqualTo("no valid observations yet");
    }

    @Test
    void invalidCoordinatesAreRejected() {
        assertThat(http.exchange("/api/v1/stations/nearest/forecast-candidates?lat=95&lon=77", HttpMethod.GET, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
