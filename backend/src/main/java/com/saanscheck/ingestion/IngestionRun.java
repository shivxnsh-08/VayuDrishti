package com.saanscheck.ingestion;

import java.time.Instant;
import java.util.Map;

/**
 * One ingestion run as recorded in {@code ingestion_runs}.
 *
 * @param inserted       new observations stored by this run (0 is normal when the source has not published a new hour)
 * @param duplicates     observations the source returned that were already stored (repeated snapshot)
 * @param latestSourceTs newest observation timestamp in the source response (null if nothing was parsed)
 */
public record IngestionRun(long id, Instant startedAt, Instant finishedAt, int fetched, int inserted,
                           int rejected, int missing, int stuck, int duplicates, int payloadDuplicates,
                           Integer expected, String status, String error,
                           String trigger, Long durationMs, Integer retryCount, String errorCategory,
                           Integer httpStatus, Instant latestSourceTs, Map<String, Integer> rejectionReasons) {

    public boolean succeeded() {
        return "SUCCESS".equals(status) || "PARTIAL".equals(status);
    }

    public boolean failed() {
        return "FAILED".equals(status) || "INTERRUPTED".equals(status);
    }
}
