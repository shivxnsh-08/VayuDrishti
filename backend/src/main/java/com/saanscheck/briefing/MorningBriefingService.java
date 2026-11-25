package com.saanscheck.briefing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saanscheck.advice.AlertMessageComposer;
import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.alerts.AlertRepository;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.forecast.ForecastService;
import com.saanscheck.forecast.ForecastView;
import com.saanscheck.profile.Profile;
import com.saanscheck.profile.ProfileRepository;
import com.saanscheck.stations.Station;
import com.saanscheck.stations.StationAqi;
import com.saanscheck.stations.StationAqiRepository;
import com.saanscheck.stations.StationRepository;
import com.saanscheck.telegram.TelegramClient;
import com.saanscheck.telegram.TelegramHtml;
import static com.saanscheck.telegram.TelegramHtml.DIVIDER;
import static com.saanscheck.telegram.TelegramHtml.dot;
import static com.saanscheck.telegram.TelegramHtml.esc;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Daily morning AQI briefing over Telegram.
 *
 * <p>One shared job (not one timer per user) runs every minute. A user is due once their local delivery time
 * (default 07:00 Asia/Kolkata) has passed today and we are still inside the catch-up window (3 h). The message is
 * composed from genuine data at that moment and queued in the existing notification history ({@code alerts},
 * kind MORNING_BRIEFING) with the dedupe key {@code brief:{user}:{local date}} (UNIQUE): at most one per user per
 * day, across restarts, retries, settings changes and several instances. Delivery, retries with backoff,
 * Telegram rate limits and "never resend when the outcome is unknown" are handled by the existing
 * {@link com.saanscheck.alerts.AlertDispatcher}; the stored message is exactly what is sent.
 */
@Service
public class MorningBriefingService {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefingService.class);
    private static final DateTimeFormatter OBSERVED = DateTimeFormatter.ofPattern("dd MMM HH:mm z", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final Map<String, String> POLLUTANTS = Map.of("PM25", "PM2.5", "PM10", "PM10", "NO2", "NO₂",
            "SO2", "SO₂", "O3", "O₃", "CO", "CO", "NH3", "NH₃");
    private static final Map<String, String> PROFILE_EN = Map.of("GENERAL", "", "ASTHMA", " (asthma)",
            "CHILD", " (child)", "ELDERLY", " (older adult)", "EXERCISER", " (outdoor exercise)");

    private final ProfileRepository profiles;
    private final StationRepository stations;
    private final StationAqiRepository stationAqi;
    private final ForecastService forecasts;
    private final AlertMessageComposer composer;
    private final AlertRepository alerts;
    private final TelegramClient telegram;
    private final AlertProperties alertProps;
    private final BriefingProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final ZoneId zone;

    public MorningBriefingService(ProfileRepository profiles, StationRepository stations, StationAqiRepository stationAqi,
                                  ForecastService forecasts, AlertMessageComposer composer, AlertRepository alerts,
                                  TelegramClient telegram, AlertProperties alertProps, BriefingProperties props,
                                  ObjectMapper json, Clock clock) {
        this.profiles = profiles;
        this.stations = stations;
        this.stationAqi = stationAqi;
        this.forecasts = forecasts;
        this.composer = composer;
        this.alerts = alerts;
        this.telegram = telegram;
        this.alertProps = alertProps;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.zone = ZoneId.of(alertProps.zone());
    }

    /** Scheduled delivery instant of a user's briefing for the local date of {@code now}. */
    Instant scheduledFor(Instant now, java.time.LocalTime time) {
        return now.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant();
    }

    static String dedupeKey(long userId, LocalDate localDate) {
        return "brief:" + userId + ":" + localDate;
    }

    /** One scheduler tick: queue every due briefing; returns how many were created. */
    public int queueDue() {
        Instant now = clock.instant();
        int expired = alerts.expireBriefings(now.minus(props.catchUpWindow()));
        if (expired > 0) {
            log.warn("{} morning briefing(s) could not be delivered within the window and were marked FAILED", expired);
        }
        if (!telegram.enabled()) {
            return 0;   // never queue messages that could only be delivered late, once a token is configured
        }
        int created = 0;
        for (ProfileRepository.BriefingCandidate c : profiles.briefingCandidates()) {
            try {
                if (queueIfDue(c, now)) {
                    created++;
                }
            } catch (RuntimeException e) {   // one user's problem must not block the others
                log.warn("Morning briefing for user {} could not be prepared: {}", c.userId(), e.toString());
            }
        }
        if (created > 0) {
            log.info("Queued {} morning briefing(s)", created);
        }
        return created;
    }

    boolean queueIfDue(ProfileRepository.BriefingCandidate c, Instant now) {
        Instant scheduled = scheduledFor(now, c.time());
        if (now.isBefore(scheduled) || !now.isBefore(scheduled.plus(props.catchUpWindow()))) {
            return false;
        }
        String key = dedupeKey(c.userId(), now.atZone(zone).toLocalDate());
        if (alerts.existsByDedupeKey(key)) {
            return false;                    // already queued/sent today (cheap check before composing)
        }
        Optional<Profile> profile = profiles.find(c.userId());
        if (profile.isEmpty() || profile.get().stationId() == null) {
            return false;
        }
        Briefing b = compose(profile.get(), now);
        return alerts.insertBriefing(c.userId(), profile.get().stationId(), key, b.aqi(), b.category(), b.message(),
                scheduled, b.contextJson(), now);
    }

    record Briefing(String message, Integer aqi, String category, String contextJson) {
    }

    /** Builds the message (Telegram HTML) from the latest genuine data; nothing is estimated or filled in. */
    Briefing compose(Profile p, Instant now) {
        boolean hi = "hi".equals(p.language());
        String stationId = p.stationId();
        Optional<Station> st = stations.findById(stationId);
        String stationName = st.map(Station::name).orElse(stationId);
        String place = st.map(s -> s.city() == null ? null
                : s.state() == null || s.state().equals(s.city()) ? s.city() : s.city() + ", " + s.state()).orElse(null);
        Optional<StationAqi> latest = stationAqi.findLatest(stationId).filter(a -> a.aqi() != null && a.ts() != null);
        String dashboard = props.appUrl() + "/forecast?station=" + java.net.URLEncoder.encode(stationId,
                java.nio.charset.StandardCharsets.UTF_8);
        String today = DAY.format(now.atZone(zone));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("stationId", stationId);
        ctx.put("stationName", stationName);
        StringBuilder m = new StringBuilder();
        m.append("🌅 <b>").append(hi ? "सुप्रभात | VayuDrishti" : "Good Morning | VayuDrishti").append("</b>\n")
                .append("<i>").append(esc(today)).append("</i>\n").append(DIVIDER).append("\n\n")
                .append("📍 <b>").append(hi ? "आपका मॉनिटरिंग स्टेशन" : "Your monitoring station").append("</b>\n")
                .append(esc(stationName)).append(place == null ? "" : "\n" + esc(place)).append("\n\n");

        if (latest.isEmpty()) {
            ctx.put("observation", "NONE");
            m.append("⚪ ").append(hi ? "इस स्टेशन के लिए अभी कोई मान्य AQI रीडिंग उपलब्ध नहीं है, इसलिए कोई AQI नहीं बताया जा रहा।"
                            : "No valid AQI reading is available for this station yet, so no AQI is reported.")
                    .append("\n");
            footer(m, dashboard, hi);
            return new Briefing(TelegramHtml.fit(m.toString()), null, null, toJson(ctx));
        }

        StationAqi a = latest.get();
        AqiCategory category = AqiCategory.valueOf(a.category());
        Duration age = Duration.between(a.ts(), now);
        boolean stale = age.compareTo(alertProps.staleAfter()) > 0;
        ctx.put("aqi", a.aqi());
        ctx.put("category", category.name());
        ctx.put("dominantPollutant", a.dominantPollutant());
        ctx.put("observedAt", a.ts().toString());
        ctx.put("stale", stale);

        m.append("🌫️ <b>").append(hi ? "आज की वायु गुणवत्ता" : "Today's Air Quality").append("</b>\n")
                .append("AQI: <b>").append(a.aqi()).append("</b>\n")
                .append(dot(category)).append(" ").append(hi ? "श्रेणी" : "Category").append(": ")
                .append(esc(composer.categoryLabel(p, category))).append("\n");
        if (a.dominantPollutant() != null) {
            m.append(hi ? "मुख्य प्रदूषक" : "Main pollutant").append(": ")
                    .append(esc(POLLUTANTS.getOrDefault(a.dominantPollutant(), a.dominantPollutant()))).append("\n");
        }
        m.append("\n🕒 ").append(hi ? "मापा गया" : "Observed").append(": ").append(esc(OBSERVED.format(a.ts().atZone(zone))))
                .append("\n🕐 ").append(hi ? "डेटा की उम्र" : "Data age").append(": ").append(ago(age, hi)).append("\n");
        if (stale) {
            m.append("\n⚠️ <b>").append(hi ? "पुराना डेटा" : "Stale data").append(":</b> ")
                    .append(hi ? "यह रीडिंग पुरानी है और वर्तमान वायु गुणवत्ता नहीं दिखाती। सलाह केवल हाल की रीडिंग पर दी जाती है।"
                            : "this reading is STALE - it does not show the current air quality. Health advice is only given for recent readings.")
                    .append("\n");
        } else {
            m.append("\n<b>").append(hi ? "आपके लिए स्वास्थ्य सलाह" : "Health guidance for you")
                    .append(hi ? "" : esc(PROFILE_EN.getOrDefault(p.healthProfile().name(), ""))).append("</b>\n")
                    .append(TelegramHtml.clip(composer.adviceFor(p, category, a.aqi(), a.ts()), 1200)).append("\n");
        }
        m.append("\n🔮 <b>").append(hi ? "AQI पूर्वानुमान" : "AQI Forecast").append("</b>\n")
                .append(forecastLine(stationId, now, hi, ctx)).append("\n");
        footer(m, dashboard, hi);
        return new Briefing(TelegramHtml.fit(m.toString()), a.aqi(), category.name(), toJson(ctx));
    }

    private static void footer(StringBuilder m, String dashboard, boolean hi) {
        m.append("\n").append(DIVIDER).append("\n🔗 ")
                .append(TelegramHtml.link(dashboard, hi ? "अपना डैशबोर्ड खोलें" : "Open Your Dashboard")).append("\n\n<i>")
                .append(hi ? "आपका दिन स्वस्थ रहे। अपनी साँसों की हवा के प्रति जागरूक रहें।"
                        : "Have a healthy day. Stay aware of the air you breathe.").append("</i>");
    }

    /** Today's outlook from the existing forecast service, labelled baseline vs ML; only real forecast points. */
    private String forecastLine(String stationId, Instant now, boolean hi, Map<String, Object> ctx) {
        ForecastView f;
        try {
            f = forecasts.getForecast(stationId);
        } catch (RuntimeException e) {
            f = ForecastView.unavailable(stationId, "forecast service unreachable");
        }
        ctx.put("forecastStatus", f.status().name());
        ctx.put("forecastMethod", f.method());
        LocalDate day = now.atZone(zone).toLocalDate();
        List<ForecastView.Point> todays = f.available() ? f.points().stream()
                .filter(pt -> !pt.targetTs().isBefore(now) && pt.targetTs().atZone(zone).toLocalDate().equals(day))
                .toList() : List.of();
        if (todays.isEmpty()) {
            String why = f.message() != null ? f.message()
                    : f.available() ? "no forecast points left for today" : f.status().name().toLowerCase().replace('_', ' ');
            return (hi ? "आज का पूर्वानुमान उपलब्ध नहीं (" : "Today's forecast: not available (") + esc(why) + ").";
        }
        ForecastView.Point peak = todays.stream().max(Comparator.comparingInt(ForecastView.Point::predictedAqi)).orElseThrow();
        int low = todays.stream().mapToInt(ForecastView.Point::predictedAqi).min().orElse(peak.predictedAqi());
        boolean model = "MODEL".equals(f.method());
        String method = model ? (hi ? "ML मॉडल " : "ML model ") + f.modelVersion()
                : (hi ? "बेसलाइन अनुमान (ML मॉडल नहीं)" : "baseline estimate, not an ML model");
        ctx.put("forecastPeak", peak.predictedAqi());
        return (hi ? "आज: " : "Today's forecast (") + esc(method) + (hi ? " - " : "): ")
                + (hi ? "अधिकतम " : "up to ") + "<b>" + peak.predictedAqi() + "</b> ("
                + esc(peak.category().replace('_', ' ').toLowerCase())
                + (hi ? ") लगभग " : ") around ") + esc(TIME.format(peak.targetTs().atZone(zone)))
                + (hi ? ", न्यूनतम " : ", lowest ") + low + ".";
    }

    private static String ago(Duration d, boolean hi) {
        long min = Math.max(0, d.toMinutes());
        String text = min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
        return hi ? text + " पहले" : text + " ago";
    }

    private String toJson(Map<String, Object> ctx) {
        try {
            return json.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
