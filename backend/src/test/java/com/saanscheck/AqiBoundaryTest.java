package com.saanscheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.aqi.AqiCalculator;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.aqi.AqiProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boundary values for EVERY band and EVERY breakpoint row of the real aqi.yml (not a test copy),
 * so any edit to the AQI configuration is caught. These tests pin the configured values; they do not
 * prove those values match CPCB - see docs/aqi-verification.md for what has and has not been verified.
 */
@SpringBootTest(classes = ConfigBindingTest.Props.class)
class AqiBoundaryTest {

    @Autowired
    AqiProperties props;
    @Autowired
    AqiCalculator calc;

    @Test
    void everyBandEdgeMapsToItsCategory() {
        for (AqiProperties.Band b : props.bands()) {
            assertThat(calc.categorize(b.min())).as("min of %s", b.category()).isEqualTo(b.category());
            assertThat(calc.categorize(b.max())).as("max of %s", b.category()).isEqualTo(b.category());
        }
        assertThat(props.bands()).extracting(AqiProperties.Band::category).containsExactly(AqiCategory.values());
        // bands are contiguous: each min is the previous max + 1
        for (int i = 1; i < props.bands().size(); i++) {
            assertThat(props.bands().get(i).min()).isEqualTo(props.bands().get(i - 1).max() + 1);
        }
    }

    @Test
    void everyBreakpointRowInterpolatesToItsEnds() {
        List<String> checked = new ArrayList<>();
        for (Map.Entry<String, List<AqiProperties.Breakpoint>> e : props.breakpoints().entrySet()) {
            for (AqiProperties.Breakpoint bp : e.getValue()) {
                assertThat(calc.subIndex(e.getKey(), bp.cLow())).as("%s at %s", e.getKey(), bp.cLow()).hasValue(bp.iLow());
                assertThat(calc.subIndex(e.getKey(), bp.cHigh())).as("%s at %s", e.getKey(), bp.cHigh()).hasValue(bp.iHigh());
                checked.add(e.getKey());
            }
            // rows are ordered, contiguous in index space and monotonic in concentration
            List<AqiProperties.Breakpoint> rows = e.getValue();
            for (int i = 1; i < rows.size(); i++) {
                assertThat(rows.get(i).iLow()).isEqualTo(rows.get(i - 1).iHigh() + 1);
                assertThat(rows.get(i).cLow()).isGreaterThan(rows.get(i - 1).cHigh() - 1e-9);
            }
            // above the table: capped at the top sub-index
            assertThat(calc.subIndex(e.getKey(), rows.getLast().cHigh() * 10)).hasValue(rows.getLast().iHigh());
        }
        assertThat(checked).hasSize(7 * 6);
    }

    @Test
    void breakpointIndexEndsAlignWithCategoryBands() {
        for (List<AqiProperties.Breakpoint> rows : props.breakpoints().values()) {
            for (int i = 0; i < rows.size(); i++) {
                assertThat(rows.get(i).iLow()).isEqualTo(props.bands().get(i).min());
                assertThat(rows.get(i).iHigh()).isEqualTo(props.bands().get(i).max());
            }
        }
    }

    @Test
    void overallRuleOnRealConfig() {
        assertThat(calc.overall(Map.of("PM25", 100.0, "NO2", 50.0, "O3", 20.0))).isPresent();
        assertThat(calc.overall(Map.of("PM25", 100.0, "NO2", 50.0))).isEmpty();
        assertThat(calc.overall(Map.of("NO2", 100.0, "SO2", 50.0, "O3", 20.0))).isEmpty();
        // sub-index mode (as configured): feed values are used as-is, capped by plausibility elsewhere
        assertThat(props.valuesAreSubIndices()).isTrue();
        assertThat(calc.overall(Map.of("PM10", 401.0, "NO2", 50.0, "CO", 20.0)).orElseThrow().category())
                .isEqualTo(AqiCategory.SEVERE);
    }
}
