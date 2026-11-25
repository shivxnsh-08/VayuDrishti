package com.saanscheck.aqi;

/** Overall AQI for one station-hour: the maximum sub-index and the pollutant that produced it. */
public record AqiResult(int aqi, AqiCategory category, String dominantPollutant, int pollutantCount) {
}
