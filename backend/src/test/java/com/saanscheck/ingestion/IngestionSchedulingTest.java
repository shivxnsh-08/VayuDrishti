package com.saanscheck.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

/** Scheduler wrapper and startup catch-up logic, without Spring (fast, deterministic). */
class IngestionSchedulingTest {

    static final Instant NOW = Instant.parse("2026-11-24T09:24:00Z");

    /** IngestionService whose runOnce is replaced; the real constructor only stores references. */
    static class FakeService extends IngestionService {
        final List<IngestionTrigger> calls = new ArrayList<>();
        RuntimeException failWith;
        Error errorWith;

        FakeService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public synchronized Optional<IngestionRun> runOnce(IngestionTrigger trigger) {
            calls.add(trigger);
            if (failWith != null) {
                throw failWith;
            }
            if (errorWith != null) {
                throw errorWith;
            }
            return Optional.empty();
        }
    }

    @Test
    void cronIsEvaluatedInUtc() throws NoSuchMethodException {
        Scheduled s = IngestionScheduler.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(s.zone()).isEqualTo("UTC");
        assertThat(s.cron()).isEqualTo("${saanscheck.ingestion.cron}");
    }

    @Test
    void aFailureNeverEscapesTheScheduledMethodAndNextRunsStillHappen() {
        FakeService svc = new FakeService();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        IngestionScheduler scheduler = new IngestionScheduler(svc, metrics);

        svc.failWith = new DataAccessResourceFailureException("database is down");   // cannot even record the run
        assertThatCode(scheduler::run).doesNotThrowAnyException();
        svc.failWith = null;
        svc.errorWith = new OutOfMemoryError("simulated");
        assertThatCode(scheduler::run).doesNotThrowAnyException();
        svc.errorWith = null;
        scheduler.run();

        assertThat(svc.calls).containsExactly(IngestionTrigger.SCHEDULED, IngestionTrigger.SCHEDULED, IngestionTrigger.SCHEDULED);
        assertThat(metrics.counter("saanscheck.ingestion.unrecorded_failures").count()).isEqualTo(2.0);
    }

    static IngestionRunRepository runsWithLastSuccess(Instant finishedAt) {
        return new IngestionRunRepository(null) {
            @Override
            public Optional<IngestionRun> lastSuccessful() {
                return Optional.ofNullable(finishedAt).map(f -> new IngestionRun(1, f.minusSeconds(10), f, 1, 1, 0, 0, 0, 0, 0,
                        1, "SUCCESS", null, "SCHEDULED", 10_000L, 0, null, 200, f, Map.of()));
            }
        };
    }

    @Test
    void startupCatchUpRunsWhenLastSuccessIsOldOrMissing() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FakeService svc = new FakeService();
        IngestionScheduler scheduler = new IngestionScheduler(svc, new SimpleMeterRegistry());

        // 18 h of downtime (the real incident): run now, do not wait for the next cron slot.
        assertThat(new IngestionStartupRecovery(scheduler, runsWithLastSuccess(NOW.minus(Duration.ofHours(18))), null, clock,
                Duration.ofMinutes(10), Duration.ofSeconds(20)).catchUpIfDue()).isTrue();
        // never ran
        assertThat(new IngestionStartupRecovery(scheduler, runsWithLastSuccess(null), null, clock,
                Duration.ofMinutes(10), Duration.ofSeconds(20)).catchUpIfDue()).isTrue();
        assertThat(svc.calls).containsExactly(IngestionTrigger.RECOVERY, IngestionTrigger.RECOVERY);

        // a quick restart: the schedule is enough
        assertThat(new IngestionStartupRecovery(scheduler, runsWithLastSuccess(NOW.minus(Duration.ofMinutes(4))), null, clock,
                Duration.ofMinutes(10), Duration.ofSeconds(20)).catchUpIfDue()).isFalse();
        assertThat(svc.calls).hasSize(2);
    }

    @Test
    void startupCatchUpStillRunsWhenHistoryCannotBeRead() {
        FakeService svc = new FakeService();
        IngestionRunRepository broken = new IngestionRunRepository(null) {
            @Override
            public Optional<IngestionRun> lastSuccessful() {
                throw new DataAccessResourceFailureException("db starting");
            }
        };
        new IngestionStartupRecovery(new IngestionScheduler(svc, new SimpleMeterRegistry()), broken, null,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), Duration.ofSeconds(20)).catchUpIfDue();
        assertThat(svc.calls).containsExactly(IngestionTrigger.RECOVERY);
    }
}
