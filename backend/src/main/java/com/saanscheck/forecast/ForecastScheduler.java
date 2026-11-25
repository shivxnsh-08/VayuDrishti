package com.saanscheck.forecast;

import com.saanscheck.stations.StationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes forecasts after each ingestion run, only for stations someone is subscribed to. */
@Component
@ConditionalOnProperty(name = "saanscheck.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ForecastScheduler {

    private final StationRepository stations;
    private final ForecastService forecasts;

    public ForecastScheduler(StationRepository stations, ForecastService forecasts) {
        this.stations = stations;
        this.forecasts = forecasts;
    }

    @Scheduled(cron = "${saanscheck.forecast.refresh-cron}")
    public void refreshSubscribed() {
        for (String stationId : stations.findSubscribedStationIds()) {
            forecasts.refresh(stationId);
        }
    }
}
