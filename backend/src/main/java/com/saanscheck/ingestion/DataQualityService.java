package com.saanscheck.ingestion;

import com.saanscheck.stations.StationAqiRepository;
import com.saanscheck.stations.StationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Data-quality summary behind the admin endpoint (and the basis of the X1 report). */
@Service
public class DataQualityService {

    private final ReadingRepository readings;
    private final IngestionRunRepository runs;
    private final StationRepository stations;
    private final StationAqiRepository stationAqi;
    private final Clock clock;
    private final Duration staleAfter;

    public DataQualityService(ReadingRepository readings, IngestionRunRepository runs, StationRepository stations,
                              StationAqiRepository stationAqi, Clock clock,
                              @Value("${saanscheck.alerts.stale-after:PT3H}") Duration staleAfter) {
        this.readings = readings;
        this.runs = runs;
        this.stations = stations;
        this.stationAqi = stationAqi;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public Summary summary(Duration window) {
        Instant now = clock.instant();
        Instant since = now.minus(window);
        int windowHours = (int) Math.max(1, window.toHours());
        List<StationAqiRepository.MissingHours> missing = stationAqi.missingHours(windowHours, 100_000);
        int missingTotal = missing.stream().mapToInt(StationAqiRepository.MissingHours::missingHours).sum();
        return new Summary(since, now,
                runs.successRate(since),
                readings.countByQuality(since),
                readings.flaggedSeries(since, ReadingQuality.STUCK.name(), 20),
                readings.flaggedSeries(since, ReadingQuality.INVALID.name(), 20),
                stations.countActive(),
                stationAqi.countStaleStations(now.minus(staleAfter)),
                missingTotal, missing.stream().limit(20).toList());
    }

    public record Summary(Instant since, Instant until, double runSuccessRate,
                          Map<String, Long> readingsByQuality,
                          List<ReadingRepository.FlaggedSeries> topStuck,
                          List<ReadingRepository.FlaggedSeries> topInvalid,
                          int activeStations, int staleStations,
                          int missingAqiHours, List<StationAqiRepository.MissingHours> topMissingHours) {
    }
}
