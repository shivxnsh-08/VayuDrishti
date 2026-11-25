package com.saanscheck.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

/** One source record with fields pulled out by name but not yet interpreted. */
public record RawRecord(String stationName, String city, String state, Double latitude, Double longitude,
                        String pollutant, String value, String lastUpdate, JsonNode raw) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180
                && !(latitude == 0.0 && longitude == 0.0);   // (0,0) is a common "unknown" placeholder
    }
}
