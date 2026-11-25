package com.saanscheck.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The 15-minute ingestion trigger (cron {@code saanscheck.ingestion.cron}, evaluated in UTC).
 *
 * <p>A failed or skipped run never affects later ones: the cron trigger is independent of the outcome, and
 * nothing is allowed to escape this method. Every outcome is in {@code ingestion_runs}; anything that could
 * not even be recorded (e.g. database down) is logged at ERROR and counted in
 * {@code saanscheck.ingestion.unrecorded_failures}.
 */
@Component
@ConditionalOnProperty(name = {"saanscheck.scheduling.enabled", "saanscheck.ingestion.enabled"},
        havingValue = "true", matchIfMissing = true)
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final IngestionService service;
    private final MeterRegistry metrics;

    public IngestionScheduler(IngestionService service, MeterRegistry metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${saanscheck.ingestion.cron}", zone = "UTC")
    public void run() {
        trigger(IngestionTrigger.SCHEDULED);
    }

    void trigger(IngestionTrigger trigger) {
        metrics.counter("saanscheck.ingestion.triggers", "trigger", trigger.name()).increment();
        try {
            service.runOnce(trigger);
        } catch (Throwable t) {   // e.g. database unavailable before the run could be recorded
            metrics.counter("saanscheck.ingestion.unrecorded_failures").increment();
            log.error("Ingestion ({}) failed before it could be recorded: {}", trigger,
                    DataGovClient.redact(String.valueOf(t)), t);
        }
    }
}
