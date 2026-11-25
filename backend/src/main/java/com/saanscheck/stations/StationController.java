package com.saanscheck.stations;

import com.saanscheck.forecast.ForecastService;
import com.saanscheck.forecast.ForecastView;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public station endpoints (no login needed to look up air quality). */
@RestController
@RequestMapping("/api/v1/stations")
@Validated
public class StationController {

    private final StationService stations;
    private final ForecastService forecasts;
    private final ForecastStationCandidates candidates;

    public StationController(StationService stations, ForecastService forecasts, ForecastStationCandidates candidates) {
        this.stations = stations;
        this.forecasts = forecasts;
        this.candidates = candidates;
    }

    /** All active stations with their latest AQI (map and dashboard). */
    @GetMapping
    public List<StationSummary> all() {
        return stations.allWithLatest();
    }

    @GetMapping("/nearest")
    public List<NearestStation> nearest(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                        @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
                                        @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return stations.nearest(lat, lon, limit);
    }

    /** Nearest stations with freshness and forecast readiness, for automatic station selection. */
    @GetMapping("/nearest/forecast-candidates")
    public List<ForecastStationCandidates.Candidate> forecastCandidates(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
        return candidates.nearest(lat, lon, limit);
    }

    @GetMapping("/{id}/aqi/current")
    public CurrentAqiView current(@PathVariable String id) {
        return stations.current(id);
    }

    @GetMapping("/{id}/aqi/history")
    public StationService.HistoryPage history(
            @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return stations.history(id, from, to, page, size);
    }

    /** Never fails because the forecast service is down: status becomes UNAVAILABLE instead. */
    @GetMapping("/{id}/forecast")
    public ForecastView forecast(@PathVariable String id) {
        stations.requireStation(id);
        return forecasts.getForecast(id);
    }
}
