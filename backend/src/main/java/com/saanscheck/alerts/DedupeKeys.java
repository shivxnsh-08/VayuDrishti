package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Dedupe keys, enforced by a UNIQUE constraint on {@code alerts.dedupe_key}.
 *
 * <p>Alert key = user + station + category + window start. Re-evaluating every 10 minutes
 * yields the same key for the same episode, so the INSERT is a no-op. A worse category gives a
 * new key (escalation). The window start is bucketed (see {@link AlertDecisionEngine}) so a
 * forecast that shifts by an hour does not create a "new" episode.
 */
public final class DedupeKeys {

    private DedupeKeys() {
    }

    public static String alert(long userId, String stationId, AqiCategory category, Instant windowStart) {
        return "u" + userId + ":s" + stationId + ":c" + category.name() + ":w" + windowStart.getEpochSecond();
    }

    public static String suppressed(String alertKey, String reason) {
        return alertKey + ":SUPPRESSED:" + reason;
    }

    public static String staleNotice(long userId, String stationId, LocalDate day) {
        return "u" + userId + ":s" + stationId + ":STALE:" + day;
    }
}
