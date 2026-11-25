package com.saanscheck.forecast;

import java.time.Instant;
import java.util.List;

/** API representation of a station forecast, always with an explicit availability status. */
public record ForecastView(String stationId, Status status, String modelVersion, String method,
                           Instant generatedAt, List<Point> points, String message) {

    public enum Status { AVAILABLE, UNAVAILABLE, INSUFFICIENT_DATA, STALE_DATA }

    public record Point(Instant targetTs, int predictedAqi, Integer lower, Integer upper, String category) {
    }

    public static ForecastView unavailable(String stationId, String message) {
        return new ForecastView(stationId, Status.UNAVAILABLE, null, null, null, List.of(), message);
    }

    public static ForecastView insufficient(String stationId, String message) {
        return new ForecastView(stationId, Status.INSUFFICIENT_DATA, null, null, null, List.of(), message);
    }

    public static ForecastView staleData(String stationId, String message) {
        return new ForecastView(stationId, Status.STALE_DATA, null, null, null, List.of(), message);
    }

    public boolean available() {
        return status == Status.AVAILABLE;
    }
}
