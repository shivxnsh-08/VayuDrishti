package com.saanscheck.metrics;

import com.saanscheck.stations.StationAqiRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code saanscheck.stations.stale}: refreshed by a scheduled query rather than on every
 * Prometheus scrape, so a slow or down database never makes the scrape hang.
 */
@Component
public class StaleStationsGauge {

    private static final Logger log = LoggerFactory.getLogger(StaleStationsGauge.class);

    private final StationAqiRepository stationAqi;
    private final Clock clock;
    private final Duration staleAfter;
    private final AtomicInteger value = new AtomicInteger(-1);
    private final AtomicInteger missingHours = new AtomicInteger(-1);

    public StaleStationsGauge(StationAqiRepository stationAqi, Clock clock, MeterRegistry registry,
                              @Value("${saanscheck.alerts.stale-after:PT3H}") Duration staleAfter) {
        this.stationAqi = stationAqi;
        this.clock = clock;
        this.staleAfter = staleAfter;
        Gauge.builder("saanscheck.stations.stale", value, AtomicInteger::get)
                .description("Active stations whose latest AQI is older than the stale limit")
                .register(registry);
        Gauge.builder("saanscheck.stations.missing_hours_24h", missingHours, AtomicInteger::get)
                .description("Station-hours without an AQI value over the last 24 feed hours (active stations)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT10S")
    public void refresh() {
        try {
            value.set(stationAqi.countStaleStations(clock.instant().minus(staleAfter)));
            missingHours.set(stationAqi.missingHours(24, 100_000).stream()
                    .mapToInt(com.saanscheck.stations.StationAqiRepository.MissingHours::missingHours).sum());
        } catch (RuntimeException e) {
            log.debug("Stale-station gauge refresh failed: {}", e.getMessage());
        }
    }
}
