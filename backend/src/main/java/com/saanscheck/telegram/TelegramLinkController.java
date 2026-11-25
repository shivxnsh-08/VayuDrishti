package com.saanscheck.telegram;

import com.saanscheck.common.ConflictException;
import com.saanscheck.common.RateLimitedException;
import com.saanscheck.common.SlidingWindowLimiter;
import com.saanscheck.security.CurrentUser;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in user's Telegram connection. Every endpoint acts only on the caller's own account and never
 * returns a chat id.
 */
@RestController
@RequestMapping("/api/v1/me/telegram")
public class TelegramLinkController {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkController.class);

    static final int MAX_TEST_MESSAGES = 3;
    static final Duration TEST_WINDOW = Duration.ofMinutes(10);

    private final TelegramLinkService links;
    private final TelegramBotInfo bot;
    private final TelegramClient client;
    private final SlidingWindowLimiter testMessages;
    private final MeterRegistry metrics;

    private final com.saanscheck.profile.ProfileRepository profiles;
    private final com.saanscheck.stations.StationRepository stations;
    private final com.saanscheck.advice.TelegramMessages messages;
    private final com.saanscheck.alerts.AlertRepository history;
    private final Clock clock;

    public TelegramLinkController(TelegramLinkService links, TelegramBotInfo bot, TelegramClient client, Clock clock,
                                  MeterRegistry metrics, com.saanscheck.profile.ProfileRepository profiles,
                                  com.saanscheck.stations.StationRepository stations,
                                  com.saanscheck.advice.TelegramMessages messages,
                                  com.saanscheck.alerts.AlertRepository history) {
        this.profiles = profiles;
        this.stations = stations;
        this.messages = messages;
        this.history = history;
        this.clock = clock;
        this.links = links;
        this.bot = bot;
        this.client = client;
        this.metrics = metrics;
        this.testMessages = new SlidingWindowLimiter(MAX_TEST_MESSAGES, TEST_WINDOW, clock);
    }

    /** Connection status and the bot to talk to (username from configuration). */
    @GetMapping
    public TelegramStatus status(@AuthenticationPrincipal Jwt jwt) {
        String username = bot.botUsername();
        return new TelegramStatus(bot.configured(), bot.state().name(), username,
                username == null ? null : "https://t.me/" + username, links.isUserLinked(CurrentUser.id(jwt)));
    }

    @PostMapping("/link")
    public TelegramLinkService.LinkCode link(@AuthenticationPrincipal Jwt jwt) {
        return links.createCode(CurrentUser.id(jwt));
    }

    @DeleteMapping
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal Jwt jwt) {
        links.unlinkUser(CurrentUser.id(jwt));
        return ResponseEntity.noContent().build();
    }

    /**
     * Sends a test message to the caller's own linked chat (throttled). The target is looked up from the
     * authenticated account; the request body is ignored, so a client can never choose another chat.
     */
    @PostMapping("/test")
    public TestResult test(@AuthenticationPrincipal Jwt jwt) {
        long userId = CurrentUser.id(jwt);
        if (!bot.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telegram is not configured on this server");
        }
        String chat = links.chatOf(userId).orElseThrow(() -> new ConflictException("Telegram is not linked to this account"));
        if (!testMessages.tryAcquire("user:" + userId)) {
            throw new RateLimitedException("Too many test messages; try again in a few minutes");
        }
        var profile = profiles.find(userId).orElse(null);
        String stationId = profile == null ? null : profile.stationId();
        String stationName = stationId == null ? null
                : stations.findById(stationId).map(com.saanscheck.stations.Station::name).orElse(stationId);
        var briefing = profiles.briefingSettings(userId);
        String text = messages.testMessage(profile, stationName, profiles.aqiAlertsEnabled(userId), briefing.enabled(),
                briefing.time());
        try {
            client.sendMessage(chat, text);
            history.recordDirect(userId, stationId, com.saanscheck.alerts.AlertKind.TEST_MESSAGE, text, true, null,
                    clock.instant());
            log.info("Telegram test message for user {}: SENT", userId);
            metrics.counter("saanscheck.telegram.test_messages", "outcome", "sent").increment();
            return new TestResult(true, "Test message sent. Check Telegram.");
        } catch (TelegramDeliveryException e) {
            log.warn("Telegram test message for user {}: FAILED ({}: {})", userId, e.outcome(), e.getMessage());
            history.recordDirect(userId, stationId, com.saanscheck.alerts.AlertKind.TEST_MESSAGE, text, false,
                    e.outcome() + ": " + e.getMessage(), clock.instant());
            metrics.counter("saanscheck.telegram.test_messages", "outcome", e.outcome().name().toLowerCase()).increment();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Telegram did not accept the message (" + e.outcome() + "); if you blocked the bot, unblock it or link again");
        }
    }

    public record TelegramStatus(boolean configured, String botState, String botUsername, String botLink, boolean linked) {
    }

    public record TestResult(boolean sent, String message) {
    }
}
