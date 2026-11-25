package com.saanscheck.ingestion.health;

import java.time.Instant;
import java.util.List;

/**
 * Ingestion health as served by {@code GET /api/v1/ingestion/health}.
 *
 * <ul>
 *   <li>HEALTHY: recent successful ingestion and the newest observation is fresh.</li>
 *   <li>DEGRADED: ingestion works but the source is delayed, the last run was partial or failed once,
 *       the schedule is overdue, or few stations are fresh.</li>
 *   <li>STALE: the newest stored observation is older than the stale limit (e.g. after downtime, or the
 *       source keeps returning old data).</li>
 *   <li>SOURCE_UNAVAILABLE: consecutive failures caused by the source / network (timeouts, TLS, 5xx, 429).</li>
 *   <li>FAILED: repeated failures of any other kind (configuration, API key, database).</li>
 *   <li>UNKNOWN: no ingestion run recorded yet.</li>
 * </ul>
 */
public record IngestionHealth(
        Status status,
        List<String> reasons,
        Instant evaluatedAt,
        Instant lastRunAt,
        String lastRunStatus,
        String lastRunTrigger,
        Instant lastSuccessfulRunAt,
        Instant lastSuccessfulFetchAt,
        Instant latestObservationAt,
        Long latestObservationAgeMinutes,
        Long minutesSinceLastRun,
        int consecutiveFailures,
        String lastErrorCategory,
        int stationsActive,
        int stationsFresh,
        int stationsStale,
        long staleAfterMinutes) {

    public enum Status { HEALTHY, DEGRADED, STALE, SOURCE_UNAVAILABLE, FAILED, UNKNOWN }

    public boolean problem() {
        return status == Status.STALE || status == Status.SOURCE_UNAVAILABLE || status == Status.FAILED;
    }
}
