package com.saanscheck.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuietHoursTest {

    @ParameterizedTest(name = "{0} in [{1},{2}) -> {3}")
    @CsvSource({
            // same-day window
            "13:00, 12:00, 14:00, true",
            "12:00, 12:00, 14:00, true",
            "14:00, 12:00, 14:00, false",
            "11:59, 12:00, 14:00, false",
            // window wrapping midnight
            "23:30, 22:00, 07:00, true",
            "00:00, 22:00, 07:00, true",
            "06:59, 22:00, 07:00, true",
            "07:00, 22:00, 07:00, false",
            "12:00, 22:00, 07:00, false",
            // degenerate window = no quiet hours
            "12:00, 08:00, 08:00, false"})
    void quietHours(LocalTime now, LocalTime start, LocalTime end, boolean expected) {
        assertThat(QuietHours.isQuiet(now, start, end)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void noWindowConfigured() {
        assertThat(QuietHours.isQuiet(LocalTime.NOON, null, null)).isFalse();
    }
}
