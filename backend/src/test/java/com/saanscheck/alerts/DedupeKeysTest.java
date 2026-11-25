package com.saanscheck.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.aqi.AqiCategory;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DedupeKeysTest {

    private static final Instant W = Instant.parse("2026-11-20T00:30:00Z");

    @Test
    void sameEpisodeGivesSameKey() {
        assertThat(DedupeKeys.alert(7, "ito", AqiCategory.POOR, W))
                .isEqualTo(DedupeKeys.alert(7, "ito", AqiCategory.POOR, W));
    }

    @Test
    void differentCategoryWindowUserOrStationGiveDifferentKeys() {
        String base = DedupeKeys.alert(7, "ito", AqiCategory.POOR, W);
        assertThat(DedupeKeys.alert(7, "ito", AqiCategory.VERY_POOR, W)).isNotEqualTo(base);
        assertThat(DedupeKeys.alert(7, "ito", AqiCategory.POOR, W.plusSeconds(6 * 3600))).isNotEqualTo(base);
        assertThat(DedupeKeys.alert(8, "ito", AqiCategory.POOR, W)).isNotEqualTo(base);
        assertThat(DedupeKeys.alert(7, "anand", AqiCategory.POOR, W)).isNotEqualTo(base);
    }

    @Test
    void keysFitColumn() {
        String longStation = "s".repeat(64);
        String key = DedupeKeys.suppressed(DedupeKeys.alert(Long.MAX_VALUE, longStation, AqiCategory.VERY_POOR, W),
                "quiet_hours");
        assertThat(key.length()).isLessThanOrEqualTo(200);
        assertThat(DedupeKeys.staleNotice(1, "ito", LocalDate.of(2026, 11, 20))).isEqualTo("u1:sito:STALE:2026-11-20");
    }
}
