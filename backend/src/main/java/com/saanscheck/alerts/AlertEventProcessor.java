package com.saanscheck.alerts;

import com.saanscheck.advice.TelegramMessages;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.profile.Profile;
import com.saanscheck.stations.Station;
import com.saanscheck.stations.StationAqi;
import com.saanscheck.stations.StationAqiRepository;
import com.saanscheck.stations.StationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link AqiEventRules} for one user, in one transaction:
 * lock the user's {@code alert_state} row, read the latest genuine observation, decide, queue the notification
 * (PENDING, delivered by {@link AlertDispatcher}) or record it as SUPPRESSED, and save the new state.
 *
 * <p>Exactly-once per observation: the state row lock serialises concurrent evaluators, the processed observation
 * time is stored with the decision, and the dedupe keys ({@code evt:...:o<obs>}, {@code rem:...:a<anchor>}) are UNIQUE.
 * A restart re-reads the same state, so nothing is sent twice.
 */
@Service
public class AlertEventProcessor {

    private final AlertStateRepository states;
    private final StationAqiRepository stationAqi;
    private final StationRepository stations;
    private final AlertRepository alerts;
    private final TelegramMessages messages;
    private final AlertProperties alertProps;
    private final AlertEventProperties props;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final ZoneId zone;

    public AlertEventProcessor(AlertStateRepository states, StationAqiRepository stationAqi, StationRepository stations,
                               AlertRepository alerts, TelegramMessages messages, AlertProperties alertProps,
                               AlertEventProperties props, MeterRegistry metrics, Clock clock) {
        this.states = states;
        this.stationAqi = stationAqi;
        this.stations = stations;
        this.alerts = alerts;
        this.messages = messages;
        this.alertProps = alertProps;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        this.zone = ZoneId.of(alertProps.zone());
    }

    @Transactional
    public AqiEventRules.Decision process(Profile p) {
        Instant now = clock.instant();
        AqiCategory threshold = alertProps.minCategoryFor(p.healthProfile(), p.alertMinCategory());
        AqiEventRules.State s = states.findForUpdate(p.userId()).orElse(null);
        Optional<StationAqi> latest = stationAqi.findLatest(p.stationId())
                .filter(a -> a.aqi() != null && a.ts() != null && a.category() != null);
        AqiEventRules.Observation obs = latest.map(a -> new AqiEventRules.Observation(a.ts(), a.aqi(),
                AqiCategory.valueOf(a.category()))).orElse(null);
        boolean quiet = QuietHours.isQuiet(now.atZone(zone).toLocalTime(), p.quietStart(), p.quietEnd());

        AqiEventRules.Decision d = AqiEventRules.decide(s, obs, p.stationId(), threshold, now, alertProps.staleAfter(),
                props, quiet, p.severeOverridesQuiet());
        metrics.counter("saanscheck.alerts.evaluations", "outcome", d.action().name().toLowerCase()).increment();

        boolean sameStation = s != null && p.stationId().equals(s.stationId());
        switch (d.action()) {
            case NONE -> { }
            case BASELINE -> states.save(p.userId(), new AqiEventRules.State(p.stationId(), threshold, obs.ts(), obs.aqi(),
                    obs.aqi(), obs.category(), obs.ts(), d.aboveNow(),
                    sameStation ? s.lastEventAt() : null, sameStation ? s.lastReminderAt() : null,
                    sameStation ? s.trackingSince() : now), now);
            case OBSERVED -> states.save(p.userId(), withObservation(s, obs), now);
            case EVENT -> {
                String key = "evt:u" + p.userId() + ":s" + p.stationId() + ":o" + obs.ts().getEpochSecond();
                queue(p, key, d, obs, latest.get(), s, threshold, AlertStatus.PENDING, null, now);
                states.save(p.userId(), new AqiEventRules.State(p.stationId(), threshold, obs.ts(), obs.aqi(),
                        obs.aqi(), obs.category(), obs.ts(), d.aboveNow(), now, null,
                        sameStation ? s.trackingSince() : now), now);   // reference = what the user is now told
            }
            case SUPPRESSED -> {
                String key = "evt:u" + p.userId() + ":s" + p.stationId() + ":o" + obs.ts().getEpochSecond() + ":SUPPRESSED";
                queue(p, key, d, obs, latest.get(), s, threshold, AlertStatus.SUPPRESSED,
                        "suppressed: quiet hours (" + d.reason() + ")", now);
                // Keep the reference (and the above/below flag) so the change is announced after quiet hours.
                states.save(p.userId(), sameStation ? withObservation(s, obs)
                        : new AqiEventRules.State(p.stationId(), threshold, obs.ts(), obs.aqi(), obs.aqi(), obs.category(),
                                obs.ts(), false, null, null, now), now);
            }
            case REMINDER -> {
                Instant anchor = latest(latest(s.lastEventAt(), s.lastReminderAt()), s.trackingSince());
                String key = "rem:u" + p.userId() + ":s" + p.stationId() + ":a" + anchor.getEpochSecond();
                queue(p, key, d, obs, latest.get(), s, threshold, AlertStatus.PENDING, null, now);
                AqiEventRules.State base = d.newObservation() ? withObservation(s, obs) : s;
                states.save(p.userId(), new AqiEventRules.State(base.stationId(), base.threshold(), base.lastObsTs(),
                        base.lastObsAqi(), base.refAqi(), base.refCategory(), base.refTs(), base.above(),
                        base.lastEventAt(), now, base.trackingSince()), now);
            }
        }
        return d;
    }

    private void queue(Profile p, String key, AqiEventRules.Decision d, AqiEventRules.Observation obs, StationAqi a,
                       AqiEventRules.State s, AqiCategory threshold, AlertStatus status, String note, Instant now) {
        if (alerts.existsByDedupeKey(key)) {
            return;                                   // already recorded (cheap check before calling the advice service)
        }
        Station station = stations.findById(p.stationId())
                .orElse(new Station(p.stationId(), p.stationId(), null, null, 0, 0, true));
        String message = messages.aqiEvent(p, station, d.kind(),
                new TelegramMessages.Reading(obs.ts(), obs.aqi(), obs.category(), a.dominantPollutant()),
                d.previousAqi(), d.change(), s == null ? null : s.refTs(), threshold, now);
        if (alerts.insertEvent(p.userId(), p.stationId(), key, d.kind(), obs.category(), obs.aqi(), d.previousAqi(),
                d.change(), obs.ts(), message, status, note, now)) {
            metrics.counter(status == AlertStatus.SUPPRESSED ? "saanscheck.alerts.suppressed" : "saanscheck.alerts.created",
                    status == AlertStatus.SUPPRESSED ? "reason" : "kind",
                    status == AlertStatus.SUPPRESSED ? "quiet_hours" : d.kind().name()).increment();
        }
    }

    private static AqiEventRules.State withObservation(AqiEventRules.State s, AqiEventRules.Observation obs) {
        return new AqiEventRules.State(s.stationId(), s.threshold(), obs.ts(), obs.aqi(), s.refAqi(), s.refCategory(),
                s.refTs(), s.above(), s.lastEventAt(), s.lastReminderAt(), s.trackingSince());
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
