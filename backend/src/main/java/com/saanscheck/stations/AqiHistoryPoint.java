package com.saanscheck.stations;

import java.time.Instant;
import java.util.List;

/** One history row plus the data-quality flags of the readings behind it (e.g. "PM25:STUCK"). */
public record AqiHistoryPoint(Instant ts, Integer aqi, String category, String dominantPollutant,
                              Integer pollutantCount, List<String> qualityFlags) {
}
