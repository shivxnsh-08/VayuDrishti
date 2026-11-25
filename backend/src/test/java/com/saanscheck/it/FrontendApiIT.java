package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.stations.StationSummary;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Read-only endpoints the frontend needs: all stations with latest AQI, and model info. */
class FrontendApiIT extends AbstractIT {

    @Test
    void stationListReturnsActiveStationsWithLatestAqiAndStaleFlag() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        insertStation("anand", "Anand Vihar", 28.6468, 77.3160, true);
        insertStation("never", "No data yet", 28.60, 77.20, true);
        insertStation("closed", "Closed", 28.50, 77.10, false);
        insertAqi("ito", T0.minus(Duration.ofHours(1)), 150, "MODERATE");
        insertAqi("ito", T0, 180, "MODERATE");                          // latest wins
        insertAqi("anand", T0.minus(Duration.ofHours(5)), 320, "VERY_POOR"); // stale (> 3 h)

        StationSummary[] all = http.getForObject("/api/v1/stations", StationSummary[].class);

        assertThat(all).extracting(StationSummary::id).containsExactlyInAnyOrder("ito", "anand", "never");
        StationSummary ito = find(all, "ito");
        assertThat(ito.aqi()).isEqualTo(180);
        assertThat(ito.ts()).isEqualTo(T0);
        assertThat(ito.stale()).isFalse();
        assertThat(ito.latitude()).isEqualTo(28.6286);
        assertThat(find(all, "anand").stale()).isTrue();
        StationSummary never = find(all, "never");
        assertThat(never.aqi()).isNull();
        assertThat(never.stale()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelInfoIsProxiedAndDegradesWhenServiceIsDown() {
        WIREMOCK.stubFor(get(urlEqualTo("/model-info")).willReturn(okJson(
                "{\"model_loaded\": false, \"serving\": \"BASELINE_PERSISTENCE\"}")));
        Map<String, Object> ok = http.getForObject("/api/v1/model-info", Map.class);
        assertThat(ok).containsEntry("available", true);
        assertThat((Map<String, Object>) ok.get("info")).containsEntry("serving", "BASELINE_PERSISTENCE");

        WIREMOCK.stubFor(get(urlEqualTo("/model-info")).willReturn(aResponse().withStatus(503)));
        Map<String, Object> down = http.getForObject("/api/v1/model-info", Map.class);
        assertThat(down).containsEntry("available", false);
    }

    private static StationSummary find(StationSummary[] all, String id) {
        for (StationSummary s : all) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("missing " + id);
    }
}
