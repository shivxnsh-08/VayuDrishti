package com.saanscheck.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.TestFixtures;
import com.saanscheck.aqi.AqiCalculator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadingValidatorTest {

    private final ReadingValidator validator = new ReadingValidator(new AqiCalculator(TestFixtures.aqiProps(true)));

    @Test
    void classifiesValues() {
        assertThat(validator.validate("PM25", "120").quality()).isEqualTo(ReadingQuality.OK);
        assertThat(validator.validate("PM25", "120").value()).isEqualTo(120.0);
        assertThat(validator.validate("PM25", "NA").quality()).isEqualTo(ReadingQuality.MISSING);
        assertThat(validator.validate("PM25", "").quality()).isEqualTo(ReadingQuality.MISSING);
        assertThat(validator.validate("PM25", null).quality()).isEqualTo(ReadingQuality.MISSING);
        assertThat(validator.validate("PM25", "abc").quality()).isEqualTo(ReadingQuality.INVALID);
        assertThat(validator.validate("PM25", "-1").quality()).isEqualTo(ReadingQuality.INVALID);
        assertThat(validator.validate("PM25", "999").quality()).isEqualTo(ReadingQuality.INVALID); // > 500 sub-index
        assertThat(validator.validate("PM25", "NaN").quality()).isEqualTo(ReadingQuality.INVALID);
    }

    @Test
    void detectsStuckSensor() {
        // stuckHours = 6 -> current + 5 previous identical values
        assertThat(ReadingValidator.isStuck(List.of(80.0, 80.0, 80.0, 80.0, 80.0), 80.0, 6)).isTrue();
        assertThat(ReadingValidator.isStuck(List.of(80.0, 80.0, 81.0, 80.0, 80.0), 80.0, 6)).isFalse();
        assertThat(ReadingValidator.isStuck(List.of(80.0, 80.0, 80.0), 80.0, 6)).isFalse(); // not enough history
        assertThat(ReadingValidator.isStuck(List.of(), 80.0, 6)).isFalse();
    }
}
