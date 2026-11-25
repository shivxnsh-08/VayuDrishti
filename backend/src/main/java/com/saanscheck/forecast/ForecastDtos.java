package com.saanscheck.forecast;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Wire contract with the Python service ({@code POST /forecast}). Must match
 * {@code forecast-service/app/schemas.py}; the contract test checks the JSON field names.
 */
public final class ForecastDtos {

    private ForecastDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReadingPoint(Instant ts, int aqi) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ForecastRequest(String stationId, List<ReadingPoint> readings, int horizonHours) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Prediction(Instant targetTs, int predictedAqi, Integer lower, Integer upper) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ForecastResponse(String stationId, Instant generatedAt, Instant originTs, String modelVersion,
                                   String method, List<Prediction> predictions) {
    }
}
