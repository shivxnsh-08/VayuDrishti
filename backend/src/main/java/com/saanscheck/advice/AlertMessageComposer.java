package com.saanscheck.advice;

import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.profile.Profile;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the final Telegram text. The headline (station, AQI, time) is formatted here in code,
 * so the numbers in it are always the system's numbers. The advice paragraph comes from the
 * Python service (rules decide, LLM only phrases); if that service fails, a fixed template is used.
 */
@Component
public class AlertMessageComposer {

    private static final Logger log = LoggerFactory.getLogger(AlertMessageComposer.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private static final Map<AqiCategory, String> HI = Map.of(
            AqiCategory.GOOD, "अच्छा", AqiCategory.SATISFACTORY, "संतोषजनक", AqiCategory.MODERATE, "मध्यम",
            AqiCategory.POOR, "खराब", AqiCategory.VERY_POOR, "बहुत खराब", AqiCategory.SEVERE, "गंभीर");
    private static final Map<AqiCategory, String> EN = Map.of(
            AqiCategory.GOOD, "Good", AqiCategory.SATISFACTORY, "Satisfactory", AqiCategory.MODERATE, "Moderate",
            AqiCategory.POOR, "Poor", AqiCategory.VERY_POOR, "Very Poor", AqiCategory.SEVERE, "Severe");

    static final String DISCLAIMER_EN = "General guidance only, not medical advice.";
    static final String DISCLAIMER_HI = "यह केवल सामान्य जानकारी है, चिकित्सा सलाह नहीं।";

    private final AdviceClient advice;
    private final ZoneId zone;

    public AlertMessageComposer(AdviceClient advice, AlertProperties props) {
        this.advice = advice;
        this.zone = ZoneId.of(props.zone());
    }



    /** Health advice for a reading, from the same advice service/templates as alerts (used by the morning briefing). */
    public String adviceFor(Profile profile, AqiCategory category, int aqi, Instant observedAt) {
        boolean hi = "hi".equals(profile.language());
        return adviceText(profile, category, "STABLE", aqi, TIME.format(observedAt.atZone(zone)), hi);
    }

    /** Category label in the profile's language (same labels as alerts). */
    public String categoryLabel(Profile profile, AqiCategory category) {
        return ("hi".equals(profile.language()) ? HI : EN).get(category);
    }

    private String adviceText(Profile profile, AqiCategory category, String trend, int aqi, String time, boolean hi) {
        try {
            AdviceDtos.AdviceResponse r = advice.advice(new AdviceDtos.AdviceRequest(
                    category.name(), trend, profile.healthProfile().name(),
                    hi ? "hi" : "en", Map.of("aqi", String.valueOf(aqi), "time", time)));
            if (r != null && r.message() != null && !r.message().isBlank()) {
                return r.message();
            }
        } catch (RuntimeException e) {
            log.warn("Advice service unavailable, using template: {}", e.toString());
        }
        return hi
                ? "विस्तृत सलाह अभी उपलब्ध नहीं है; कृपया बाद में फिर से देखें।\n" + DISCLAIMER_HI
                : "Detailed advice is temporarily unavailable; please check again later.\n" + DISCLAIMER_EN;
    }
}
