package com.saanscheck.alerts;

import com.saanscheck.advice.TelegramMessages;
import com.saanscheck.forecast.ForecastService;
import com.saanscheck.forecast.ForecastView;
import com.saanscheck.profile.Profile;
import com.saanscheck.profile.ProfileRepository;
import com.saanscheck.stations.Station;
import com.saanscheck.stations.StationAqi;
import com.saanscheck.stations.StationAqiRepository;
import com.saanscheck.stations.StationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Trigger loop, for each user with a station, a linked chat and AQI alerts switched on:
 * <ol>
 *   <li>change-based AQI events from new genuine observations - threshold crossing, deterioration, recovery and
 *       8-hour stability reminders ({@link AlertEventProcessor}, {@link AqiEventRules});</li>
 *   <li>forecast alerts and stale-data notices with the existing rules ({@link AlertDecisionEngine}). Its
 *       current-AQI alerts are superseded by step 1 and no longer created.</li>
 * </ol>
 * Everything is recorded in the outbox; sending happens in {@link AlertDispatcher}, so a Telegram outage never
 * blocks evaluation. Each run logs one summary line.
 */
@Service
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final ProfileRepository profiles;
    private final StationRepository stations;
    private final StationAqiRepository stationAqi;
    private final ForecastService forecasts;
    private final AlertRepository alerts;
    private final AlertDecisionEngine engine;
    private final AlertProperties props;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final AlertEventProcessor events;
    private final TelegramMessages messages;

    public AlertEvaluator(ProfileRepository profiles, StationRepository stations, StationAqiRepository stationAqi,
                          ForecastService forecasts, AlertRepository alerts, AlertDecisionEngine engine,
                          AlertProperties props, MeterRegistry metrics, Clock clock,
                          AlertEventProcessor events, TelegramMessages messages) {
        this.events = events;
        this.messages = messages;
        this.profiles = profiles;
        this.stations = stations;
        this.stationAqi = stationAqi;
        this.forecasts = forecasts;
        this.alerts = alerts;
        this.engine = engine;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void evaluateAll() {
        List<Profile> users = profiles.findAlertable();
        java.util.Map<String, Integer> outcomes = new java.util.TreeMap<>();
        int failed = 0;
        for (Profile p : users) {
            try {
                AqiEventRules.Decision d = events.process(p);
                outcomes.merge(d.action() + (d.kind() == null ? "" : ":" + d.kind()), 1, Integer::sum);
            } catch (RuntimeException e) {
                failed++;
                log.error("AQI event evaluation failed for user {}: {}", p.userId(), e.toString());
            }
            try {
                evaluate(p);
            } catch (RuntimeException e) {
                failed++;
                log.error("Forecast/stale alert evaluation failed for user {}: {}", p.userId(), e.toString());
            }
        }
        log.info("Alert evaluation: {} user(s) evaluated; AQI event outcomes {}{}", users.size(), outcomes,
                failed > 0 ? "; " + failed + " failure(s)" : "");
    }

    public AlertDecision evaluate(Profile p) {
        Instant now = clock.instant();
        StationAqi latest = stationAqi.findLatest(p.stationId()).orElse(null);
        ForecastView forecast = forecasts.getForecast(p.stationId());
        List<AlertContext.Point> points = forecast.points().stream()
                .map(pt -> new AlertContext.Point(pt.targetTs(), pt.predictedAqi())).toList();

        AlertContext ctx = new AlertContext(p.userId(), p.stationId(),
                props.minCategoryFor(p.healthProfile(), p.alertMinCategory()),
                p.quietStart(), p.quietEnd(), p.severeOverridesQuiet(),
                latest == null ? null : latest.ts(), latest == null ? null : latest.aqi(),
                forecast.available(), points,
                alerts.lastActive(p.userId(), p.stationId()).orElse(null), now);

        AlertDecision decision = engine.decide(ctx);
        Station station = stations.findById(p.stationId())
                .orElse(new Station(p.stationId(), p.stationId(), null, null, 0, 0, true));

        // Current-AQI decisions are handled by the change-based event engine; keep only forecast alerts here.
        if (decision instanceof AlertDecision.Send s0 && s0.kind() == AlertKind.CURRENT
                || decision instanceof AlertDecision.Suppress sup0 && sup0.wouldSend().kind() == AlertKind.CURRENT) {
            return decision;
        }
        switch (decision) {
            case AlertDecision.Send s -> {
                String key = DedupeKeys.alert(p.userId(), p.stationId(), s.category(), s.windowStart());
                if (!alerts.existsByDedupeKey(key)) {   // cheap pre-check avoids an LLM call per evaluation
                    String message = messages.forecastAlert(p, station, s.peakAqi(), s.category(), s.peakTs(),
                            forecast.method(), forecast.modelVersion(), latest == null ? null : latest.ts(),
                            latest == null ? null : latest.aqi(), now);
                    alerts.insertIfAbsent(new AlertRepository.NewAlert(p.userId(), p.stationId(), key, s.kind(),
                                    s.category(), s.peakAqi(), s.windowStart(), message, AlertStatus.PENDING, now))
                            .ifPresent(id -> metrics.counter("saanscheck.alerts.created", "kind", s.kind().name()).increment());
                }
            }
            case AlertDecision.Suppress sup -> {
                metrics.counter("saanscheck.alerts.suppressed", "reason", sup.reason()).increment();
                if ("quiet_hours".equals(sup.reason())) {
                    AlertDecision.Send s = sup.wouldSend();
                    String key = DedupeKeys.suppressed(
                            DedupeKeys.alert(p.userId(), p.stationId(), s.category(), s.windowStart()), sup.reason());
                    alerts.insertIfAbsent(new AlertRepository.NewAlert(p.userId(), p.stationId(), key, s.kind(),
                            s.category(), s.peakAqi(), s.windowStart(), "Suppressed: quiet hours",
                            AlertStatus.SUPPRESSED, now));
                }
            }
            case AlertDecision.StaleNotice st -> {
                String key = DedupeKeys.staleNotice(p.userId(), p.stationId(),
                        now.atZone(ZoneId.of(props.zone())).toLocalDate());
                if (!alerts.existsByDedupeKey(key)) {
                    String message = messages.staleNotice(p, station, st.lastDataTs(), now);
                    alerts.insertIfAbsent(new AlertRepository.NewAlert(p.userId(), p.stationId(), key,
                                    AlertKind.STALE_NOTICE, null, null, null, message, AlertStatus.PENDING, now))
                            .ifPresent(id -> metrics.counter("saanscheck.alerts.created", "kind", "STALE_NOTICE").increment());
                }
            }
            case AlertDecision.None none -> {
                // nothing to do
            }
        }
        return decision;
    }
}
