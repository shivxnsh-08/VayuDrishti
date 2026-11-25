package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.ingestion.DataQualityService;
import com.saanscheck.stations.StationAqiRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Missing-hour detection on real PostgreSQL. */
class DataQualityIT extends AbstractIT {

    @Autowired
    StationAqiRepository stationAqi;
    @Autowired
    DataQualityService quality;

    @Test
    void countsHoursWithoutAqiFromEachStationsFirstObservation() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        insertStation("new", "New station", 28.60, 77.20, true);
        insertStation("closed", "Closed", 28.50, 77.10, false);
        // ito: T0-3h, T0-2h, (gap at T0-1h), T0
        insertAqi("ito", T0.minus(Duration.ofHours(3)), 150, "MODERATE");
        insertAqi("ito", T0.minus(Duration.ofHours(2)), 150, "MODERATE");
        insertAqi("ito", T0, 150, "MODERATE");
        // new station first seen at T0: earlier slots are not counted against it
        insertAqi("new", T0, 120, "MODERATE");
        // inactive stations are ignored
        insertAqi("closed", T0.minus(Duration.ofHours(5)), 100, "SATISFACTORY");

        var missing = stationAqi.missingHours(24, 10);

        assertThat(missing).containsExactly(new StationAqiRepository.MissingHours("ito", 1));

        DataQualityService.Summary s = quality.summary(Duration.ofHours(24));
        assertThat(s.missingAqiHours()).isEqualTo(1);
        assertThat(s.topMissingHours()).hasSize(1);
    }

    @Test
    void noDataMeansNoMissingHours() {
        assertThat(stationAqi.missingHours(24, 10)).isEmpty();
    }
}
