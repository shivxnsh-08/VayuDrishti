package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.ingestion.IngestionRun;
import com.saanscheck.ingestion.IngestionService;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression for the live pagination bug: data.gov.in returned overlapping offset pages when no
 * sort was given (58 of 3,906 records duplicated, 58 others never returned). Page size 2 here so
 * that pagination actually happens.
 */
@TestPropertySource(properties = "saanscheck.ingestion.page-size=2")
class IngestionPaginationIT extends AbstractIT {

    private static final String PATH = "/resource/test-resource";

    @Autowired
    IngestionService ingestion;

    private static String rec(String station, String pollutant, String value) {
        return """
                {"country":"India","state":"Delhi","city":"Delhi","station":"%s","last_update":"20-11-2026 10:00:00",
                 "latitude":"28.6286","longitude":"77.2410","pollutant_id":"%s","min_value":"%s","max_value":"%s","avg_value":"%s"}
                """.formatted(station, pollutant, value, value, value);
    }

    private static String page(int total, String... records) {
        return "{\"total\": %d, \"count\": %d, \"records\": [%s]}".formatted(total, records.length,
                List.of(records).stream().collect(Collectors.joining(",")));
    }

    private void stubPage(int offset, String body) {
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("offset", equalTo(String.valueOf(offset)))
                .willReturn(okJson(body)));
    }

    @Test
    void overlappingPagesAreCountedAndRunIsMarkedPartial() {
        // 4 records exist; the unsorted API returns ITO/PM10 on both pages and never returns ITO/PM2.5.
        stubPage(0, page(4, rec("ITO", "NO2", "60"), rec("ITO", "PM10", "160")));
        stubPage(2, page(4, rec("ITO", "PM10", "160"), rec("ITO", "SO2", "10")));

        IngestionRun run = ingestion.runOnce();

        assertThat(run.fetched()).isEqualTo(4);
        assertThat(run.payloadDuplicates()).isEqualTo(1);
        assertThat(run.expected()).isEqualTo(4);
        assertThat(run.status()).isEqualTo("PARTIAL");
        assertThat(run.error()).contains("3 unique records of 4");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM readings", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT payload_duplicates FROM ingestion_runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void sortedDisjointPagesGiveACompleteRun() {
        stubPage(0, page(4, rec("ITO", "NO2", "60"), rec("ITO", "PM10", "160")));
        stubPage(2, page(4, rec("ITO", "PM2.5", "180"), rec("ITO", "SO2", "10")));

        IngestionRun run = ingestion.runOnce();

        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.payloadDuplicates()).isZero();
        assertThat(run.inserted()).isEqualTo(4);
        WIREMOCK.verify(2, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("sort[station]", equalTo("asc"))
                .withQueryParam("sort[pollutant_id]", equalTo("asc")));
    }
}
