package com.saanscheck.ingestion.health;

import com.saanscheck.ingestion.health.IngestionHealth.Status;
import com.saanscheck.telegram.TelegramClient;
import com.saanscheck.telegram.TelegramDeliveryException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import com.saanscheck.telegram.TelegramHtml;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute: evaluates ingestion health, publishes Prometheus gauges, and sends deduplicated Telegram
 * notifications to admins who linked Telegram.
 *
 * <p>Notification rules (no alert every 15 minutes for the same problem):
 * <ul>
 *   <li>PROBLEM (STALE / SOURCE_UNAVAILABLE / FAILED) is announced once, after it has persisted for
 *       {@code notify-after}, and again only if the problem status changes.</li>
 *   <li>RECOVERY is announced once when the status returns to HEALTHY or DEGRADED after a PROBLEM.</li>
 *   <li>Every announcement (including when there are no recipients) is stored in
 *       {@code ingestion_health_notifications}, so deduplication survives restarts.</li>
 * </ul>
 * Scheduling is enabled by the global {@code @EnableScheduling}; when scheduling is disabled (tests) the
 * {@link #check()} method can be called directly.
 */
@Component
public class IngestionHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(IngestionHealthMonitor.class);

    private final IngestionHealthService health;
    private final IngestionHealthProperties props;
    private final JdbcTemplate jdbc;
    private final TelegramClient telegram;
    private final Clock clock;
    private final AtomicReference<IngestionHealth> latest = new AtomicReference<>();
    private Status problemStatus;
    private Instant problemSince;

    public IngestionHealthMonitor(IngestionHealthService health, IngestionHealthProperties props, JdbcTemplate jdbc,
                                  TelegramClient telegram, MeterRegistry registry, Clock clock) {
        this.health = health;
        this.props = props;
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.clock = clock;
        for (Status s : Status.values()) {
            Gauge.builder("saanscheck.ingestion.health", latest, h -> h.get() != null && h.get().status() == s ? 1 : 0)
                    .tag("status", s.name()).description("1 for the current ingestion health status").register(registry);
        }
        gauge(registry, "saanscheck.ingestion.latest_observation_age_minutes", IngestionHealth::latestObservationAgeMinutes);
        gauge(registry, "saanscheck.ingestion.minutes_since_last_run", IngestionHealth::minutesSinceLastRun);
        gauge(registry, "saanscheck.ingestion.consecutive_failures", h -> (long) h.consecutiveFailures());
        gauge(registry, "saanscheck.stations.fresh", h -> (long) h.stationsFresh());
        gauge(registry, "saanscheck.ingestion.minutes_since_last_success",
                h -> h.lastSuccessfulRunAt() == null ? null : Duration.between(h.lastSuccessfulRunAt(), h.evaluatedAt()).toMinutes());
    }

    private void gauge(MeterRegistry registry, String name, java.util.function.Function<IngestionHealth, Long> f) {
        Gauge.builder(name, latest, h -> {
            IngestionHealth v = h.get();
            Long x = v == null ? null : f.apply(v);
            return x == null ? Double.NaN : x;
        }).register(registry);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    public void scheduledCheck() {
        try {
            check();
        } catch (RuntimeException e) {
            log.warn("Ingestion health check failed: {}", e.toString());
        }
    }

    /** Evaluates health, updates gauges and sends a notification if one is due. Returns the health. */
    public synchronized IngestionHealth check() {
        IngestionHealth h = health.current();
        latest.set(h);
        notifyIfDue(h);
        return h;
    }

    private void notifyIfDue(IngestionHealth h) {
        Instant now = clock.instant();
        Map<String, Object> last = jdbc.queryForList(
                "SELECT status, kind FROM ingestion_health_notifications ORDER BY id DESC LIMIT 1").stream()
                .findFirst().orElse(null);
        String lastKind = last == null ? null : (String) last.get("kind");
        String lastStatus = last == null ? null : (String) last.get("status");

        if (h.problem()) {
            if (h.status() != problemStatus) {
                problemStatus = h.status();
                problemSince = now;
            }
            boolean persisted = Duration.between(problemSince, now).compareTo(props.notifyAfter()) >= 0;
            boolean alreadyAnnounced = "PROBLEM".equals(lastKind) && h.status().name().equals(lastStatus);
            if (persisted && !alreadyAnnounced) {
                send("PROBLEM", h, lastStatus, adminMessage(true, h));
            }
        } else {
            problemStatus = null;
            problemSince = null;
            if ("PROBLEM".equals(lastKind) && h.status() != Status.UNKNOWN) {
                send("RECOVERY", h, lastStatus, adminMessage(false, h));
            }
        }
    }

    private void send(String kind, IngestionHealth h, String previous, String message) {
        List<String> chats = jdbc.queryForList("""
                SELECT p.telegram_chat_id FROM users u JOIN profiles p ON p.user_id = u.id
                WHERE u.role = 'ADMIN' AND p.telegram_chat_id IS NOT NULL
                """, String.class);
        int delivered = 0;
        if (telegram.enabled()) {
            for (String chat : chats) {
                try {
                    telegram.sendMessage(chat, message);
                    delivered++;
                } catch (TelegramDeliveryException e) {
                    log.warn("Ingestion {} notification to an admin chat failed: {}", kind, e.getMessage());
                }
            }
        }
        jdbc.update("""
                INSERT INTO ingestion_health_notifications (status, previous_status, kind, message, recipients)
                VALUES (?, ?, ?, ?, ?)
                """, h.status().name(), previous, kind, message, delivered);
        if (delivered == 0) {
            log.warn("Ingestion {} ({}) recorded but not delivered: no admin with a linked Telegram chat{}", kind,
                    h.status(), telegram.enabled() ? "" : " (Telegram not configured)");
        } else {
            log.info("Ingestion {} ({}) sent to {} admin chat(s)", kind, h.status(), delivered);
        }
    }

    /** Admin notice in the VayuDrishti Telegram-HTML style (all dynamic text escaped). */
    static String adminMessage(boolean problem, IngestionHealth h) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z",
                java.util.Locale.ENGLISH).withZone(java.time.ZoneId.of("Asia/Kolkata"));
        StringBuilder m = new StringBuilder(problem ? "🛰️ <b>VayuDrishti | DATA PIPELINE ALERT</b>\n"
                : "✅ <b>VayuDrishti | DATA PIPELINE RECOVERED</b>\n").append(TelegramHtml.DIVIDER).append("\n\n")
                .append("<b>Status:</b> ").append(TelegramHtml.esc(h.status().name())).append("\n\n");
        for (String r : h.reasons()) {
            m.append("• ").append(TelegramHtml.esc(r)).append("\n");
        }
        if (problem) {
            m.append("\n🕒 <b>Last successful ingestion:</b> ")
                    .append(h.lastSuccessfulRunAt() == null ? "never" : TelegramHtml.esc(f.format(h.lastSuccessfulRunAt())))
                    .append("\n🌫️ <b>Newest observation:</b> ")
                    .append(h.latestObservationAt() == null ? "none" : TelegramHtml.esc(f.format(h.latestObservationAt())))
                    .append("\n");
        }
        return TelegramHtml.fit(m.append("\n<i>Admin notification · VayuDrishti ingestion monitor</i>").toString());
    }
}
