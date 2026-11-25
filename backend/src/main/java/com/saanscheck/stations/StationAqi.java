package com.saanscheck.stations;

import java.io.Serializable;
import java.time.Instant;

/** One derived station-hour AQI row. Cached in Redis, hence Serializable. */
public record StationAqi(String stationId, Instant ts, Integer aqi, String category,
                        String dominantPollutant, Integer pollutantCount) implements Serializable {
}
