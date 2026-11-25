package com.saanscheck.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.TestFixtures;
import com.saanscheck.aqi.AqiCalculator;
import com.saanscheck.aqi.AqiCategory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertDecisionEngineTest {

    // 12:00 IST
    private static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");

    private final AlertDecisionEngine engine =
            new AlertDecisionEngine(TestFixtures.alertProps(), new AqiCalculator(TestFixtures.aqiProps(true)));

    private static List<AlertContext.Point> forecast(int... aqis) {
        List<AlertContext.Point> pts = new ArrayList<>();
        for (int h = 0; h < aqis.length; h++) {
            pts.add(new AlertContext.Point(NOW.plus(Duration.ofHours(h + 1)), aqis[h]));
        }
        return pts;
    }

    private static AlertContext ctx(AqiCategory min, int currentAqi, List<AlertContext.Point> fc,
                                    AlertContext.LastAlert last, LocalTime qs, LocalTime qe, boolean severeOverride) {
        return new AlertContext(1, "ito", min, qs, qe, severeOverride, NOW.minus(Duration.ofMinutes(30)), currentAqi,
                fc != null, fc == null ? List.of() : fc, last, NOW);
    }

    @Test
    void belowThresholdDoesNothing() {
        var d = engine.decide(ctx(AqiCategory.POOR, 150, forecast(160, 170, 180), null, null, null, false));
        assertThat(d).isInstanceOf(AlertDecision.None.class);
    }

    @Test
    void forecastCrossingTriggersForecastAlert() {
        var d = engine.decide(ctx(AqiCategory.POOR, 150, forecast(170, 230, 310, 250), null, null, null, false));
        assertThat(d).isInstanceOf(AlertDecision.Send.class);
        var s = (AlertDecision.Send) d;
        assertThat(s.kind()).isEqualTo(AlertKind.FORECAST);
        assertThat(s.category()).isEqualTo(AqiCategory.VERY_POOR);
        assertThat(s.peakAqi()).isEqualTo(310);
        assertThat(s.trend()).isEqualTo(Trend.WORSENING);
        // first crossing at 14:00 IST -> 12:00-18:00 block starts 12:00 IST = 06:30Z
        assertThat(s.windowStart()).isEqualTo(Instant.parse("2026-11-20T06:30:00Z"));
    }

    @Test
    void sensitiveProfileTriggersEarlier() {
        var d = engine.decide(ctx(AqiCategory.MODERATE, 120, forecast(130, 140), null, null, null, false));
        assertThat(d).isInstanceOf(AlertDecision.Send.class);
    }

    @Test
    void forecastUnavailableFallsBackToCurrentAqi() {
        var d = engine.decide(ctx(AqiCategory.POOR, 260, null, null, null, null, false));
        assertThat(d).isInstanceOf(AlertDecision.Send.class);
        assertThat(((AlertDecision.Send) d).kind()).isEqualTo(AlertKind.CURRENT);

        var quiet = engine.decide(ctx(AqiCategory.POOR, 150, null, null, null, null, false));
        assertThat(quiet).isInstanceOf(AlertDecision.None.class);
    }

    @Test
    void staleDataGivesNoticeNotForecastAlert() {
        var c = new AlertContext(1, "ito", AqiCategory.POOR, null, null, false,
                NOW.minus(Duration.ofHours(5)), 400, true, forecast(450, 460), null, NOW);
        assertThat(engine.decide(c)).isInstanceOf(AlertDecision.StaleNotice.class);

        var none = new AlertContext(1, "ito", AqiCategory.POOR, null, null, false,
                null, null, false, List.of(), null, NOW);
        assertThat(engine.decide(none)).isInstanceOf(AlertDecision.StaleNotice.class);
    }

    @Test
    void cooldownSuppressesSameCategoryButEscalationPassesThrough() {
        var recent = new AlertContext.LastAlert(AqiCategory.POOR, NOW.minus(Duration.ofHours(2)));
        var same = engine.decide(ctx(AqiCategory.POOR, 150, forecast(250, 260), recent, null, null, false));
        assertThat(same).isInstanceOf(AlertDecision.Suppress.class);
        assertThat(((AlertDecision.Suppress) same).reason()).isEqualTo("cooldown");

        var worse = engine.decide(ctx(AqiCategory.POOR, 150, forecast(250, 350), recent, null, null, false));
        assertThat(worse).isInstanceOf(AlertDecision.Send.class);

        var old = new AlertContext.LastAlert(AqiCategory.POOR, NOW.minus(Duration.ofHours(7)));
        var afterCooldown = engine.decide(ctx(AqiCategory.POOR, 150, forecast(250, 260), old, null, null, false));
        assertThat(afterCooldown).isInstanceOf(AlertDecision.Send.class);
    }

    @Test
    void quietHoursSuppressUnlessSevereAndOptedIn() {
        LocalTime qs = LocalTime.of(11, 0);
        LocalTime qe = LocalTime.of(13, 0);   // NOW is 12:00 IST
        var poor = engine.decide(ctx(AqiCategory.POOR, 150, forecast(250), null, qs, qe, true));
        assertThat(poor).isInstanceOf(AlertDecision.Suppress.class);
        assertThat(((AlertDecision.Suppress) poor).reason()).isEqualTo("quiet_hours");

        var severeNoOptIn = engine.decide(ctx(AqiCategory.POOR, 150, forecast(450), null, qs, qe, false));
        assertThat(severeNoOptIn).isInstanceOf(AlertDecision.Suppress.class);

        var severeOptIn = engine.decide(ctx(AqiCategory.POOR, 150, forecast(450), null, qs, qe, true));
        assertThat(severeOptIn).isInstanceOf(AlertDecision.Send.class);
    }

    @Test
    void forecastBeyondHorizonIsIgnored() {
        List<AlertContext.Point> fc = List.of(new AlertContext.Point(NOW.plus(Duration.ofHours(30)), 450));
        var d = engine.decide(ctx(AqiCategory.POOR, 150, fc, null, null, null, false));
        assertThat(d).isInstanceOf(AlertDecision.None.class);
    }

    @Test
    void windowBucketsAreStableWithinBlock() {
        Instant a = Instant.parse("2026-11-20T07:30:00Z");   // 13:00 IST
        Instant b = Instant.parse("2026-11-20T11:29:00Z");   // 16:59 IST
        Instant c = Instant.parse("2026-11-20T12:30:00Z");   // 18:00 IST -> next block
        assertThat(engine.windowStart(a)).isEqualTo(engine.windowStart(b));
        assertThat(engine.windowStart(c)).isNotEqualTo(engine.windowStart(a));
    }
}
