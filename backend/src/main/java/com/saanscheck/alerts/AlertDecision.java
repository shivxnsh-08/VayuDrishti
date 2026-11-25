package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.time.Instant;

/** Result of evaluating one user against their station. A sealed type keeps the handling exhaustive. */
public sealed interface AlertDecision {

    record None(String reason) implements AlertDecision {
    }

    record Send(AlertKind kind, AqiCategory category, int peakAqi, Instant peakTs,
                Instant windowStart, Trend trend) implements AlertDecision {
    }

    record Suppress(String reason, Send wouldSend) implements AlertDecision {
    }

    record StaleNotice(Instant lastDataTs) implements AlertDecision {
    }
}
