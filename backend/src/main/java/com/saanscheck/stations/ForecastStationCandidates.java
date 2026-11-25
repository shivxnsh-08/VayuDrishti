package com.saanscheck.stations;

import com.saanscheck.forecast.ForecastProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Nearest stations with what the Forecast page needs to choose one honestly: how far, how fresh the latest reading
 * is, and whether the station has enough genuine history for a forecast (the backend's own rule: at least
 * {@code min-history-points} valid hourly observations within {@code history-hours}).
 *
 * <p>Privacy: coordinates are rounded to 2 decimals (~1 km) before use, are not stored, and are not echoed back.
 */
@Service
public class ForecastStationCandidates {

    private final StationRepository stations;
    private final ForecastProperties forecast;
    private final Duration staleAfter;
    private final Clock clock;

    public ForecastStationCandidates(StationRepository stations, ForecastProperties forecast, Clock clock,
                                     @Value("${saanscheck.alerts.stale-after:PT3H}") Duration staleAfter) {
        this.stations = stations;
        this.forecast = forecast;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public record Candidate(String id, String name, String city, String state, double distanceKm,
                            Instant latestTs, Integer aqi, String category, boolean stale,
                            int validHours, int requiredHours, boolean forecastReady, String reason) {
    }

    public List<Candidate> nearest(double lat, double lon, int limit) {
        Instant now = clock.instant();
        double rLat = Math.round(lat * 100) / 100.0;
        double rLon = Math.round(lon * 100) / 100.0;
        int required = forecast.minHistoryPoints();
        return stations.findNearestWithHistory(rLat, rLon, limit, now.minus(Duration.ofHours(forecast.historyHours())))
                .stream().map(r -> {
                    boolean stale = r.latestTs() == null || Duration.between(r.latestTs(), now).compareTo(staleAfter) > 0;
                    boolean ready = !stale && r.validHours() >= required;
                    String reason = r.latestTs() == null ? "no valid observations yet"
                            : stale ? "latest reading is " + Duration.between(r.latestTs(), now).toHours() + " h old (stale)"
                            : r.validHours() < required ? "only " + r.validHours() + " of " + required
                              + " hourly observations needed for a forecast"
                            : null;
                    return new Candidate(r.id(), r.name(), r.city(), r.state(),
                            Math.round(r.distanceMeters() / 100.0) / 10.0, r.latestTs(), r.latestAqi(), r.category(),
                            stale, r.validHours(), required, ready, reason);
                }).toList();
    }
}
