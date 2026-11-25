package com.saanscheck.ingestion.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.ingestion.IngestionRun;
import com.saanscheck.ingestion.health.IngestionHealth.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** Every health status and its precedence (Phase 8), plus timezone independence (Phase 10.16). */
class IngestionHealthCalculatorTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    static final IngestionHealthProperties P = new IngestionHealthProperties(null, null, 0, 0, 0, null, null);
    static final Duration STALE = Duration.ofHours(3);

    static IngestionRun run(int minutesAgo, String status, String category, int inserted, Instant latestSource) {
        Instant start = NOW.minus(Duration.ofMinutes(minutesAgo));
        return new IngestionRun(minutesAgo, start, start.plusSeconds(10), 4000, inserted, 0, 0, 0, 4000 - inserted, 0,
                4000, status, status.equals("FAILED") ? "error" : null, "SCHEDULED", 10_000L, 0, category, null,
                latestSource, Map.of());
    }

    static IngestionHealth eval(List<IngestionRun> runs, Instant latestObs, int active, int stale) {
        return IngestionHealthCalculator.evaluate(new IngestionHealthCalculator.Inputs(NOW, runs,
                NOW.minus(Duration.ofMinutes(5)), latestObs, active, stale, STALE), P);
    }

    static Instant minutesAgo(int m) {
        return NOW.minus(Duration.ofMinutes(m));
    }

    @Test
    void unknownWithoutRuns() {
        assertThat(eval(List.of(), null, 0, 0).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void healthyWhenRecentRunAndFreshData() {
        IngestionHealth h = eval(List.of(run(5, "SUCCESS", null, 0, minutesAgo(40))), minutesAgo(40), 578, 30);
        assertThat(h.status()).isEqualTo(Status.HEALTHY);
        assertThat(h.stationsFresh()).isEqualTo(548);
        assertThat(h.latestObservationAgeMinutes()).isEqualTo(40);
    }

    @Test
    void zeroNewObservationsIsNotAFailure() {
        var runs = List.of(run(5, "SUCCESS", null, 0, minutesAgo(50)), run(20, "SUCCESS", null, 0, minutesAgo(50)),
                run(35, "SUCCESS", null, 4000, minutesAgo(50)));
        assertThat(eval(runs, minutesAgo(50), 578, 10).status()).isEqualTo(Status.HEALTHY);
    }

    @Test
    void degradedWhenSourceDelayed() {
        IngestionHealth h = eval(List.of(run(5, "SUCCESS", null, 0, minutesAgo(150))), minutesAgo(150), 578, 10);
        assertThat(h.status()).isEqualTo(Status.DEGRADED);
        assertThat(h.reasons()).anyMatch(r -> r.contains("source is delayed"));
    }

    @Test
    void normalHourlyPublicationLagIsNotDegraded() {
        // Observed on 2026-11-24: just before the 09:30 UTC hour was published (10:20 run) the newest observation
        // was 108 min old. That is the feed's normal rhythm, not a delay.
        for (int age : new int[] {60, 108, 125}) {
            assertThat(eval(List.of(run(5, "SUCCESS", null, 0, minutesAgo(age))), minutesAgo(age), 578, 10).status())
                    .as("observation age %d min", age).isEqualTo(Status.HEALTHY);
        }
    }

    @Test
    void degradedOnPartialRunSingleFailureOverdueOrFewFreshStations() {
        assertThat(eval(List.of(run(5, "PARTIAL", "TIMEOUT", 100, minutesAgo(40))), minutesAgo(40), 578, 10).status())
                .isEqualTo(Status.DEGRADED);
        assertThat(eval(List.of(run(5, "FAILED", "TIMEOUT", 0, null), run(20, "SUCCESS", null, 0, minutesAgo(40))),
                minutesAgo(40), 578, 10).status()).isEqualTo(Status.DEGRADED);
        IngestionHealth overdue = eval(List.of(run(50, "SUCCESS", null, 0, minutesAgo(60))), minutesAgo(60), 578, 10);
        assertThat(overdue.status()).isEqualTo(Status.DEGRADED);
        assertThat(overdue.reasons()).anyMatch(r -> r.contains("scheduler overdue"));
        assertThat(eval(List.of(run(5, "SUCCESS", null, 0, minutesAgo(40))), minutesAgo(40), 578, 400).status())
                .isEqualTo(Status.DEGRADED);
    }

    @Test
    void staleAfterDowntimeOrWhenSourceKeepsReturningOldData() {
        // The real incident: newest observation 19 h old.
        IngestionHealth h = eval(List.of(run(3, "SUCCESS", null, 0, minutesAgo(19 * 60))), minutesAgo(19 * 60), 578, 578);
        assertThat(h.status()).isEqualTo(Status.STALE);
        assertThat(h.reasons().getFirst()).contains("19 h 0 min old").contains("still returning old observations");
        assertThat(eval(List.of(run(3, "SUCCESS", null, 0, null)), null, 0, 0).status()).isEqualTo(Status.STALE);
    }

    @Test
    void sourceUnavailableAfterTwoSourceSideFailures() {
        var runs = List.of(run(5, "FAILED", "TLS", 0, null), run(20, "FAILED", "TIMEOUT", 0, null),
                run(35, "SUCCESS", null, 0, minutesAgo(60)));
        IngestionHealth h = eval(runs, minutesAgo(60), 578, 0);
        assertThat(h.status()).isEqualTo(Status.SOURCE_UNAVAILABLE);
        assertThat(h.consecutiveFailures()).isEqualTo(2);
        assertThat(h.lastErrorCategory()).isEqualTo("TLS");
    }

    @Test
    void failedAfterThreeNonSourceFailures() {
        var runs = new ArrayList<>(List.of(run(5, "FAILED", "AUTH", 0, null), run(20, "FAILED", "AUTH", 0, null),
                run(35, "FAILED", "AUTH", 0, null)));
        assertThat(eval(runs, minutesAgo(60), 578, 0).status()).isEqualTo(Status.FAILED);
        // two AUTH failures are not yet FAILED and never SOURCE_UNAVAILABLE
        assertThat(eval(runs.subList(0, 2), minutesAgo(60), 578, 0).status()).isEqualTo(Status.DEGRADED);
    }

    @Test
    void interruptedRunsAreNotTakenAsSourceOutage() {
        var runs = List.of(run(5, "INTERRUPTED", null, 0, null), run(20, "INTERRUPTED", null, 0, null),
                run(35, "SUCCESS", null, 0, minutesAgo(50)));
        assertThat(eval(runs, minutesAgo(50), 578, 0).status()).isNotEqualTo(Status.SOURCE_UNAVAILABLE);
    }

    @Test
    void resultDoesNotDependOnTheJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            IngestionHealth utc = eval(List.of(run(5, "SUCCESS", null, 0, minutesAgo(40))), minutesAgo(40), 578, 0);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            IngestionHealth ist = eval(List.of(run(5, "SUCCESS", null, 0, minutesAgo(40))), minutesAgo(40), 578, 0);
            assertThat(ist).isEqualTo(utc);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
