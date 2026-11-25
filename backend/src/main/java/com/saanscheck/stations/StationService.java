package com.saanscheck.stations;

import com.saanscheck.common.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StationService {

    private final StationRepository stations;
    private final StationAqiRepository aqi;
    private final CachedStationReads cached;
    private final Clock clock;
    private final Duration staleAfter;

    public StationService(StationRepository stations, StationAqiRepository aqi, CachedStationReads cached,
                          Clock clock, @Value("${saanscheck.alerts.stale-after:PT3H}") Duration staleAfter) {
        this.stations = stations;
        this.aqi = aqi;
        this.cached = cached;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public List<NearestStation> nearest(double lat, double lon, int limit) {
        return cached.nearest(lat, lon, limit);
    }

    /** All active stations with latest AQI; the stale flag is computed here, at read time. */
    public List<StationSummary> allWithLatest() {
        Instant now = clock.instant();
        return stations.findAllWithLatest().stream()
                .map(s -> s.withStale(s.ts() == null || Duration.between(s.ts(), now).compareTo(staleAfter) > 0))
                .toList();
    }

    public Station requireStation(String stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("Unknown station: " + stationId));
    }

    /** Latest AQI with a stale flag. A station with no data at all is reported as stale with null values. */
    public CurrentAqiView current(String stationId) {
        Station station = requireStation(stationId);
        StationAqi latest = cached.latest(stationId);
        Instant now = clock.instant();
        if (latest == null) {
            return new CurrentAqiView(stationId, station.name(), null, null, null, null, true, -1);
        }
        long age = Duration.between(latest.ts(), now).toMinutes();
        boolean stale = Duration.between(latest.ts(), now).compareTo(staleAfter) > 0;
        return new CurrentAqiView(stationId, station.name(), latest.ts(), latest.aqi(), latest.category(),
                latest.dominantPollutant(), stale, age);
    }

    public HistoryPage history(String stationId, Instant from, Instant to, int page, int size) {
        requireStation(stationId);
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(Duration.ofDays(7));
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        long total = aqi.countHistory(stationId, start, end);
        List<AqiHistoryPoint> items = aqi.findHistory(stationId, start, end, size, page * size);
        return new HistoryPage(stationId, start, end, page, size, total, items);
    }

    public record HistoryPage(String stationId, Instant from, Instant to, int page, int size,
                              long total, List<AqiHistoryPoint> items) {
    }
}
