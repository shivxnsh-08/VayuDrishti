package com.saanscheck.advice;

import static com.saanscheck.telegram.TelegramHtml.DIVIDER;
import static com.saanscheck.telegram.TelegramHtml.TAGLINE;
import static com.saanscheck.telegram.TelegramHtml.clip;
import static com.saanscheck.telegram.TelegramHtml.dot;
import static com.saanscheck.telegram.TelegramHtml.esc;
import static com.saanscheck.telegram.TelegramHtml.link;

import com.saanscheck.alerts.AlertKind;
import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.briefing.BriefingProperties;
import com.saanscheck.profile.Profile;
import com.saanscheck.stations.Station;
import com.saanscheck.telegram.TelegramHtml;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Professional Telegram-HTML messages for every VayuDrishti notification. All dynamic text is escaped; advice
 * text from the advice service is clipped so a message always fits Telegram's limit. Times are shown in the alert
 * time zone (Asia/Kolkata) with the zone name; observation time and data age are always explicit (Telegram itself
 * shows the delivery time). Nothing is invented: optional fields are simply left out when unknown.
 */
@Component
public class TelegramMessages {

    static final int MAX_ADVICE = 1200;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH);
    private static final Map<String, String> POLLUTANTS = Map.of("PM25", "PM2.5", "PM10", "PM10", "NO2", "NO₂",
            "SO2", "SO₂", "O3", "O₃", "CO", "CO", "NH3", "NH₃");

    private final AlertMessageComposer composer;
    private final String appUrl;
    private final ZoneId zone;

    public TelegramMessages(AlertMessageComposer composer, BriefingProperties briefing, AlertProperties alerts) {
        this.composer = composer;
        this.appUrl = briefing.appUrl();
        this.zone = ZoneId.of(alerts.zone());
    }

    /** The reading an event is about (genuine, from station_aqi). */
    public record Reading(Instant observedAt, int aqi, AqiCategory category, String dominantPollutant) {
    }

    // ---- AQI events -----------------------------------------------------------------------------------------

    public String aqiEvent(Profile p, Station station, AlertKind kind, Reading r, Integer previousAqi, Integer change,
                           Instant referenceAt, AqiCategory threshold, Instant now) {
        boolean hi = hi(p);
        String label = composer.categoryLabel(p, r.category());
        StringBuilder m = new StringBuilder();
        switch (kind) {
            case AQI_THRESHOLD_CROSSED -> m.append("🚨 <b>VayuDrishti | ").append(hi ? "AQI अलर्ट" : "AQI ALERT").append("</b>\n")
                    .append(DIVIDER).append("\n\n⚠️ <b>")
                    .append(hi ? "वायु गुणवत्ता आपके अलर्ट स्तर तक पहुँच गई है" : "Air Quality Has Reached Your Alert Level")
                    .append("</b>\n\n");
            case AQI_DETERIORATION -> m.append("🚨 <b>VayuDrishti | ").append(hi ? "AQI अलर्ट" : "AQI ALERT").append("</b>\n")
                    .append(DIVIDER).append("\n\n⚠️ <b>").append(hi ? "वायु गुणवत्ता बिगड़ी है" : "Air Quality Has Deteriorated")
                    .append("</b>\n\n");
            case AQI_RECOVERY -> m.append("🌿 <b>VayuDrishti | ").append(hi ? "वायु गुणवत्ता अपडेट" : "AIR QUALITY UPDATE")
                    .append("</b>\n").append(DIVIDER).append("\n\n🟢 <b>")
                    .append(r.category().isAtLeast(threshold)
                            ? (hi ? "वायु गुणवत्ता में सुधार हुआ है" : "Air Quality Has Improved")
                            : (hi ? "वायु गुणवत्ता आपके अलर्ट स्तर से नीचे आ गई है" : "Air Quality Is Back Below Your Alert Level"))
                    .append("</b>\n\n");
            case AQI_STABILITY_REMINDER -> m.append("🔔 <b>VayuDrishti | ").append(hi ? "AQI रिमाइंडर" : "AQI REMINDER")
                    .append("</b>\n").append(DIVIDER).append("\n\n⚠️ <b>")
                    .append(hi ? "वायु गुणवत्ता अभी भी आपके अलर्ट स्तर पर है" : "Air Quality Remains Above Your Alert Level")
                    .append("</b>\n\n");
            default -> throw new IllegalArgumentException("not an AQI event: " + kind);
        }
        location(m, station, hi);
        m.append("🌫️ <b>").append(hi ? "वर्तमान AQI" : "Current AQI").append(":</b> <b>").append(r.aqi()).append("</b>\n")
                .append(dot(r.category())).append(" <b>").append(hi ? "श्रेणी" : "Category").append(":</b> ")
                .append(esc(label)).append("\n");
        if (change != null && previousAqi != null && kind != AlertKind.AQI_STABILITY_REMINDER) {
            boolean up = change > 0;
            m.append(change == 0 ? "📊 " : up ? "📈 " : "📉 ").append("<b>")
                    .append(kind == AlertKind.AQI_RECOVERY ? (hi ? "सुधार" : "Improvement") : (hi ? "बदलाव" : "Change"))
                    .append(":</b> ")
                    .append(kind == AlertKind.AQI_RECOVERY ? Math.abs(change) + (hi ? " अंक" : " points")
                            : (up ? "+" : "") + change + (hi ? " अंक" : " points"))
                    .append(" (").append(hi ? "पिछला" : "from").append(" ").append(previousAqi);
            if (referenceAt != null) {
                m.append(hi ? ", " : " at ").append(esc(TIME.format(referenceAt.atZone(zone))));
            }
            m.append(")\n");
        }
        if (kind == AlertKind.AQI_THRESHOLD_CROSSED || kind == AlertKind.AQI_STABILITY_REMINDER) {
            m.append("🎯 <b>").append(hi ? "आपका अलर्ट स्तर" : "Your alert level").append(":</b> ")
                    .append(esc(composer.categoryLabel(p, threshold))).append(hi ? " और उससे खराब" : " and worse").append("\n");
        }
        observed(m, r.observedAt(), now, hi, kind == AlertKind.AQI_STABILITY_REMINDER);
        if (kind == AlertKind.AQI_STABILITY_REMINDER) {
            m.append("\n").append(hi ? "पिछले 8 घंटों से AQI आपके चुने हुए अलर्ट स्तर से ऊपर बना हुआ है।"
                    : "The air quality has stayed above your configured alert threshold for at least 8 hours.").append("\n");
        }
        m.append("\n<b>").append(kind == AlertKind.AQI_RECOVERY ? (hi ? "स्वास्थ्य अपडेट" : "Health update")
                : (hi ? "स्वास्थ्य सलाह" : "Health advisory")).append("</b>\n")
                .append(clip(composer.adviceFor(p, r.category(), r.aqi(), r.observedAt()), MAX_ADVICE)).append("\n");
        pollutant(m, r.dominantPollutant(), hi);
        footer(m, station.id(), kind == AlertKind.AQI_RECOVERY ? (hi ? "लाइव वायु गुणवत्ता देखें" : "View Live Air Quality")
                : kind == AlertKind.AQI_STABILITY_REMINDER ? (hi ? "लाइव AQI देखें" : "Check Live AQI")
                : (hi ? "VayuDrishti डैशबोर्ड खोलें" : "Open VayuDrishti Dashboard"));
        return TelegramHtml.fit(m.toString());
    }

    // ---- forecast alert (existing forecast rules) -------------------------------------------------------------

    public String forecastAlert(Profile p, Station station, int peakAqi, AqiCategory category, Instant peakAt,
                                String method, String modelVersion, Instant currentObservedAt, Integer currentAqi,
                                Instant now) {
        boolean hi = hi(p);
        boolean model = "MODEL".equals(method);
        StringBuilder m = new StringBuilder("🔮 <b>VayuDrishti | ").append(hi ? "पूर्वानुमान अलर्ट" : "FORECAST ALERT")
                .append("</b>\n").append(DIVIDER).append("\n\n⚠️ <b>")
                .append(hi ? "वायु गुणवत्ता बिगड़ने की संभावना है" : "Air Quality Is Expected to Worsen").append("</b>\n\n");
        location(m, station, hi);
        m.append("🔮 <b>").append(hi ? "अनुमानित AQI" : "Forecast AQI").append(":</b> <b>").append(peakAqi).append("</b>\n")
                .append(dot(category)).append(" <b>").append(hi ? "श्रेणी" : "Category").append(":</b> ")
                .append(esc(composer.categoryLabel(p, category))).append("\n")
                .append("🕒 <b>").append(hi ? "लगभग" : "Expected around").append(":</b> ")
                .append(esc(WHEN.format(peakAt.atZone(zone)))).append("\n")
                .append("🧮 <b>").append(hi ? "पूर्वानुमान" : "Forecast").append(":</b> ")
                .append(model ? esc((hi ? "ML मॉडल " : "ML model ") + modelVersion)
                        : (hi ? "बेसलाइन अनुमान (ML मॉडल नहीं)" : "baseline estimate (not an ML model)")).append("\n");
        if (currentAqi != null && currentObservedAt != null) {
            m.append("🌫️ ").append(hi ? "अभी का AQI" : "Latest reading").append(": ").append(currentAqi).append(" (")
                    .append(hi ? "मापा गया " : "observed ").append(esc(WHEN.format(currentObservedAt.atZone(zone)))).append(")\n");
        }
        m.append("\n<b>").append(hi ? "स्वास्थ्य सलाह" : "Health advisory").append("</b>\n")
                .append(clip(composer.adviceFor(p, category, peakAqi, peakAt), MAX_ADVICE)).append("\n");
        footer(m, station.id(), hi ? "VayuDrishti डैशबोर्ड खोलें" : "Open VayuDrishti Dashboard");
        return TelegramHtml.fit(m.toString());
    }

    // ---- stale data ------------------------------------------------------------------------------------------

    public String staleNotice(Profile p, Station station, Instant lastObservedAt, Instant now) {
        boolean hi = hi(p);
        StringBuilder m = new StringBuilder("📡 <b>VayuDrishti | ").append(hi ? "डेटा सूचना" : "DATA NOTICE").append("</b>\n")
                .append(DIVIDER).append("\n\n⏸️ <b>").append(hi ? "स्टेशन से नया डेटा नहीं आ रहा" : "No Recent Data From Your Station")
                .append("</b>\n\n");
        location(m, station, hi);
        if (lastObservedAt != null) {
            m.append("🕒 <b>").append(hi ? "अंतिम रीडिंग" : "Last reading").append(":</b> ")
                    .append(esc(WHEN.format(lastObservedAt.atZone(zone)))).append(" (")
                    .append(age(Duration.between(lastObservedAt, now), hi)).append(")\n");
        } else {
            m.append(hi ? "इस स्टेशन से अभी तक कोई रीडिंग नहीं मिली।" : "This station has not reported any reading yet.").append("\n");
        }
        m.append("\n").append(hi ? "पुरानी रीडिंग को वर्तमान नहीं माना जाता, इसलिए डेटा लौटने तक AQI अलर्ट और सलाह रुकी हुई हैं।"
                : "Old readings are not treated as current, so AQI alerts and advice are paused until data resumes.")
                .append("\n");
        footer(m, station.id(), hi ? "लाइव स्थिति देखें" : "Check Live Status");
        return TelegramHtml.fit(m.toString());
    }

    // ---- test message ----------------------------------------------------------------------------------------

    public String testMessage(Profile p, String stationName, boolean alertsEnabled, boolean briefingEnabled,
                              LocalTime briefingTime) {
        boolean hi = p != null && hi(p);
        String on = hi ? "चालू" : "Enabled";
        String off = hi ? "बंद" : "Disabled";
        return TelegramHtml.fit("✅ <b>" + (hi ? "VayuDrishti Telegram कनेक्शन सफल" : "VayuDrishti Telegram Connection Successful")
                + "</b>\n" + DIVIDER + "\n\n"
                + (hi ? "आपका Telegram खाता जुड़ा है और AQI सूचनाएँ पाने के लिए तैयार है।"
                        : "Your Telegram account is connected and ready to receive AQI notifications.") + "\n\n"
                + "📍 " + (hi ? "मॉनिटरिंग स्टेशन" : "Monitoring station") + ": "
                + (stationName == null ? (hi ? "चुना नहीं गया" : "not selected yet") : esc(stationName)) + "\n"
                + "🔔 " + (hi ? "AQI अलर्ट" : "AQI alerts") + ": " + (alertsEnabled ? on : off) + "\n"
                + "🌅 " + (hi ? "सुबह का सारांश" : "Morning briefing") + ": " + (briefingEnabled ? on : off) + "\n"
                + (briefingEnabled && briefingTime != null
                        ? "🕒 " + (hi ? "सुबह का समय" : "Morning delivery") + ": " + briefingTime + " IST (Asia/Kolkata)\n" : "")
                + "\n<i>" + (hi ? "यह एक परीक्षण संदेश है। कोई AQI घटना ट्रिगर नहीं हुई।"
                        : "This is a test message. No AQI event was triggered.") + "</i>");
    }

    // ---- shared pieces ---------------------------------------------------------------------------------------

    private void location(StringBuilder m, Station station, boolean hi) {
        String place = station.city() == null ? null
                : station.state() == null || station.state().equals(station.city()) ? station.city()
                : station.city() + ", " + station.state();
        if (place != null) {
            m.append("📍 <b>").append(hi ? "स्थान" : "Location").append(":</b> ").append(esc(place)).append("\n");
        }
        m.append("📡 <b>").append(hi ? "मॉनिटरिंग स्टेशन" : "Monitoring station").append(":</b> ")
                .append(esc(station.name())).append("\n\n");
    }

    private void observed(StringBuilder m, Instant observedAt, Instant now, boolean hi, boolean reminder) {
        m.append("\n🕒 <b>").append(reminder ? (hi ? "अंतिम रीडिंग" : "Last observation") : (hi ? "मापने का समय" : "Observation time"))
                .append(":</b> ").append(esc(WHEN.format(observedAt.atZone(zone)))).append("\n")
                .append("🕐 <b>").append(hi ? "डेटा की उम्र" : "Data age").append(":</b> ")
                .append(age(Duration.between(observedAt, now), hi))
                .append(hi ? " (संदेश बनते समय)" : " (when this message was prepared)").append("\n");
    }

    private void pollutant(StringBuilder m, String dominant, boolean hi) {
        if (dominant == null || dominant.isBlank()) {
            return;   // only shown when known
        }
        m.append("\n<b>").append(hi ? "प्रदूषक जानकारी" : "Pollutant information").append("</b>\n• ")
                .append(hi ? "मुख्य प्रदूषक" : "Main pollutant").append(": ")
                .append(esc(POLLUTANTS.getOrDefault(dominant, dominant))).append("\n");
    }

    private void footer(StringBuilder m, String stationId, String linkLabel) {
        m.append("\n").append(DIVIDER).append("\n🔗 ").append(link(dashboard(stationId), linkLabel)).append("\n\n")
                .append(TAGLINE);
    }

    public String dashboard(String stationId) {
        return appUrl + "/forecast?station=" + URLEncoder.encode(stationId, StandardCharsets.UTF_8);
    }

    public static String age(Duration d, boolean hi) {
        long min = Math.max(0, d.toMinutes());
        String t = min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
        return t;
    }

    private static boolean hi(Profile p) {
        return "hi".equals(p.language());
    }
}
