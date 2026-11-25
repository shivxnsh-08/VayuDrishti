package com.saanscheck.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StationIdsTest {

    @Test
    void slugifiesNames() {
        assertThat(StationIds.slug("Anand Vihar, Delhi - DPCC")).isEqualTo("anand-vihar-delhi-dpcc");
        assertThat(StationIds.slug("  Sector-62, Noida - IMD ")).isEqualTo("sector-62-noida-imd");
        assertThat(StationIds.slug("Café Station")).isEqualTo("cafe-station");
    }

    @Test
    void longNamesAreTruncatedButStayUnique() {
        String a = StationIds.slug("A".repeat(80) + " one");
        String b = StationIds.slug("A".repeat(80) + " two");
        assertThat(a).hasSizeLessThanOrEqualTo(64);
        assertThat(a).isNotEqualTo(b);
    }
}
