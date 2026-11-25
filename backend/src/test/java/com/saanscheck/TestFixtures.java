package com.saanscheck;

import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.aqi.AqiProperties;
import com.saanscheck.ingestion.IngestionProperties;
import com.saanscheck.profile.HealthProfile;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Hand-built configuration for plain unit tests (no Spring context). Mirrors aqi.yml / application.yml. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static AqiProperties aqiProps(boolean subIndices) {
        return new AqiProperties(
                List.of(new AqiProperties.Band(AqiCategory.GOOD, 0, 50),
                        new AqiProperties.Band(AqiCategory.SATISFACTORY, 51, 100),
                        new AqiProperties.Band(AqiCategory.MODERATE, 101, 200),
                        new AqiProperties.Band(AqiCategory.POOR, 201, 300),
                        new AqiProperties.Band(AqiCategory.VERY_POOR, 301, 400),
                        new AqiProperties.Band(AqiCategory.SEVERE, 401, 500)),
                subIndices, 500, 3, List.of("PM25", "PM10"),
                Map.of("PM25", 1000.0, "PM10", 2000.0, "NO2", 2000.0),
                Map.of("PM25", List.of(
                                new AqiProperties.Breakpoint(0, 30, 0, 50),
                                new AqiProperties.Breakpoint(31, 60, 51, 100),
                                new AqiProperties.Breakpoint(61, 90, 101, 200),
                                new AqiProperties.Breakpoint(91, 120, 201, 300),
                                new AqiProperties.Breakpoint(121, 250, 301, 400),
                                new AqiProperties.Breakpoint(251, 380, 401, 500)),
                        "PM10", List.of(
                                new AqiProperties.Breakpoint(0, 50, 0, 50),
                                new AqiProperties.Breakpoint(51, 100, 51, 100),
                                new AqiProperties.Breakpoint(101, 250, 101, 200),
                                new AqiProperties.Breakpoint(251, 350, 201, 300),
                                new AqiProperties.Breakpoint(351, 430, 301, 400),
                                new AqiProperties.Breakpoint(431, 510, 401, 500)),
                        "NO2", List.of(
                                new AqiProperties.Breakpoint(0, 40, 0, 50),
                                new AqiProperties.Breakpoint(41, 80, 51, 100),
                                new AqiProperties.Breakpoint(81, 180, 101, 200))));
    }

    public static AlertProperties alertProps() {
        return new AlertProperties(true, "Asia/Kolkata", Duration.ofMinutes(10), Duration.ofSeconds(30),
                24, 6, Duration.ofHours(6), Duration.ofHours(3), 5, Duration.ofMinutes(1), Duration.ofMinutes(5), 25,
                Map.of(HealthProfile.GENERAL, AqiCategory.POOR, HealthProfile.ASTHMA, AqiCategory.MODERATE,
                        HealthProfile.CHILD, AqiCategory.MODERATE, HealthProfile.ELDERLY, AqiCategory.MODERATE,
                        HealthProfile.EXERCISER, AqiCategory.MODERATE));
    }

    public static IngestionProperties ingestionProps(String baseUrl) {
        return new IngestionProperties(true, baseUrl, "test-resource", "test-key", "0 5 * * * *", 500, 5,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2), Map.of(),
                "Asia/Kolkata", "dd-MM-yyyy HH:mm:ss", 6,
                new IngestionProperties.Fields("station", "city", "state", "latitude", "longitude",
                        "pollutant_id", "avg_value", "last_update"),
                Map.of("OZONE", "O3", "PM2.5", "PM25"), null, 50.0);
    }
}
