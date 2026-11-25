package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCalculator;
import com.saanscheck.aqi.AqiCategory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure alert rules, in order:
 * <ol>
 *   <li><b>Stale data</b>: latest reading older than {@code stale-after} -> no forecast alert,
 *       an informational notice instead.</li>
 *   <li><b>Threshold</b>: peak AQI over now + next {@code horizon-hours} (forecast only if available,
 *       otherwise current AQI only) must reach the user's minimum category.</li>
 *   <li><b>Cooldown / escalation</b>: within the cooldown after an alert, stay silent unless the
 *       category got worse.</li>
 *   <li><b>Quiet hours</b>: suppress, unless SEVERE and the user opted in to severe overrides.</li>
 * </ol>
 */
@Component
public class AlertDecisionEngine {

    private final AlertProperties props;
    private final AqiCalculator calculator;
    private final ZoneId zone;

    public AlertDecisionEngine(AlertProperties props, AqiCalculator calculator) {
        this.props = props;
        this.calculator = calculator;
        this.zone = ZoneId.of(props.zone());
    }

    public AlertDecision decide(AlertContext c) {
        Instant now = c.now();
        if (c.currentTs() == null || c.currentAqi() == null
                || Duration.between(c.currentTs(), now).compareTo(props.staleAfter()) > 0) {
            return new AlertDecision.StaleNotice(c.currentTs());
        }

        AlertContext.Point current = new AlertContext.Point(c.currentTs(), c.currentAqi());
        List<AlertContext.Point> points = new ArrayList<>();
        points.add(current);
        List<AlertContext.Point> future = List.of();
        if (c.forecastAvailable() && c.forecast() != null) {
            Instant horizonEnd = now.plus(Duration.ofHours(props.horizonHours()));
            future = c.forecast().stream()
                    .filter(p -> p.ts().isAfter(now) && !p.ts().isAfter(horizonEnd))
                    .sorted(Comparator.comparing(AlertContext.Point::ts))
                    .toList();
            points.addAll(future);
        }

        // Peak = highest AQI; ties resolved to the earliest time (warn as early as possible).
        AlertContext.Point peak = points.stream()
                .max(Comparator.comparingInt(AlertContext.Point::aqi)
                        .thenComparing(AlertContext.Point::ts, Comparator.reverseOrder()))
                .orElseThrow();
        AqiCategory peakCategory = calculator.categorize(peak.aqi());
        if (!peakCategory.isAtLeast(c.minCategory())) {
            return new AlertDecision.None("below_threshold");
        }

        AlertContext.Point firstCrossing = points.stream()
                .filter(p -> calculator.categorize(p.aqi()).isAtLeast(c.minCategory()))
                .min(Comparator.comparing(AlertContext.Point::ts))
                .orElse(peak);
        AlertKind kind = peak == current ? AlertKind.CURRENT : AlertKind.FORECAST;
        AlertDecision.Send send = new AlertDecision.Send(kind, peakCategory, peak.aqi(), peak.ts(),
                windowStart(firstCrossing.ts()), trend(current, future));

        AlertContext.LastAlert last = c.lastActive();
        if (last != null && Duration.between(last.createdAt(), now).compareTo(props.cooldown()) < 0
                && !peakCategory.isWorseThan(last.category())) {
            return new AlertDecision.Suppress("cooldown", send);
        }

        boolean quiet = QuietHours.isQuiet(now.atZone(zone).toLocalTime(), c.quietStart(), c.quietEnd());
        boolean severeOverride = peakCategory == AqiCategory.SEVERE && c.severeOverridesQuiet();
        if (quiet && !severeOverride) {
            return new AlertDecision.Suppress("quiet_hours", send);
        }
        return send;
    }

    /** Buckets a time into fixed {@code window-hours} blocks of the local day (e.g. 00-06, 06-12, ...). */
    public Instant windowStart(Instant ts) {
        ZonedDateTime z = ts.atZone(zone).truncatedTo(ChronoUnit.HOURS);
        int block = Math.max(1, props.windowHours());
        return z.withHour((z.getHour() / block) * block).toInstant();
    }

    private Trend trend(AlertContext.Point current, List<AlertContext.Point> future) {
        if (future.isEmpty()) {
            return Trend.STABLE;
        }
        int futureMax = future.stream().mapToInt(AlertContext.Point::aqi).max().orElse(current.aqi());
        int delta = futureMax - current.aqi();
        if (delta >= props.trendDelta()) {
            return Trend.WORSENING;
        }
        int futureLast = future.getLast().aqi();
        if (current.aqi() - futureLast >= props.trendDelta() && futureMax <= current.aqi()) {
            return Trend.IMPROVING;
        }
        return Trend.STABLE;
    }
}
