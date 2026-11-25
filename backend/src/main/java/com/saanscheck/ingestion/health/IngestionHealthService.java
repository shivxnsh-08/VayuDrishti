package com.saanscheck.ingestion.health;

import com.saanscheck.ingestion.IngestionRunRepository;
import com.saanscheck.stations.StationAqiRepository;
import com.saanscheck.stations.StationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Gathers the facts from the database and evaluates them with {@link IngestionHealthCalculator}. */
@Service
public class IngestionHealthService {

    private final IngestionRunRepository runs;
    private final StationAqiRepository stationAqi;
    private final StationRepository stations;
    private final IngestionHealthProperties props;
    private final Clock clock;
    private final Duration staleAfter;

    public IngestionHealthService(IngestionRunRepository runs, StationAqiRepository stationAqi, StationRepository stations,
                                  IngestionHealthProperties props, Clock clock,
                                  @Value("${saanscheck.alerts.stale-after:PT3H}") Duration staleAfter) {
        this.runs = runs;
        this.stationAqi = stationAqi;
        this.stations = stations;
        this.props = props;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public IngestionHealth current() {
        Instant now = clock.instant();
        return IngestionHealthCalculator.evaluate(new IngestionHealthCalculator.Inputs(
                now, runs.recent(20), runs.lastSuccessfulFetch().orElse(null), stationAqi.latestObservationTs(),
                stations.countActive(), stationAqi.countStaleStations(now.minus(staleAfter)), staleAfter), props);
    }
}
