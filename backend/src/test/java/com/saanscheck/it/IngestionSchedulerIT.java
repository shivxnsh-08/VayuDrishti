package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saanscheck.ingestion.IngestionScheduler;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The real Spring scheduler with scheduling ENABLED and a 2-second cron. Proves that the ingestion job is
 * registered and fires after startup, fires repeatedly, keeps firing after failed runs and succeeds once the
 * source recovers, and that the startup catch-up (trigger RECOVERY) runs when there is no recent success.
 *
 * <p>The context is closed after the class so the fast cron never runs during other integration tests.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "saanscheck.scheduling.enabled=true",
        "saanscheck.ingestion.cron=*/2 * * * * *",
        "saanscheck.ingestion.health.startup-delay=PT6S",
        "saanscheck.telegram.poll-updates=false",
        "saanscheck.alerts.enabled=false"})
class IngestionSchedulerIT extends AbstractIT {

    private static final String PATH = "/resource/test-resource";

    @Autowired(required = false)
    IngestionScheduler scheduler;

    private List<String> runs(String trigger) {
        return jdbc.queryForList("SELECT status FROM ingestion_runs WHERE trigger = ? ORDER BY id", String.class, trigger);
    }

    @Test
    void scheduledRunsFireRepeatedlyRecoverAfterFailuresAndStartupCatchUpRuns() throws Exception {
        assertThat(scheduler).as("scheduler bean is active when scheduling is enabled").isNotNull();

        // Source is down (permanent 403): every run fails, so the startup catch-up must not be skipped.
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(runs("SCHEDULED")).hasSizeGreaterThanOrEqualTo(2).containsOnly("FAILED");
            assertThat(runs("RECOVERY")).containsExactly("FAILED");     // ran once, ~6 s after startup
        });

        // The source recovers: the very next scheduled runs succeed without any restart or manual action.
        String fixture;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/datagov_page1.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(fixture)));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            List<String> scheduled = runs("SCHEDULED");
            assertThat(scheduled.getLast()).isEqualTo("SUCCESS");
            assertThat(scheduled.stream().filter("SUCCESS"::equals).count()).isGreaterThanOrEqualTo(2);
        });

        // (Overlap prevention is covered by IngestionReliabilityIT using a lock held on another connection;
        // here run timestamps come from the fixed test clock, so they cannot show ordering.)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_runs WHERE status = 'RUNNING'", Integer.class))
                .isLessThanOrEqualTo(1);                                  // at most the run in progress right now
        assertThat(runs("RECOVERY")).hasSize(1);                         // the catch-up is one-shot
    }
}
