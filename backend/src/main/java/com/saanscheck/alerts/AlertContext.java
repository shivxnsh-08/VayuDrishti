package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Everything the decision needs, already loaded. Keeping I/O out of {@link AlertDecisionEngine}
 * makes it a pure function that is trivial to unit test and to replay over history (X6).
 *
 * @param lastActive most recent non-suppressed, non-failed alert for this user and station (nullable)
 */
public record AlertContext(long userId, String stationId, AqiCategory minCategory,
                           LocalTime quietStart, LocalTime quietEnd, boolean severeOverridesQuiet,
                           Instant currentTs, Integer currentAqi,
                           boolean forecastAvailable, List<Point> forecast,
                           LastAlert lastActive, Instant now) {

    public record Point(Instant ts, int aqi) {
    }

    public record LastAlert(AqiCategory category, Instant createdAt) {
    }
}
