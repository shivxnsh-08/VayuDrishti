package com.saanscheck.forecast;

import com.saanscheck.aqi.AqiCalculator;
import com.saanscheck.stations.StationAqi;
import com.saanscheck.stations.StationAqiRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Serves forecasts with graceful degradation: a fresh stored batch is reused; otherwise the
 * Python service is called; if that fails, the result is an explicit UNAVAILABLE status, never
 * an exception to the caller. Current AQI endpoints do not depend on this class at all.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);
    static final int HORIZON_HOURS = 24;

    private final ForecastProperties props;
    private final ForecastClient client;
    private final ForecastRepository repository;
    private final StationAqiRepository stationAqi;
    private final AqiCalculator calculator;
    private final MeterRegistry metrics;
    private final Clock clock;

    public ForecastService(ForecastProperties props, ForecastClient client, ForecastRepository repository,
                           StationAqiRepository stationAqi, AqiCalculator calculator, MeterRegistry metrics,
                           Clock clock) {
        this.props = props;
        this.client = client;
        this.repository = repository;
        this.stationAqi = stationAqi;
        this.calculator = calculator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public ForecastView getForecast(String stationId) {
        Instant now = clock.instant();
        Optional<ForecastRepository.Batch> latest = repository.findLatestBatch(stationId);
        if (latest.isPresent() && Duration.between(latest.get().generatedAt(), now).compareTo(props.freshFor()) <= 0) {
            ForecastRepository.Batch b = latest.get();
            return toView(stationId, b.generatedAt(), b.modelVersion(), b.method(), b.predictions(), now);
        }
        return refresh(stationId);
    }

    /** Always calls the forecast service (used by the hourly scheduler). */
    public ForecastView refresh(String stationId) {
        Instant now = clock.instant();
        List<StationAqi> series = stationAqi.findSeries(stationId, now.minus(Duration.ofHours(props.historyHours())));
        // Readiness rules (never padded or fabricated to reach the minimum):
        // 1. the newest observation must be recent, otherwise a "forecast" would start from old data;
        // 2. at least min-history-points distinct, valid hourly observations within history-hours.
        //    station_aqi has one row per (station, hour) and findSeries returns them ascending.
        if (!series.isEmpty() && Duration.between(series.getLast().ts(), now).compareTo(props.maxDataAge()) > 0) {
            return ForecastView.staleData(stationId, "Latest observation is from " + series.getLast().ts()
                    + "; forecasts resume when fresh data arrives");
        }
        long validHours = series.stream().filter(s -> s.aqi() != null && s.aqi() >= 0).map(StationAqi::ts).distinct().count();
        if (validHours < props.minHistoryPoints()) {
            return ForecastView.insufficient(stationId,
                    "Only " + validHours + " hourly points available; need " + props.minHistoryPoints());
        }
        var request = new ForecastDtos.ForecastRequest(stationId,
                series.stream().map(s -> new ForecastDtos.ReadingPoint(s.ts(), s.aqi())).toList(), HORIZON_HOURS);

        metrics.counter("saanscheck.forecast.calls").increment();
        Timer.Sample sample = Timer.start(metrics);
        try {
            ForecastDtos.ForecastResponse resp = client.forecast(request);
            sample.stop(metrics.timer("saanscheck.forecast.latency", "outcome", "success"));
            if (resp == null || resp.predictions() == null || resp.predictions().isEmpty()) {
                throw new IllegalStateException("empty forecast response");
            }
            Instant generatedAt = now.truncatedTo(ChronoUnit.SECONDS);
            repository.saveBatch(stationId, generatedAt, resp.modelVersion(), resp.method(), resp.predictions());
            return toView(stationId, generatedAt, resp.modelVersion(), resp.method(), resp.predictions(), now);
        } catch (RuntimeException e) {
            sample.stop(metrics.timer("saanscheck.forecast.latency", "outcome", "failure"));
            metrics.counter("saanscheck.forecast.failures", "exception", e.getClass().getSimpleName()).increment();
            log.warn("Forecast unavailable for {}: {}", stationId, e.toString());
            return ForecastView.unavailable(stationId, "Forecast service unavailable; current AQI is still served");
        }
    }

    private ForecastView toView(String stationId, Instant generatedAt, String modelVersion, String method,
                                List<ForecastDtos.Prediction> predictions, Instant now) {
        List<ForecastView.Point> points = predictions.stream()
                .filter(p -> p.targetTs().isAfter(now.minus(Duration.ofHours(1))))
                .map(p -> new ForecastView.Point(p.targetTs(), p.predictedAqi(), p.lower(), p.upper(),
                        calculator.categorize(p.predictedAqi()).name()))
                .toList();
        return new ForecastView(stationId, ForecastView.Status.AVAILABLE, modelVersion, method, generatedAt, points, null);
    }
}
