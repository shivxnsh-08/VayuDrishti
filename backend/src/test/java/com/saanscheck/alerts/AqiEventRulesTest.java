package com.saanscheck.alerts;

import static com.saanscheck.alerts.AqiEventRules.Action.BASELINE;
import static com.saanscheck.alerts.AqiEventRules.Action.EVENT;
import static com.saanscheck.alerts.AqiEventRules.Action.NONE;
import static com.saanscheck.alerts.AqiEventRules.Action.OBSERVED;
import static com.saanscheck.alerts.AqiEventRules.Action.REMINDER;
import static com.saanscheck.alerts.AqiEventRules.Action.SUPPRESSED;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.aqi.AqiCategory;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Every change-based alert rule, without I/O. Threshold: MODERATE unless stated. */
class AqiEventRulesTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    static final Duration STALE = Duration.ofHours(3);
    static final AlertEventProperties P = AlertEventProperties.DEFAULTS;   // 25 points, 8 h, SEVERE, bypass allowed
    static final AqiCategory T = AqiCategory.MODERATE;

    static AqiEventRules.Observation obs(int minutesAgo, int aqi, AqiCategory c) {
        return new AqiEventRules.Observation(NOW.minus(Duration.ofMinutes(minutesAgo)), aqi, c);
    }

    /** State: last processed observation 60 min ago; reference aqi/category; last event {@code eventHoursAgo}. */
    static AqiEventRules.State state(int refAqi, AqiCategory refCat, boolean above, double eventHoursAgo, Double reminderHoursAgo) {
        return new AqiEventRules.State("st", T, NOW.minus(Duration.ofMinutes(60)), refAqi, refAqi, refCat,
                NOW.minus(Duration.ofMinutes(60)), above, NOW.minus(Duration.ofMinutes((long) (eventHoursAgo * 60))),
                reminderHoursAgo == null ? null : NOW.minus(Duration.ofMinutes((long) (reminderHoursAgo * 60))),
                NOW.minus(Duration.ofDays(2)));
    }

    static AqiEventRules.Decision decide(AqiEventRules.State s, AqiEventRules.Observation o) {
        return decide(s, o, false, false);
    }

    static AqiEventRules.Decision decide(AqiEventRules.State s, AqiEventRules.Observation o, boolean quiet, boolean optIn) {
        return AqiEventRules.decide(s, o, "st", T, NOW, STALE, P, quiet, optIn);
    }

    @Test
    void thresholdCrossingFromBelowIsAnImmediateEvent() {
        var d = decide(state(90, AqiCategory.SATISFACTORY, false, 30, null), obs(10, 112, AqiCategory.MODERATE));
        assertThat(d.action()).isEqualTo(EVENT);
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_THRESHOLD_CROSSED);
        assertThat(d.previousAqi()).isEqualTo(90);
        assertThat(d.change()).isEqualTo(22);
    }

    @Test
    void significantDeteriorationWithinTheSameCategory() {
        var d = decide(state(120, AqiCategory.MODERATE, true, 1, null), obs(10, 146, AqiCategory.MODERATE));
        assertThat(d.action()).isEqualTo(EVENT);
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_DETERIORATION);
        assertThat(d.change()).isEqualTo(26);
        assertThat(d.reason()).isEqualTo("significant_increase");
    }

    @Test
    void exactlyTheConfiguredChangeCounts() {
        assertThat(decide(state(120, AqiCategory.MODERATE, true, 1, null), obs(10, 145, AqiCategory.MODERATE)).kind())
                .isEqualTo(AlertKind.AQI_DETERIORATION);
        assertThat(decide(state(145, AqiCategory.MODERATE, true, 1, null), obs(10, 120, AqiCategory.MODERATE)).kind())
                .isEqualTo(AlertKind.AQI_RECOVERY);
    }

    @Test
    void smallIncreaseIsNotAnEvent() {
        var d = decide(state(120, AqiCategory.MODERATE, true, 1, null), obs(10, 144, AqiCategory.MODERATE));
        assertThat(d.action()).isEqualTo(OBSERVED);
    }

    @Test
    void worseCategoryIsAnEventEvenForASmallDifference() {
        var d = decide(state(195, AqiCategory.MODERATE, true, 1, null), obs(10, 205, AqiCategory.POOR));
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_DETERIORATION);
        assertThat(d.reason()).isEqualTo("worse_category");
        assertThat(d.change()).isEqualTo(10);
    }

    @Test
    void significantImprovementWhileStillAboveIsARecovery() {
        var d = decide(state(180, AqiCategory.MODERATE, true, 1, null), obs(10, 150, AqiCategory.MODERATE));
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_RECOVERY);
        assertThat(d.change()).isEqualTo(-30);
        assertThat(d.reason()).isEqualTo("significant_decrease");
    }

    @Test
    void betterCategoryIsARecovery() {
        var d = decide(state(210, AqiCategory.POOR, true, 1, null), obs(10, 198, AqiCategory.MODERATE));
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_RECOVERY);
        assertThat(d.reason()).isEqualTo("better_category");
    }

    @Test
    void fallingBelowTheThresholdIsARecovery() {
        var d = decide(state(110, AqiCategory.MODERATE, true, 1, null), obs(10, 95, AqiCategory.SATISFACTORY));
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_RECOVERY);
        assertThat(d.reason()).isEqualTo("fell_below_threshold");
        assertThat(d.aboveNow()).isFalse();
    }

    @Test
    void changesEntirelyBelowTheThresholdAreNotNotified() {
        var d = decide(state(20, AqiCategory.GOOD, false, 30, null), obs(10, 90, AqiCategory.SATISFACTORY));
        assertThat(d.action()).isEqualTo(OBSERVED);
    }

    @Test
    void reminderAfterEightHoursOfStableAqiAboveTheThreshold() {
        assertThat(decide(state(150, AqiCategory.MODERATE, true, 8.0, null), obs(10, 155, AqiCategory.MODERATE)).action())
                .isEqualTo(REMINDER);
        // also without a new observation (the source publishes hourly; reminders do not wait for one)
        var s = state(150, AqiCategory.MODERATE, true, 8.5, null);
        var noNew = new AqiEventRules.Observation(s.lastObsTs(), 152, AqiCategory.MODERATE);
        assertThat(decide(s, noNew).action()).isEqualTo(REMINDER);
    }

    @Test
    void noReminderBeforeEightHours() {
        var d = decide(state(150, AqiCategory.MODERATE, true, 7.9, null), obs(10, 155, AqiCategory.MODERATE));
        assertThat(d.action()).isEqualTo(OBSERVED);
    }

    @Test
    void repeatedRemindersEveryEightHoursMeasuredFromTheLastReminder() {
        // event 20 h ago, reminder 12 h ago -> next due (8 h after the reminder)
        assertThat(decide(state(150, AqiCategory.MODERATE, true, 20, 12.0), obs(10, 150, AqiCategory.MODERATE)).action())
                .isEqualTo(REMINDER);
        // event 20 h ago, reminder 3 h ago -> not yet
        assertThat(decide(state(150, AqiCategory.MODERATE, true, 20, 3.0), obs(10, 150, AqiCategory.MODERATE)).action())
                .isEqualTo(OBSERVED);
    }

    @Test
    void noReminderBelowTheThreshold() {
        var d = decide(state(40, AqiCategory.GOOD, false, 20, null), obs(10, 45, AqiCategory.GOOD));
        assertThat(d.action()).isEqualTo(OBSERVED);
    }

    @Test
    void sameObservationIsNeverProcessedTwice() {
        var s = state(120, AqiCategory.MODERATE, true, 1, null);
        var already = new AqiEventRules.Observation(s.lastObsTs(), 190, AqiCategory.MODERATE);   // big change, but seen
        assertThat(decide(s, already).action()).isEqualTo(NONE);
    }

    @Test
    void staleObservationsNeverProduceEventsOrReminders() {
        var d = decide(state(120, AqiCategory.MODERATE, true, 20, null), obs(181, 300, AqiCategory.POOR));
        assertThat(d.action()).isEqualTo(NONE);
        assertThat(d.reason()).isEqualTo("stale_observation");
        assertThat(decide(null, obs(200, 300, AqiCategory.POOR)).action()).isEqualTo(NONE);
    }

    @Test
    void firstObservationIsABaselineBelowAndACrossingAbove() {
        assertThat(decide(null, obs(10, 80, AqiCategory.SATISFACTORY)).action()).isEqualTo(BASELINE);
        var above = decide(null, obs(10, 160, AqiCategory.MODERATE));
        assertThat(above.kind()).isEqualTo(AlertKind.AQI_THRESHOLD_CROSSED);
        assertThat(above.previousAqi()).isNull();
    }

    @Test
    void thresholdChangeReBaselinesSilentlyUnlessNowAbove() {
        var s = new AqiEventRules.State("st", AqiCategory.POOR, NOW.minus(Duration.ofHours(1)), 150, 150,
                AqiCategory.MODERATE, NOW.minus(Duration.ofHours(1)), false, null, null, NOW.minus(Duration.ofDays(1)));
        // user lowered the level from POOR to MODERATE while AQI is 160: announce
        assertThat(decide(s, obs(10, 160, AqiCategory.MODERATE)).kind()).isEqualTo(AlertKind.AQI_THRESHOLD_CROSSED);
        // user raised the level so the air is now below it: silent re-baseline, no "improved" message
        var above = new AqiEventRules.State("st", AqiCategory.GOOD, NOW.minus(Duration.ofHours(1)), 150, 150,
                AqiCategory.MODERATE, NOW.minus(Duration.ofHours(1)), true, null, null, NOW.minus(Duration.ofDays(1)));
        var d = AqiEventRules.decide(above, obs(10, 150, AqiCategory.MODERATE), "st", AqiCategory.POOR, NOW, STALE, P,
                false, false);
        assertThat(d.action()).isEqualTo(BASELINE);
    }

    @Test
    void stationChangeStartsANewBaseline() {
        var d = AqiEventRules.decide(state(300, AqiCategory.VERY_POOR, true, 1, null), obs(10, 60, AqiCategory.SATISFACTORY),
                "other-station", T, NOW, STALE, P, false, false);
        assertThat(d.action()).isEqualTo(BASELINE);
    }

    @Test
    void quietHoursSuppressEventsAndHoldReminders() {
        var d = decide(state(120, AqiCategory.MODERATE, true, 1, null), obs(10, 230, AqiCategory.POOR), true, true);
        assertThat(d.action()).isEqualTo(SUPPRESSED);
        assertThat(d.reason()).contains("quiet_hours");
        var reminder = decide(state(150, AqiCategory.MODERATE, true, 9, null), obs(10, 150, AqiCategory.MODERATE), true, true);
        assertThat(reminder.action()).isEqualTo(NONE);
        assertThat(reminder.reason()).isEqualTo("reminder_waits_for_quiet_hours");
        var recovery = decide(state(110, AqiCategory.MODERATE, true, 1, null), obs(10, 95, AqiCategory.SATISFACTORY), true, true);
        assertThat(recovery.action()).isEqualTo(SUPPRESSED);   // routine updates respect quiet hours
    }

    @Test
    void criticalAlertsBypassQuietHoursOnlyWithPolicyAndOptIn() {
        var s = state(300, AqiCategory.VERY_POOR, true, 1, null);
        var severe = obs(10, 420, AqiCategory.SEVERE);
        assertThat(decide(s, severe, true, true).action()).isEqualTo(EVENT);
        assertThat(decide(s, severe, true, true).critical()).isTrue();
        assertThat(decide(s, severe, true, false).action()).isEqualTo(SUPPRESSED);   // user did not opt in
        var noBypass = new AlertEventProperties(25, Duration.ofHours(8), AqiCategory.SEVERE, false);
        assertThat(AqiEventRules.decide(s, severe, "st", T, NOW, STALE, noBypass, true, true).action())
                .isEqualTo(SUPPRESSED);                                              // global policy off
        assertThat(decide(s, obs(10, 380, AqiCategory.VERY_POOR), true, true).action()).isEqualTo(SUPPRESSED);   // not critical
    }

    @Test
    void significantChangeIsConfigurable() {
        var strict = new AlertEventProperties(10, Duration.ofHours(8), AqiCategory.SEVERE, true);
        var d = AqiEventRules.decide(state(120, AqiCategory.MODERATE, true, 1, null), obs(10, 131, AqiCategory.MODERATE),
                "st", T, NOW, STALE, strict, false, false);
        assertThat(d.kind()).isEqualTo(AlertKind.AQI_DETERIORATION);
    }
}
