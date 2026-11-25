package com.saanscheck.stations;

import java.time.Instant;

/** A station with its latest AQI; aqi/category/ts are null when no AQI has been computed yet. */
public record StationSummary(String id, String name, String city, String state, double latitude, double longitude,
                             Instant ts, Integer aqi, String category, String dominantPollutant, boolean stale) {

    public StationSummary withStale(boolean value) {
        return new StationSummary(id, name, city, state, latitude, longitude, ts, aqi, category, dominantPollutant, value);
    }
}
