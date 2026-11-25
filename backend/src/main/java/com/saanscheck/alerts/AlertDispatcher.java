package com.saanscheck.alerts;

import com.saanscheck.profile.ProfileRepository;
import com.saanscheck.telegram.TelegramClient;
import com.saanscheck.telegram.TelegramDeliveryException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Delivers PENDING alerts. Idempotency design ("record intent, claim, send, mark"):
 * <ol>
 *   <li>The evaluator has already stored the alert as PENDING (intent recorded).</li>
 *   <li>Claim: one UPDATE moves it to SENDING and increments attempts.</li>
 *   <li>Send via Telegram.</li>
 *   <li>Mark SENT, or PENDING with backoff (definitely not delivered), or FAILED.</li>
 * </ol>
 * A crash after step 3 leaves the row in SENDING; recovery marks it FAILED instead of resending,
 * so a user never gets the same alert twice. The trade-off (rarely, one alert might be lost) is
 * the right one for a notification that is re-evaluated every few minutes anyway.
 */
@Service
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);
    private static final int BATCH = 50;
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final AlertRepository alerts;
    private final ProfileRepository profiles;
    private final TelegramClient telegram;
    private final AlertProperties props;
    private final MeterRegistry metrics;
    private final Clock clock;

    public AlertDispatcher(AlertRepository alerts, ProfileRepository profiles, TelegramClient telegram,
                           AlertProperties props, MeterRegistry metrics, Clock clock) {
        this.alerts = alerts;
        this.profiles = profiles;
        this.telegram = telegram;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void dispatchDue() {
        Instant now = clock.instant();
        int recovered = alerts.failStuckSending(now.minus(props.sendingTimeout()));
        if (recovered > 0) {
            log.warn("{} alert(s) stuck in SENDING marked FAILED (outcome unknown)", recovered);
            metrics.counter("saanscheck.alerts.failed", "reason", "unknown_outcome").increment(recovered);
        }
        if (!telegram.enabled()) {
            return;   // alerts stay PENDING until a bot token is configured
        }
        for (AlertRepository.ClaimedAlert a : alerts.claimDue(now, BATCH)) {
            deliver(a);
        }
    }

    void deliver(AlertRepository.ClaimedAlert a) {
        Optional<String> chatId = profiles.findChatId(a.userId());
        if (chatId.isEmpty()) {
            alerts.markFailed(a.id(), "no Telegram chat linked");
            metrics.counter("saanscheck.alerts.failed", "reason", "no_chat").increment();
            return;
        }
        try {
            telegram.sendMessage(chatId.get(), a.message());
            alerts.markSent(a.id(), clock.instant());
            metrics.counter("saanscheck.alerts.sent").increment();
        } catch (TelegramDeliveryException e) {
            boolean retry = e.outcome() == TelegramDeliveryException.Outcome.RETRYABLE && a.attempts() < props.maxAttempts();
            if (retry) {
                Duration wait = backoff(a.attempts());
                if (e.retryAfter() != null && e.retryAfter().compareTo(wait) > 0) {
                    wait = e.retryAfter().compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : e.retryAfter();   // Telegram flood control
                }
                Instant next = clock.instant().plus(wait);
                alerts.markRetry(a.id(), next, e.getMessage());
                metrics.counter("saanscheck.alerts.retried").increment();
            } else {
                alerts.markFailed(a.id(), e.outcome() + ": " + e.getMessage());
                metrics.counter("saanscheck.alerts.failed", "reason", e.outcome().name().toLowerCase()).increment();
            }
        }
    }

    /** Exponential backoff: base * 2^(attempts-1), capped at one hour. */
    Duration backoff(int attempts) {
        Duration d = props.baseBackoff().multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 10));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
