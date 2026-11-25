package com.saanscheck.advice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saanscheck.alerts.AlertKind;
import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.briefing.BriefingProperties;
import com.saanscheck.profile.HealthProfile;
import com.saanscheck.profile.Profile;
import com.saanscheck.stations.Station;
import com.saanscheck.telegram.TelegramHtml;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every Telegram message type: valid Telegram HTML, escaped dynamic text, explicit times, honest optional fields. */
class TelegramMessagesTest {

    static final Instant OBS = Instant.parse("2026-11-24T11:30:00Z");        // 17:00 IST
    static final Instant NOW = Instant.parse("2026-11-24T12:15:00Z");
    static final Station STATION = new Station("st", "Lalbagh <North> & Co", "Lucknow", "Uttar Pradesh", 0, 0, true);

    AlertMessageComposer composer;
    TelegramMessages messages;
    Profile en;
    Profile hi;

    @BeforeEach
    void setUp() {
        composer = mock(AlertMessageComposer.class);
        when(composer.adviceFor(any(), any(), anyInt(), any())).thenReturn("Limit outdoor exertion & wear a mask <N95>.");
        when(composer.categoryLabel(any(), any())).thenAnswer(inv -> switch ((AqiCategory) inv.getArgument(1)) {
            case GOOD -> "Good"; case SATISFACTORY -> "Satisfactory"; case MODERATE -> "Moderate";
            case POOR -> "Poor"; case VERY_POOR -> "Very Poor"; case SEVERE -> "Severe";
        });
        AlertProperties alerts = new AlertProperties(true, "Asia/Kolkata", null, null, 24, 6, null, null, 5, null, null, 25, null);
        messages = new TelegramMessages(composer, new BriefingProperties(true, null, "http://localhost:5173"), alerts);
        en = new Profile(1, HealthProfile.ASTHMA, null, null, null, "st", "en", null, null, null, false, "1");
        hi = new Profile(1, HealthProfile.ASTHMA, null, null, null, "st", "hi", null, null, null, false, "1");
    }

    TelegramMessages.Reading reading(int aqi, AqiCategory c, String pollutant) {
        return new TelegramMessages.Reading(OBS, aqi, c, pollutant);
    }

    @Test
    void deteriorationMessageHasAllSectionsAndIsValidHtml() {
        String m = messages.aqiEvent(en, STATION, AlertKind.AQI_DETERIORATION, reading(215, AqiCategory.POOR, "PM25"),
                173, 42, Instant.parse("2026-11-24T10:30:00Z"), AqiCategory.MODERATE, NOW);
        assertThat(TelegramHtml.isValid(m)).as(m).isTrue();
        assertThat(m).startsWith("🚨 <b>VayuDrishti | AQI ALERT</b>\n" + TelegramHtml.DIVIDER)
                .contains("⚠️ <b>Air Quality Has Deteriorated</b>")
                .contains("📍 <b>Location:</b> Lucknow, Uttar Pradesh")
                .contains("📡 <b>Monitoring station:</b> Lalbagh &lt;North&gt; &amp; Co")      // escaped
                .contains("🌫️ <b>Current AQI:</b> <b>215</b>")
                .contains("🔴 <b>Category:</b> Poor")
                .contains("📈 <b>Change:</b> +42 points (from 173 at 16:00 IST)")
                .contains("🕒 <b>Observation time:</b> 24 Nov 2026, 17:00 IST")
                .contains("🕐 <b>Data age:</b> 45 min (when this message was prepared)")
                .contains("<b>Health advisory</b>\nLimit outdoor exertion &amp; wear a mask &lt;N95&gt;.")
                .contains("• Main pollutant: PM2.5")
                .contains("<a href=\"http://localhost:5173/forecast?station=st\">Open VayuDrishti Dashboard</a>")
                .endsWith(TelegramHtml.TAGLINE)
                .doesNotContain("Concentration");                                        // not available: not shown
    }

    @Test
    void thresholdCrossingShowsTheAlertLevel() {
        String m = messages.aqiEvent(en, STATION, AlertKind.AQI_THRESHOLD_CROSSED, reading(160, AqiCategory.MODERATE, null),
                null, null, null, AqiCategory.MODERATE, NOW);
        assertThat(TelegramHtml.isValid(m)).isTrue();
        assertThat(m).contains("Air Quality Has Reached Your Alert Level").contains("🎯 <b>Your alert level:</b> Moderate and worse")
                .doesNotContain("Change:").doesNotContain("Pollutant information");     // unknown pollutant omitted
    }

    @Test
    void recoveryUsesTheDistinctPositiveFormat() {
        String improved = messages.aqiEvent(en, STATION, AlertKind.AQI_RECOVERY, reading(150, AqiCategory.MODERATE, "PM10"),
                180, -30, OBS.minusSeconds(7200), AqiCategory.MODERATE, NOW);
        assertThat(TelegramHtml.isValid(improved)).isTrue();
        assertThat(improved).startsWith("🌿 <b>VayuDrishti | AIR QUALITY UPDATE</b>").contains("🟢 <b>Air Quality Has Improved</b>")
                .contains("📉 <b>Improvement:</b> 30 points (from 180").contains("<b>Health update</b>")
                .contains("View Live Air Quality");
        String below = messages.aqiEvent(en, STATION, AlertKind.AQI_RECOVERY, reading(90, AqiCategory.SATISFACTORY, "PM10"),
                120, -30, OBS, AqiCategory.MODERATE, NOW);
        assertThat(below).contains("Air Quality Is Back Below Your Alert Level");
    }

    @Test
    void stabilityReminderFormat() {
        String m = messages.aqiEvent(en, STATION, AlertKind.AQI_STABILITY_REMINDER, reading(158, AqiCategory.MODERATE, "PM25"),
                158, 0, OBS, AqiCategory.GOOD, NOW);
        assertThat(TelegramHtml.isValid(m)).isTrue();
        assertThat(m).startsWith("🔔 <b>VayuDrishti | AQI REMINDER</b>").contains("Air Quality Remains Above Your Alert Level")
                .contains("🕒 <b>Last observation:</b>").contains("above your configured alert threshold for at least 8 hours")
                .contains("Check Live AQI").doesNotContain("Change:");
    }

    @Test
    void forecastAlertIsLabelledBaselineUnlessAPromotedModel() {
        String baseline = messages.forecastAlert(en, STATION, 320, AqiCategory.VERY_POOR, OBS.plusSeconds(3 * 3600),
                "BASELINE_PERSISTENCE", "baseline", OBS, 150, NOW);
        assertThat(TelegramHtml.isValid(baseline)).isTrue();
        assertThat(baseline).contains("FORECAST ALERT").contains("baseline estimate (not an ML model)")
                .contains("Expected around:</b> 24 Nov 2026, 20:00 IST").contains("Latest reading: 150 (observed 24 Nov 2026, 17:00 IST)");
        String model = messages.forecastAlert(en, STATION, 320, AqiCategory.VERY_POOR, OBS, "MODEL", "lgbm-v1", null, null, NOW);
        assertThat(model).contains("ML model lgbm-v1").doesNotContain("baseline");
    }

    @Test
    void staleNoticeNeverPresentsOldDataAsCurrent() {
        String m = messages.staleNotice(en, STATION, OBS.minusSeconds(6 * 3600), NOW);
        assertThat(TelegramHtml.isValid(m)).isTrue();
        assertThat(m).contains("No Recent Data From Your Station").contains("6 h 45 min").contains("paused until data resumes")
                .doesNotContain("Health advisory");
    }

    @Test
    void testMessageShowsSettings() {
        String m = messages.testMessage(en, "Lalbagh & Co", true, false, LocalTime.of(7, 0));
        assertThat(TelegramHtml.isValid(m)).isTrue();
        assertThat(m).startsWith("✅ <b>VayuDrishti Telegram Connection Successful</b>")
                .contains("Monitoring station: Lalbagh &amp; Co").contains("AQI alerts: Enabled")
                .contains("Morning briefing: Disabled").doesNotContain("Morning delivery")
                .contains("<i>This is a test message. No AQI event was triggered.</i>");
        assertThat(messages.testMessage(en, null, false, true, LocalTime.of(6, 30)))
                .contains("not selected yet").contains("Morning delivery: 06:30 IST");
    }

    @Test
    void hindiMessagesAreValidToo() {
        for (AlertKind k : new AlertKind[] {AlertKind.AQI_THRESHOLD_CROSSED, AlertKind.AQI_DETERIORATION,
                AlertKind.AQI_RECOVERY, AlertKind.AQI_STABILITY_REMINDER}) {
            String m = messages.aqiEvent(hi, STATION, k, reading(215, AqiCategory.POOR, "PM25"), 180, 35, OBS,
                    AqiCategory.MODERATE, NOW);
            assertThat(TelegramHtml.isValid(m)).as(k.name()).isTrue();
        }
        assertThat(messages.aqiEvent(hi, STATION, AlertKind.AQI_DETERIORATION, reading(215, AqiCategory.POOR, "PM25"),
                180, 35, OBS, AqiCategory.MODERATE, NOW)).contains("वायु गुणवत्ता बिगड़ी है");
    }

    @Test
    void hugeAdviceStillFitsTelegramsLimit() {
        when(composer.adviceFor(any(), any(), anyInt(), any())).thenReturn("Stay indoors & rest. ".repeat(1000));
        String m = messages.aqiEvent(en, STATION, AlertKind.AQI_DETERIORATION, reading(420, AqiCategory.SEVERE, "PM25"),
                300, 120, OBS, AqiCategory.MODERATE, NOW);
        assertThat(m.length()).isLessThan(4096);
        assertThat(TelegramHtml.isValid(m)).isTrue();
    }
}
