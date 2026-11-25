package com.saanscheck.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * After startup (container restart, PC reboot, deployment) do not wait up to 15 minutes for the next cron
 * slot: if the last successful run is older than {@code startup-catch-up-after}, run once ~20 s after the
 * application is ready (trigger RECOVERY). The run also marks runs orphaned by the previous process as
 * INTERRUPTED. It uses the same lock as the cron, so it can never overlap with a scheduled run.
 *
 * <p>Note: the data.gov.in feed holds only the latest snapshot, so hours missed while the stack was down
 * cannot be recovered; this only shortens the time to the next fresh observation.
 */
@Component
@ConditionalOnProperty(name = {"saanscheck.scheduling.enabled", "saanscheck.ingestion.enabled"},
        havingValue = "true", matchIfMissing = true)
public class IngestionStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(IngestionStartupRecovery.class);
    private final IngestionScheduler scheduler;
    private final IngestionRunRepository runs;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final Duration catchUpAfter;
    private final Duration delay;

    public IngestionStartupRecovery(IngestionScheduler scheduler, IngestionRunRepository runs, TaskScheduler taskScheduler,
                                    Clock clock,
                                    @Value("${saanscheck.ingestion.health.startup-catch-up-after:PT10M}") Duration catchUpAfter,
                                    @Value("${saanscheck.ingestion.health.startup-delay:PT20S}") Duration delay) {
        this.scheduler = scheduler;
        this.runs = runs;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.catchUpAfter = catchUpAfter;
        this.delay = delay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // The trigger time is on the task scheduler's own (wall) clock, not the injectable business clock.
        taskScheduler.schedule(this::catchUpIfDue, taskScheduler.getClock().instant().plus(delay));
        log.info("Ingestion startup check scheduled in {} s", delay.toSeconds());
    }

    /** Returns true if a catch-up run was started. */
    boolean catchUpIfDue() {
        try {
            Optional<IngestionRun> last = runs.lastSuccessful();
            Instant lastAt = last.map(IngestionRun::finishedAt).orElse(null);
            if (lastAt != null && Duration.between(lastAt, clock.instant()).compareTo(catchUpAfter) < 0) {
                log.info("Ingestion startup check: last successful run at {} is recent; waiting for the schedule", lastAt);
                return false;
            }
            log.info("Ingestion startup check: last successful run {}; running a RECOVERY ingestion now",
                    lastAt == null ? "never" : "at " + lastAt);
        } catch (RuntimeException e) {
            log.warn("Ingestion startup check could not read run history ({}); running a RECOVERY ingestion", e.toString());
        }
        scheduler.trigger(IngestionTrigger.RECOVERY);
        return true;
    }
}
