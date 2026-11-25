package com.saanscheck.ingestion;

/** What started an ingestion run (recorded in {@code ingestion_runs.trigger}). */
public enum IngestionTrigger {
    /** The 15-minute cron. */
    SCHEDULED,
    /** Catch-up run shortly after application startup, when the last successful run is old. */
    RECOVERY,
    /** Admin endpoint or tests. */
    MANUAL
}
