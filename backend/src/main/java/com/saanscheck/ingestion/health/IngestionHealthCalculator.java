package com.saanscheck.ingestion.health;

import com.saanscheck.ingestion.ErrorCategory;
import com.saanscheck.ingestion.IngestionRun;
import com.saanscheck.ingestion.health.IngestionHealth.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure health evaluation (no I/O), so every rule is unit-tested. Precedence, highest first:
 * UNKNOWN (no runs) > SOURCE_UNAVAILABLE / FAILED (consecutive failures) > STALE (old data)
 * > DEGRADED (delayed source, partial/failed last run, overdue schedule, few fresh stations) > HEALTHY.
 * All times are compared as UTC instants.
 */
public final class IngestionHealthCalculator {

    private IngestionHealthCalculator() {
    }

    /** Facts gathered from the database. {@code recentRuns} newest first. */
    public record Inputs(Instant now, List<IngestionRun> recentRuns, Instant lastSuccessfulFetchAt,
                         Instant latestObservationAt, int stationsActive, int stationsStale, Duration staleAfter) {
    }

    public static IngestionHealth evaluate(Inputs in, IngestionHealthProperties p) {
        List<String> reasons = new ArrayList<>();
        List<IngestionRun> finished = in.recentRuns().stream().filter(r -> !"RUNNING".equals(r.status())).toList();
        IngestionRun last = in.recentRuns().isEmpty() ? null : in.recentRuns().getFirst();
        IngestionRun lastSuccess = finished.stream().filter(IngestionRun::succeeded).findFirst().orElse(null);

        int failures = 0;
        boolean allSourceSide = true;
        String lastCategory = null;
        for (IngestionRun r : finished) {
            if (!r.failed()) {
                break;
            }
            failures++;
            if (lastCategory == null) {
                lastCategory = r.errorCategory();
            }
            allSourceSide &= isSourceSide(r.errorCategory());
        }
        Long obsAge = in.latestObservationAt() == null ? null
                : Duration.between(in.latestObservationAt(), in.now()).toMinutes();
        Long sinceRun = last == null ? null : Duration.between(last.startedAt(), in.now()).toMinutes();
        int fresh = Math.max(0, in.stationsActive() - in.stationsStale());

        Status status;
        if (last == null) {
            status = Status.UNKNOWN;
            reasons.add("no ingestion run has been recorded yet");
        } else if (failures >= p.sourceFailureThreshold() && allSourceSide) {
            status = Status.SOURCE_UNAVAILABLE;
            reasons.add(failures + " consecutive failed runs caused by the data source or network (last: " + lastCategory + ")");
        } else if (failures >= p.failureThreshold()) {
            status = Status.FAILED;
            reasons.add(failures + " consecutive failed runs (last: " + lastCategory + ")");
        } else if (in.latestObservationAt() == null) {
            status = Status.STALE;
            reasons.add("no observation has been stored yet");
        } else if (obsAge * 60 > in.staleAfter().toSeconds()) {
            status = Status.STALE;
            reasons.add("newest observation is " + humanMinutes(obsAge) + " old (stale after "
                    + humanMinutes(in.staleAfter().toMinutes()) + ")"
                    + (lastSuccess != null && lastSuccess.inserted() == 0 && lastSuccess.latestSourceTs() != null
                    && !lastSuccess.latestSourceTs().isAfter(in.latestObservationAt())
                    ? "; the source is still returning old observations" : ""));
        } else {
            status = Status.HEALTHY;
            if (obsAge > p.delayedAfter().toMinutes()) {
                status = Status.DEGRADED;
                reasons.add("source is delayed: newest observation is " + humanMinutes(obsAge) + " old");
            }
            if (failures > 0) {
                status = Status.DEGRADED;
                reasons.add(failures + " recent failed run(s) (last: " + lastCategory + ")");
            } else if ("PARTIAL".equals(last.status())) {
                status = Status.DEGRADED;
                reasons.add("last run was partial: " + last.error());
            }
            if (sinceRun != null && sinceRun > p.overdueAfter().toMinutes()) {
                status = Status.DEGRADED;
                reasons.add("no ingestion run for " + humanMinutes(sinceRun) + " (scheduler overdue or backend was down)");
            }
            if (in.stationsActive() > 0 && fresh < p.minFreshShare() * in.stationsActive()) {
                status = Status.DEGRADED;
                reasons.add("only " + fresh + " of " + in.stationsActive() + " stations have fresh data");
            }
            if (status == Status.HEALTHY) {
                reasons.add("ingestion is running and the newest observation is " + humanMinutes(obsAge) + " old");
            }
        }

        return new IngestionHealth(status, List.copyOf(reasons), in.now(),
                last == null ? null : last.startedAt(), last == null ? null : last.status(),
                last == null ? null : last.trigger(),
                lastSuccess == null ? null : lastSuccess.finishedAt(), in.lastSuccessfulFetchAt(),
                in.latestObservationAt(), obsAge, sinceRun, failures, lastCategory,
                in.stationsActive(), fresh, in.stationsStale(), in.staleAfter().toMinutes());
    }

    private static boolean isSourceSide(String category) {
        if (category == null) {
            return false;   // e.g. INTERRUPTED rows: not evidence that the source is down
        }
        try {
            return ErrorCategory.valueOf(category).sourceSide();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String humanMinutes(long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        if (minutes < 48 * 60) {
            return (minutes / 60) + " h " + (minutes % 60) + " min";
        }
        return (minutes / (24 * 60)) + " days";
    }
}
