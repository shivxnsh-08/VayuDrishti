package com.saanscheck.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saanscheck.TestFixtures;
import com.saanscheck.aqi.AqiCalculator;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Parses the recorded fixture: no live API involved. */
class DataGovPayloadMapperTest {

    private final DataGovPayloadMapper mapper = new DataGovPayloadMapper(TestFixtures.ingestionProps("http://x"),
            new ReadingValidator(new AqiCalculator(TestFixtures.aqiProps(true))));

    private JsonNode fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/datagov_page1.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void parsesEnvelopeAndRecords() throws Exception {
        DataGovPayloadMapper.Page page = mapper.parsePage(fixture());
        assertThat(page.total()).isEqualTo(10);
        assertThat(page.records()).hasSize(10);

        RawRecord first = mapper.toRaw(page.records().getFirst());
        assertThat(first.stationName()).isEqualTo("Anand Vihar, Delhi - DPCC");
        assertThat(first.latitude()).isEqualTo(28.6468);
        assertThat(first.hasCoordinates()).isTrue();

        RawRecord unlocated = mapper.toRaw(page.records().getLast());
        assertThat(unlocated.hasCoordinates()).isFalse();
    }

    @Test
    void convertsTimestampFromIstAndNormalizesPollutants() throws Exception {
        DataGovPayloadMapper.Page page = mapper.parsePage(fixture());
        ParsedReading pm25 = mapper.toReading(mapper.toRaw(page.records().get(0)), "anand-vihar-delhi-dpcc").orElseThrow();
        assertThat(pm25.pollutant()).isEqualTo("PM25");
        assertThat(pm25.ts()).isEqualTo(Instant.parse("2026-11-20T04:30:00Z"));  // 10:00 IST
        assertThat(pm25.value()).isEqualTo(345.0);
        assertThat(pm25.quality()).isEqualTo(ReadingQuality.OK);

        ParsedReading ozone = mapper.toReading(mapper.toRaw(page.records().get(3)), "s").orElseThrow();
        assertThat(ozone.pollutant()).isEqualTo("O3");

        ParsedReading co = mapper.toReading(mapper.toRaw(page.records().get(4)), "s").orElseThrow();
        assertThat(co.quality()).isEqualTo(ReadingQuality.MISSING);
        assertThat(co.value()).isNull();

        ParsedReading so2 = mapper.toReading(mapper.toRaw(page.records().get(7)), "s").orElseThrow();
        assertThat(so2.quality()).isEqualTo(ReadingQuality.INVALID);
    }

    @Test
    void rejectsPayloadWithoutRecords() {
        assertThatThrownBy(() -> mapper.parsePage(new ObjectMapper().readTree("{\"message\":\"Invalid key\"}")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redactsApiKeyFromMessages() {
        String msg = "I/O error on GET request for \"https://api.data.gov.in/resource/x?api-key=SECRET123&format=json\"";
        assertThat(DataGovClient.redact(msg)).doesNotContain("SECRET123").contains("api-key=***");
    }
}
