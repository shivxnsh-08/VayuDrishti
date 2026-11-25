package com.saanscheck.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-24T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsNPerWindowPerKeyAndRecoversAfterTheWindow() {
        TestClock clock = new TestClock();
        SlidingWindowLimiter l = new SlidingWindowLimiter(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 3; i++) {
            assertThat(l.tryAcquire("a")).isTrue();
        }
        assertThat(l.tryAcquire("a")).isFalse();
        assertThat(l.tryAcquire("b")).isTrue();                 // keys are independent
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(l.tryAcquire("a")).isTrue();
        l.reset("a");
        assertThat(l.isLimited("a")).isFalse();
    }
}
