package com.saanscheck.aqi;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.TestFixtures;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AqiCalculatorTest {

    private final AqiCalculator subIndexCalc = new AqiCalculator(TestFixtures.aqiProps(true));
    private final AqiCalculator concCalc = new AqiCalculator(TestFixtures.aqiProps(false));

    @ParameterizedTest
    @CsvSource({"0,GOOD", "50,GOOD", "51,SATISFACTORY", "100,SATISFACTORY", "101,MODERATE", "200,MODERATE",
            "201,POOR", "300,POOR", "301,VERY_POOR", "400,VERY_POOR", "401,SEVERE", "500,SEVERE", "650,SEVERE"})
    void categorizesBandEdges(int aqi, AqiCategory expected) {
        assertThat(subIndexCalc.categorize(aqi)).isEqualTo(expected);
    }

    @Test
    void interpolatesConcentrationToSubIndex() {
        assertThat(concCalc.subIndex("PM25", 0)).hasValue(0);
        assertThat(concCalc.subIndex("PM25", 30)).hasValue(50);
        assertThat(concCalc.subIndex("PM25", 45.5)).hasValue(76);   // 51 + 49/29*14.5 = 75.5 -> 76
        assertThat(concCalc.subIndex("PM25", 30.5)).hasValue(50);   // gap between 30 and 31 belongs to lower row cap
        assertThat(concCalc.subIndex("PM25", 1000)).hasValue(500);  // capped
        assertThat(concCalc.subIndex("UNKNOWN", 10)).isEmpty();
    }

    @Test
    void overallIsMaxSubIndexWithDominantPollutant() {
        var result = subIndexCalc.overall(Map.of("PM25", 345.0, "PM10", 310.0, "NO2", 80.0));
        assertThat(result).isPresent();
        assertThat(result.get().aqi()).isEqualTo(345);
        assertThat(result.get().dominantPollutant()).isEqualTo("PM25");
        assertThat(result.get().category()).isEqualTo(AqiCategory.VERY_POOR);
        assertThat(result.get().pollutantCount()).isEqualTo(3);
    }

    @Test
    void requiresMinimumPollutantsIncludingParticulates() {
        assertThat(subIndexCalc.overall(Map.of("PM25", 100.0, "NO2", 50.0))).isEmpty();          // only 2
        assertThat(subIndexCalc.overall(Map.of("NO2", 100.0, "SO2", 50.0, "O3", 20.0))).isEmpty(); // no PM
        Map<String, Double> withNull = new HashMap<>(Map.of("PM10", 100.0, "NO2", 50.0));
        withNull.put("CO", null);
        assertThat(subIndexCalc.overall(withNull)).isEmpty();                                     // null not counted
    }
}
