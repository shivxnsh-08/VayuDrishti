package com.saanscheck.stations;

import java.time.Instant;

/**
 * Response of {@code /stations/{id}/aqi/current}. {@code stale} is computed at read time
 * from the configured limit, so a cached row can never hide that data stopped arriving.
 */
public record CurrentAqiView(String stationId, String stationName, Instant ts, Integer aqi,
                             String category, String dominantPollutant, boolean stale, long ageMinutes) {
}
