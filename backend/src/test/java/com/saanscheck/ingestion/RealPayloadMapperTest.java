package com.saanscheck.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saanscheck.TestFixtures;
import com.saanscheck.aqi.AqiCalculator;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Mapper against a sanitised REAL data.gov.in response (see fixtures/README.md). */
class RealPayloadMapperTest {

    private final DataGovPayloadMapper mapper = new DataGovPayloadMapper(TestFixtures.ingestionProps("http://x"),
            new ReadingValidator(new AqiCalculator(TestFixtures.aqiProps(true))));

    private static JsonNode real() throws Exception {
        try (InputStream in = RealPayloadMapperTest.class.getResourceAsStream("/fixtures/datagov_real_20261122_sample.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void everyRealRecordParses() throws Exception {
        DataGovPayloadMapper.Page page = mapper.parsePage(real());
        assertThat(page.total()).isEqualTo(28).isEqualTo(page.records().size());

        List<ParsedReading> readings = page.records().stream()
                .map(mapper::toRaw)
                .map(r -> mapper.toReading(r, StationIds.slug(r.stationName())).orElseThrow())
                .toList();

        // 22-11-2026 18:00:00 IST
        assertThat(readings).extracting(ParsedReading::ts).containsOnly(Instant.parse("2026-11-22T12:30:00Z"));
        assertThat(readings).extracting(ParsedReading::pollutant).containsOnly("PM25", "PM10", "NO2", "SO2", "CO", "O3", "NH3");
        assertThat(readings).filteredOn(r -> r.quality() == ReadingQuality.MISSING).hasSize(2);
        assertThat(readings).filteredOn(r -> r.quality() == ReadingQuality.OK).hasSize(26);
        assertThat(readings).filteredOn(r -> r.quality() == ReadingQuality.INVALID).isEmpty();
    }

    @Test
    void stationNamesAreTrimmedAndCoordinatesParsed() throws Exception {
        Set<String> names = mapper.parsePage(real()).records().stream().map(mapper::toRaw)
                .peek(r -> assertThat(r.hasCoordinates()).isTrue())
                .map(RawRecord::stationName).collect(Collectors.toSet());
        assertThat(names).contains("Ambedkar Nagar, Nellore - APPCB").allMatch(n -> n.equals(n.strip()));
        assertThat(names.stream().map(StationIds::slug)).allMatch(s -> !s.endsWith("-") && !s.startsWith("-"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-22 18:00:00", "22-11-2026 18:00", "22/11/2026 18:00:00", "", "31-02-2026 10:00:00",
            "22-11-2026 25:00:00", "NA"})
    void malformedTimestampsAreRejected(String ts) {
        RawRecord raw = new RawRecord("ITO, Delhi - CPCB", "Delhi", "Delhi", 28.6, 77.2, "PM2.5", "100", ts,
                new ObjectMapper().createObjectNode());
        assertThat(mapper.toReading(raw, "ito")).isEmpty();
    }

    @Test
    void validTimestampStillParses() {
        RawRecord raw = new RawRecord("ITO", "Delhi", "Delhi", 28.6, 77.2, "PM2.5", "100", "29-02-2028 00:00:00",
                new ObjectMapper().createObjectNode());
        assertThat(mapper.toReading(raw, "ito")).get().extracting(ParsedReading::ts)
                .isEqualTo(Instant.parse("2028-02-28T18:30:00Z"));   // leap day, IST -> UTC
    }
}
