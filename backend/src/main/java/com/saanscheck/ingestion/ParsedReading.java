package com.saanscheck.ingestion;

import java.time.Instant;

/** A reading after parsing and validation; {@code value} is the cleaned value (null unless usable). */
public record ParsedReading(String stationId, String pollutant, Instant ts, Double value,
                            ReadingQuality quality, String rawJson) {

    public ParsedReading withQuality(ReadingQuality q) {
        return new ParsedReading(stationId, pollutant, ts, value, q, rawJson);
    }
}
