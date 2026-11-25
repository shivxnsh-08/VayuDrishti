package com.saanscheck.ingestion;

/** Mutable counters for one run (single-threaded use inside {@link IngestionService}). */
public class IngestionStats {
    int fetched;
    int inserted;
    int rejected;
    int missing;
    int stuck;
    int duplicates;
    int stationHoursComputed;
    int expected = -1;
    int payloadDuplicates;
    int retries;
    Integer httpStatus;
    ErrorCategory errorCategory;
    java.time.Instant latestSourceTs;
    final java.util.Map<String, Integer> rejectionReasons = new java.util.TreeMap<>();

    /** Counts a rejected record and why it was rejected. */
    void reject(String reason) {
        rejected++;
        rejectionReasons.merge(reason, 1, Integer::sum);
    }

    void observe(java.time.Instant sourceTs) {
        if (sourceTs != null && (latestSourceTs == null || sourceTs.isAfter(latestSourceTs))) {
            latestSourceTs = sourceTs;
        }
    }

    public java.util.Map<String, Integer> rejectionReasons() {
        return rejectionReasons;
    }

    public int fetched() {
        return fetched;
    }

    public int inserted() {
        return inserted;
    }

    public int rejected() {
        return rejected;
    }

    public int missing() {
        return missing;
    }

    public int stuck() {
        return stuck;
    }

    public int duplicates() {
        return duplicates;
    }

    public int expected() {
        return expected;
    }

    public int payloadDuplicates() {
        return payloadDuplicates;
    }

    public int stationHoursComputed() {
        return stationHoursComputed;
    }
}
